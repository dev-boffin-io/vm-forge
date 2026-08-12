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
 * device/FIFO entries, converting hard links to symlinks, and rebuilding
 * every entry without its original PAX extended records (xattrs/
 * capabilities/ACLs) — into a plain tar file fed to a bundled `busybox
 * tar` (jniLibs/arm64-v8a/libbusybox.so). Everything else (regular files,
 * directories, symlinks, permissions) passes through unmodified.
 *
 * Why all this filtering: extracting a full Linux rootfs needs mknod(),
 * link()/linkat(), and setxattr()/lsetxattr(). Android's seccomp-bpf
 * filter — installed by Zygote for every regular app process — kills the
 * whole process outright (SIGSYS, exit 159) for these, rather than
 * returning a normal error tar could just warn about and continue past.
 * A fourth crash, on the archive's own root entry ("./"), turned out to
 * be unrelated to any of that — see [isArchiveRoot].
 *
 * Extraction reads from a real temp *file*, not a stdin pipe: piping made
 * "which entry was tar processing when it died" fundamentally unreliable
 * — SIGSYS is abrupt enough that busybox's own buffered stdout (even with
 * -v) never gets flushed, and our writer thread can be a pipe-buffer's
 * worth of entries ahead of what tar actually consumed. If extraction
 * still fails after the three known filters, [findCrashingEntry] does a
 * binary search — re-extracting bounded *prefixes* of the filtered entry
 * list against real files, immune to buffering — to name the exact entry
 * so a fourth filter can be added with certainty instead of another guess.
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
            val tmpTar = File(context.cacheDir, "rootfs-filtered.tar")

            if (!busybox.exists() || !busybox.canExecute()) {
                onDone(
                    false,
                    "Bundled busybox not found at ${busybox.absolutePath} — " +
                        "add libbusybox.so to app/src/main/jniLibs/arm64-v8a/"
                )
                return@Thread
            }

            try {
                // Build the fully-filtered tar as a real file first — see
                // class doc for why (removes pipe-buffering ambiguity).
                writeFilteredTar(context, uri, tmpTar, entryLimit = Int.MAX_VALUE)

                val (exitCode, log) = runBusyboxTar(busybox, tmpTar, destDir)

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
            } finally {
                tmpTar.delete()
            }
        }.start()
    }

    /**
     * Binary-searches the smallest filtered-entry-count prefix that still
     * crashes busybox, then returns the name of that boundary entry — the
     * one whose inclusion is what actually triggers the SIGSYS. Each probe
     * is a fresh, complete, real tar file (no pipe involved), so the
     * result is exact regardless of buffering.
     */
    private fun findCrashingEntry(context: Context, uri: Uri, busybox: File): String? {
        val total = countFilteredEntries(context, uri)
        if (total == 0) return null

        val probeTar = File(context.cacheDir, "rootfs-probe.tar")
        val probeDest = File(context.cacheDir, "rootfs-probe-dest")
        try {
            var low = 0      // largest known-good prefix count
            var high = total // smallest known-bad prefix count

            writeFilteredTar(context, uri, probeTar, entryLimit = high)
            if (runBusyboxTar(busybox, probeTar, probeDest).first != 159) return null

            while (high - low > 1) {
                val mid = (low + high) / 2
                writeFilteredTar(context, uri, probeTar, entryLimit = mid)
                val (code, _) = runBusyboxTar(busybox, probeTar, probeDest)
                if (code == 159) high = mid else low = mid
            }
            return nameOfFilteredEntry(context, uri, high - 1) // 0-indexed, last entry in the failing prefix
        } finally {
            probeTar.delete()
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

    /** Name of the [index]-th (0-based) filtered entry, without buffering file data. */
    private fun nameOfFilteredEntry(context: Context, uri: Uri, index: Int): String? {
        var current = -1
        context.contentResolver.openInputStream(uri)?.use { raw ->
            GzipCompressorInputStream(raw).use { gz ->
                TarArchiveInputStream(gz).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        if (!isDeviceOrFifo(entry) && !isArchiveRoot(entry)) {
                            current++
                            if (current == index) return entry.name
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
        return null
    }

    /**
     * Writes the first [entryLimit] filtered entries as a real tar file at
     * [dest], streaming file data directly from the source archive (never
     * buffering a whole file's bytes in memory).
     */
    private fun writeFilteredTar(context: Context, uri: Uri, dest: File, entryLimit: Int) {
        var written = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            GzipCompressorInputStream(raw).use { gz ->
                TarArchiveInputStream(gz).use { tarIn ->
                    dest.outputStream().use { fileOut ->
                        TarArchiveOutputStream(fileOut).apply {
                            setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                            setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                        }.use { tarOut ->
                            var entry = tarIn.nextTarEntry
                            while (entry != null && written < entryLimit) {
                                if (!isDeviceOrFifo(entry) && !isArchiveRoot(entry)) {
                                    val linkFlag = if (entry.isLink) TarConstants.LF_SYMLINK else entry.linkFlag
                                    val clean = TarArchiveEntry(entry.name, linkFlag)
                                    clean.size = entry.size
                                    if (entry.isSymbolicLink || entry.isLink) {
                                        // Symlink permissions are meaningless on Linux
                                        // (the kernel always treats them as effectively
                                        // 777) — use the conventional default instead of
                                        // the archive's stored mode, so busybox has no
                                        // reason to attempt an explicit symlink-chmod
                                        // after creating it. That call (fchmodat with
                                        // AT_SYMLINK_NOFOLLOW, possibly the newer
                                        // fchmodat2) is apparently also seccomp-killed —
                                        // this was the crash on "bin" (bin -> usr/bin).
                                        clean.mode = 0x1FF // 0777
                                        clean.linkName = entry.linkName
                                    } else {
                                        // Strip setuid/setgid/sticky (04000/02000/01000)
                                        // — chmod() with those bits set is also
                                        // seccomp-killed, same as mknod/link/setxattr.
                                        clean.mode = entry.mode and 0x1FF // keep rwxrwxrwx only
                                    }
                                    clean.setModTime(entry.modTime)
                                    tarOut.putArchiveEntry(clean)
                                    if (!entry.isDirectory && !entry.isSymbolicLink && !entry.isLink) {
                                        tarIn.copyTo(tarOut)
                                    }
                                    tarOut.closeArchiveEntry()
                                    written++
                                }
                                entry = tarIn.nextTarEntry
                            }
                            tarOut.finish()
                        }
                    }
                }
            }
        }
    }

    private fun runBusyboxTar(busybox: File, tarFile: File, destDir: File): Pair<Int, String> {
        destDir.deleteRecursively()
        destDir.mkdirs()
        val process = ProcessBuilder(
            busybox.absolutePath, "tar", "-xf", tarFile.absolutePath, "-C", destDir.absolutePath
        )
            .redirectErrorStream(true)
            .start()
        val log = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return exitCode to log
    }

    private fun isDeviceOrFifo(entry: TarArchiveEntry): Boolean =
        entry.isFIFO || entry.isCharacterDevice || entry.isBlockDevice

    /**
     * True for the archive's own root entry (".", "./") — this maps to the
     * exact same path as -C destDir, which we already create ourselves in
     * Kotlin before invoking tar. Extracting it turned out to be what was
     * actually crashing (found via binary search — not the setuid/setgid
     * mode bits, which were the working theory but didn't fix it): tar
     * appears to do something to this self-referential entry (rename/chmod
     * on what's effectively its own working directory) that trips the same
     * seccomp kill. Skipping it entirely sidesteps whatever that is.
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
