package io.boffin.vmforge

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File

/**
 * Extracts a PRoot rootfs tarball (.tar.gz, e.g. a Debian/Ubuntu/Alpine
 * arm64 base rootfs from proot-distro or debootstrap) into
 * filesDir/proot-rootfs/.
 *
 * The picked .tar.gz is re-streamed through Kotlin first — dropping any
 * device/FIFO entries and the archive's own root entry, converting hard
 * links to symlinks, stripping setuid/setgid/sticky bits, and skipping
 * mtime on symlinks — into a plain tar stream piped to a bundled
 * `busybox tar` (jniLibs/arm64-v8a/libbusybox.so) over stdin, with
 * --no-same-owner so it doesn't attempt chown(). Everything else (regular
 * files, directories, symlinks, permissions) passes through unmodified.
 *
 * IMPORTANT: extraction pipes to stdin ("-xf -"), not a real temp file.
 * An earlier version switched to a real file specifically to make
 * [findCrashingEntry]'s binary search immune to pipe-buffering ambiguity
 * — but that switch turned out to change busybox's own behavior (a real,
 * seekable file apparently takes a different internal code path than a
 * pipe) and *introduced* a new crash on the very first entry that never
 * happened with piping, even before any of the filters below existed.
 * Binary search doesn't actually need a real file to stay reliable: each
 * probe just checks whether a complete, self-contained prefix-limited
 * archive succeeds or fails as a whole, which works the same over a pipe.
 *
 * Why the filtering: extracting a full Linux rootfs needs mknod(),
 * link()/linkat(), setxattr()/lsetxattr(), and (seemingly) various
 * symlink-specific attribute calls. Android's seccomp-bpf filter —
 * installed by Zygote for every regular app process — kills the whole
 * process outright (SIGSYS, exit 159) for these, rather than returning a
 * normal error tar could just warn about and continue past.
 *
 * Earlier abandoned approaches, for anyone re-reading this history: a
 * hand-rolled Kotlin tar *parser* (writing files itself) silently dropped
 * some symlinks; wrapping extraction in `proot -0` hit a proot path-
 * canonicalization bug on Android's tilde-containing install path.
 */
object RootfsImporter {

    fun extract(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        Thread {
            val destDir = File(context.filesDir, "proot-rootfs")
            val busybox = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")

            if (!busybox.exists() || !busybox.canExecute()) {
                onDone(
                    false,
                    "Bundled busybox not found at ${busybox.absolutePath} — " +
                        "add libbusybox.so to app/src/main/jniLibs/arm64-v8a/"
                )
                return@Thread
            }

            try {
                val (exitCode, log) = pipeFilteredTarAndExtract(context, uri, busybox, destDir, Int.MAX_VALUE)

                if (exitCode != 0) {
                    var message = "tar extraction failed (exit $exitCode): ${log.takeLast(2000)}"
                    if (exitCode == 159) {
                        // SIGSYS (seccomp kill) — pin down exactly which
                        // entry by binary-searching prefix lengths.
                        val culprit = findCrashingEntry(context, uri, busybox)
                        message = "tar extraction crashed (exit 159, seccomp-killed on a " +
                            "privileged syscall) at entry: ${culprit ?: "(could not isolate — " +
                            "crash may not be reproducible in isolation)"}"
                    }
                    onDone(false, message)
                    return@Thread
                }

                val shellOk = resolvesToRealFile(destDir, "bin/sh") || resolvesToRealFile(destDir, "usr/bin/sh")
                if (!shellOk) {
                    onDone(
                        false,
                        "Extraction finished but no /bin/sh or /usr/bin/sh was found — " +
                            "wrong or incompatible rootfs archive"
                    )
                    return@Thread
                }

                onDone(true, "Rootfs extracted successfully")
            } catch (e: Exception) {
                onDone(false, e.message ?: "unknown error")
            }
        }.start()
    }

    /**
     * Binary-searches the smallest filtered-entry-count prefix that still
     * crashes busybox, then returns full details of that boundary entry —
     * the one whose inclusion is what actually triggers the SIGSYS. Each
     * probe pipes a fresh, complete, self-contained prefix to a new
     * busybox process and only checks its exit code, so the result is
     * exact regardless of pipe buffering.
     */
    private fun findCrashingEntry(context: Context, uri: Uri, busybox: File): String? {
        val total = countFilteredEntries(context, uri)
        if (total == 0) return null

        val probeDest = File(context.cacheDir, "rootfs-probe-dest")
        try {
            var low = 0      // largest known-good prefix count
            var high = total // smallest known-bad prefix count

            if (pipeFilteredTarAndExtract(context, uri, busybox, probeDest, high).first != 159) return null

            while (high - low > 1) {
                val mid = (low + high) / 2
                val (code, _) = pipeFilteredTarAndExtract(context, uri, busybox, probeDest, mid)
                if (code == 159) high = mid else low = mid
            }
            return detailsOfFilteredEntry(context, uri, high - 1) // 0-indexed, last entry in the failing prefix
        } finally {
            probeDest.deleteRecursively()
        }
    }

