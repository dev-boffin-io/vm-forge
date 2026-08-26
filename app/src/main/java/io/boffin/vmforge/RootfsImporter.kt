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
 * filesDir.parentFile/local/proot-rootfs — a sibling of filesDir, NOT a
 * subdirectory of it. See [ROOTFS_DIR] for why this location specifically.
 *
 * FULL HISTORY, for anyone re-reading this — the long road here:
 * 1. Extracting a full Linux rootfs needs mknod(), link()/linkat(), and
 *    setxattr()/lsetxattr(). Android's seccomp-bpf filter — installed by
 *    Zygote for every regular app process — kills the whole process
 *    outright (SIGSYS) for these. Fixed by filtering in Kotlin: drop
 *    device/FIFO entries and the archive's own root entry, convert hard
 *    links to symlinks, strip setuid/setgid/sticky bits, skip mtime on
 *    symlinks — piping the result into a real `tar` (prefer /system/bin/tar
 *    or toybox; fall back to bundled busybox) rather than parsing the tar
 *    format by hand (an earlier hand-rolled parser silently dropped some
 *    symlinks).
 * 2. But *running* anything from the extracted rootfs still failed —
 *    proot reported "execve(...): No such file or directory" for files
 *    proven to exist (verified byte-for-byte, including the ELF
 *    interpreter, and confirmed via a clean proot-independent test that
 *    this device returns EACCES for exec() from filesDir specifically).
 * 3. Tried routing everything through Shizuku (shell UID) — technically
 *    worked but heavyweight, and turned out to be solving the wrong
 *    problem: comparing against a known-working reference app
 *    (RohitKushvaha01/ReTerminal) showed it keeps its rootfs at
 *    filesDir.parentFile/"local"/... — a *sibling* of filesDir, not
 *    inside it — and needs no Shizuku/root at all for this. The
 *    exec-from-app-data restriction on this device apparently targets
 *    the "files" subdirectory specifically, not the whole private data
 *    root. Switched to the same sibling-directory pattern; Shizuku
 *    removed as unnecessary.
 */
object RootfsImporter {

    /**
     * filesDir.parentFile/local/proot-rootfs — deliberately a sibling of
     * filesDir (i.e. /data/data/<pkg>/local/proot-rootfs), not
     * filesDir/proot-rootfs. See class doc point 3: this device denies
     * exec() specifically for files under the "files" subdirectory, and
     * ReTerminal's proven-working pattern is to keep an executable rootfs
     * in a differently-named sibling directory instead.
     */
    fun rootfsDir(context: Context): File =
        File(File(context.filesDir.parentFile, "local"), "proot-rootfs")

    fun extract(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        Thread {
            val destDir = rootfsDir(context)
            val busybox = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")

            val tarCmd = resolveTarCommand(context)
            if (!File(tarCmd.first()).canExecute()) {
                onDone(
                    false,
                    "No usable tar found (checked /system/bin/tar, /system/bin/toybox, and " +
                        "bundled ${busybox.absolutePath}) — add libbusybox.so to " +
                        "app/src/main/jniLibs/arm64-v8a/ as a fallback"
                )
                return@Thread
            }

            try {
                val (exitCode, log) = pipeFilteredTarAndExtract(context, uri, tarCmd, destDir, Int.MAX_VALUE)

                if (exitCode != 0) {
                    var message = "tar extraction failed (exit $exitCode): ${log.takeLast(2000)}"
                    if (exitCode == 159) {
                        val culprit = findCrashingEntry(context, uri, tarCmd)
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

                onDone(true, "Rootfs extracted successfully to ${destDir.absolutePath}")
            } catch (e: Exception) {
                onDone(false, e.message ?: "unknown error")
            }
        }.start()
    }

    /**
     * Prefers the device's own tar (usually a toybox applet — stock
     * Android has used toybox for its built-in command-line utilities
     * since Marshmallow) over our bundled busybox, since a prior static
     * busybox build had its own problems unrelated to any of this.
     * Returns the argv prefix to invoke it.
     */
    private fun resolveTarCommand(context: Context): List<String> {
        val busybox = File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        val candidates = listOf(
            listOf("/system/bin/tar"),
            listOf("/system/bin/toybox", "tar"),
            listOf(busybox.absolutePath, "tar")
        )
        return candidates.firstOrNull { File(it.first()).canExecute() } ?: listOf(busybox.absolutePath, "tar")
    }

    /**
     * Binary-searches the smallest filtered-entry-count prefix that still
     * crashes, then returns full details of that boundary entry. Each
     * probe pipes a fresh, complete, self-contained prefix to a new tar
     * process and only checks its exit code, so the result is exact
     * regardless of pipe buffering.
     */
    private fun findCrashingEntry(context: Context, uri: Uri, tarCmd: List<String>): String? {
        val total = countFilteredEntries(context, uri)
        if (total == 0) return null

        val probeDest = File(context.cacheDir, "rootfs-probe-dest")
        try {
            var low = 0
            var high = total

            if (pipeFilteredTarAndExtract(context, uri, tarCmd, probeDest, high).first != 159) return null

            while (high - low > 1) {
                val mid = (low + high) / 2
                val (code, _) = pipeFilteredTarAndExtract(context, uri, tarCmd, probeDest, mid)
                if (code == 159) high = mid else low = mid
            }
            return detailsOfFilteredEntry(context, uri, high - 1)
        } finally {
            probeDest.deleteRecursively()
        }
    }

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

    private fun pipeFilteredTarAndExtract(
        context: Context,
        uri: Uri,
        tarCmd: List<String>,
        destDir: File,
        entryLimit: Int
    ): Pair<Int, String> {
        destDir.deleteRecursively()
        destDir.mkdirs()

        val process = ProcessBuilder(
            tarCmd + listOf("--no-same-owner", "-xf", "-", "-C", destDir.absolutePath)
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
                // A broken pipe here just means tar already exited before
                // we finished writing — the exit code below is what matters.
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