    /** Parses the archive, applying the same filter as extraction, without buffering file data. */
    private fun countFilteredEntries(context: Context, uri: Uri): Int {
        var count = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            GzipCompressorInputStream(raw).use { gz ->
                TarArchiveInputStream(gz).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        if (!isDeviceOrFifo(entry) && !isArchiveRoot(entry)) count++
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
        return count
    }

    /** Full diagnostic details for the [index]-th (0-based) filtered entry. */
    private fun detailsOfFilteredEntry(context: Context, uri: Uri, index: Int): String? {
        var current = -1
        context.contentResolver.openInputStream(uri)?.use { raw ->
            GzipCompressorInputStream(raw).use { gz ->
                TarArchiveInputStream(gz).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        if (!isDeviceOrFifo(entry) && !isArchiveRoot(entry)) {
                            current++
                            if (current == index) {
                                val type = when {
                                    entry.isDirectory -> "directory"
                                    entry.isSymbolicLink -> "symlink -> ${entry.linkName}"
                                    entry.isLink -> "hardlink -> ${entry.linkName}"
                                    else -> "file"
                                }
                                return "name='${entry.name}' type=$type mode=${
                                    Integer.toOctalString(entry.mode)
                                } size=${entry.size}"
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
        return null
    }

    /**
     * Filters the archive down to the first [entryLimit] surviving entries
     * and pipes them as a plain tar stream directly into a fresh busybox
     * process's stdin, extracting into [destDir]. Returns (exitCode,
     * combined stdout+stderr).
     */
    private fun pipeFilteredTarAndExtract(
        context: Context,
        uri: Uri,
        busybox: File,
        destDir: File,
        entryLimit: Int
    ): Pair<Int, String> {
        destDir.deleteRecursively()
        destDir.mkdirs()

        val process = ProcessBuilder(
            busybox.absolutePath, "tar", "--no-same-owner", "-xf", "-", "-C", destDir.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val pumpThread = Thread {
            try {
                var written = 0
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    GzipCompressorInputStream(raw).use { gz ->
                        TarArchiveInputStream(gz).use { tarIn ->
                            TarArchiveOutputStream(process.outputStream).apply {
                                setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                                setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                            }.use { tarOut ->
                                var entry = tarIn.nextTarEntry
                                while (entry != null && written < entryLimit) {
                                    if (!isDeviceOrFifo(entry) && !isArchiveRoot(entry)) {
                                        writeCleanEntry(tarOut, tarIn, entry)
                                        written++
                                    }
                                    entry = tarIn.nextTarEntry
                                }
                                tarOut.finish()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // A broken pipe here just means busybox already exited
                // (crashed or otherwise) before we finished writing —
                // the exit code below is what matters, not this.
            }
        }
        pumpThread.start()

        val log = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        pumpThread.join()

        return exitCode to log
    }

    private fun writeCleanEntry(tarOut: TarArchiveOutputStream, tarIn: TarArchiveInputStream, entry: TarArchiveEntry) {
        val linkFlag = if (entry.isLink) TarConstants.LF_SYMLINK else entry.linkFlag
        val clean = TarArchiveEntry(entry.name, linkFlag)
        clean.size = entry.size
        if (entry.isSymbolicLink || entry.isLink) {
            // Symlink permissions are meaningless on Linux (the kernel
            // always treats them as effectively 777) — use the
            // conventional default instead of the archive's stored mode,
            // and don't set mtime either (needs utimensat with
            // AT_SYMLINK_NOFOLLOW). Both are candidates for the same
            // seccomp-kill pattern as mknod/link/setxattr.
            clean.mode = 0x1FF // 0777
            clean.linkName = entry.linkName
        } else {
            // Strip setuid/setgid/sticky (04000/02000/01000) — chmod()
            // with those bits set is also a seccomp-kill candidate.
            clean.mode = entry.mode and 0x1FF // keep rwxrwxrwx only
            clean.setModTime(entry.modTime)
        }
        tarOut.putArchiveEntry(clean)
        if (!entry.isDirectory && !entry.isSymbolicLink && !entry.isLink) {
            tarIn.copyTo(tarOut)
        }
        tarOut.closeArchiveEntry()
    }

    private fun isDeviceOrFifo(entry: TarArchiveEntry): Boolean =
        entry.isFIFO || entry.isCharacterDevice || entry.isBlockDevice

    /**
     * True for the archive's own root entry (".", "./") — this maps to the
     * exact same path as -C destDir, which we already create ourselves in
     * Kotlin before invoking tar, so it's redundant to extract regardless
     * of whether it was ever actually the cause of a crash.
     */
    private fun isArchiveRoot(entry: TarArchiveEntry): Boolean {
        val normalized = entry.name.removePrefix("./").removeSuffix("/")
        return normalized.isEmpty()
    }

    /**
     * Manually resolves a path within [root], following symlinks (relative or
     * absolute-as-if-rooted-at [root], the same way proot itself would) up to
     * a small depth limit, and reports whether it lands on a real, non-empty
     * regular file.
     */
    private fun resolvesToRealFile(root: File, relativePath: String, depth: Int = 0): Boolean {
        if (depth > 10) return false
        val target = File(root, relativePath.removePrefix("/"))
        if (!java.nio.file.Files.isSymbolicLink(target.toPath())) {
            return target.isFile && target.length() > 0
        }
        val link = java.nio.file.Files.readSymbolicLink(target.toPath()).toString()
        val nextRelative = if (link.startsWith("/")) {
            link
        } else {
            File(target.parentFile, link).path.removePrefix(root.path).removePrefix("/")
        }
        return resolvesToRealFile(root, nextRelative, depth + 1)
    }
}
