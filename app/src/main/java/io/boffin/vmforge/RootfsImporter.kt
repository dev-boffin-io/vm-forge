package io.boffin.vmforge

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File

/**
 * Extracts a PRoot rootfs tarball (.tar.gz, e.g. a Debian/Ubuntu/Alpine
 * arm64 base rootfs from proot-distro or debootstrap) into
 * filesDir/proot-rootfs/.
 *
 * The picked .tar.gz is re-streamed through Kotlin first — dropping any
 * device/FIFO entries (dev/null, dev/console, ...), converting hard links
 * to symlinks, and rebuilding every entry without its original PAX
 * extended records (xattrs/capabilities/ACLs) — into a plain, uncompressed
 * tar stream fed to a bundled `busybox tar` (jniLibs/arm64-v8a/
 * libbusybox.so) over stdin. Everything else (regular files, directories,
 * symlinks, permissions) passes through unmodified, so usrmerge-style
 * layouts and permission bits come out correct.
 *
 * Why all this filtering: extracting a full Linux rootfs needs mknod()
 * (device nodes), link()/linkat() (hard links — common in dpkg-installed
 * files), and setxattr()/lsetxattr() (POSIX capabilities, also common on
 * Debian packages). Android's seccomp-bpf filter — installed by Zygote for
 * every regular app process — kills the whole process outright (SIGSYS)
 * for these, rather than returning a normal error tar could just warn
 * about and continue past. Each of the three was found and fixed one at a
 * time by tracking which archive entry was being processed when the
 * process died (see git history) — this class now filters all three
 * proactively before busybox ever sees them.
 *
 * A prior hand-rolled Kotlin tar *parser* (writing files itself instead of
 * filtering-then-delegating to a real tar) was tried first and abandoned:
 * it silently dropped some symlinks, leaving a rootfs that "succeeded" but
 * was missing /usr/bin/sh. Wrapping extraction in `proot -0` (to fake
 * mknod() via ptrace) was also tried and abandoned: proot's own path
 * canonicalization fails on Android's deeply nested, tilde-containing
 * install path (/data/app/~~.../lib/arm64/...) with a spurious "No such
 * file or directory", a known proot limitation unrelated to the actual
 * rootfs.
 */
object RootfsImporter {

    fun extract(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        Thread {
            val destDir = File(context.filesDir, "proot-rootfs").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
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
                val process = ProcessBuilder(
                    busybox.absolutePath, "tar", "-xvf", "-", "-C", destDir.absolutePath
                )
                    .redirectErrorStream(true)
                    .start()

                var pumpError: Exception? = null
                var lastEntryName = "(none — stream ended or crashed before any entry)"
                val pumpThread = Thread {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { raw ->
                            GzipCompressorInputStream(raw).use { gz ->
                                TarArchiveInputStream(gz).use { tarIn ->
                                    TarArchiveOutputStream(process.outputStream).apply {
                                        setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                                        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                                    }.use { tarOut ->
                                        var entry = tarIn.nextTarEntry
                                        while (entry != null) {
                                            lastEntryName = entry.name
                                            if (!isDeviceOrFifo(entry)) {
                                                val linkFlag = if (entry.isLink) {
                                                    org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK
                                                } else {
                                                    entry.linkFlag
                                                }
                                                val clean = TarArchiveEntry(entry.name, linkFlag)
                                                clean.size = entry.size
                                                clean.mode = entry.mode
                                                clean.setModTime(entry.modTime)
                                                if (entry.isSymbolicLink || entry.isLink) {
                                                    clean.linkName = entry.linkName
                                                }
                                                tarOut.putArchiveEntry(clean)
                                                if (!entry.isDirectory && !entry.isSymbolicLink && !entry.isLink) {
                                                    tarIn.copyTo(tarOut)
                                                }
                                                tarOut.closeArchiveEntry()
                                            }
                                            entry = tarIn.nextTarEntry
                                        }
                                        tarOut.finish()
                                    }
                                }
                            }
                        } ?: throw IllegalStateException("Could not open picked file")
                    } catch (e: Exception) {
                        pumpError = e
                    }
                }
                pumpThread.start()

                val log = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                pumpThread.join()

                if (exitCode != 0) {
                    onDone(
                        false,
                        "tar extraction failed (exit $exitCode), last entry sent: $lastEntryName\n" +
                            log.takeLast(4000)
                    )
                    return@Thread
                }
                if (pumpError != null) {
                    onDone(
                        false,
                        "Failed reading picked file: ${pumpError?.message}, last entry sent: $lastEntryName" +
                            if (log.isNotBlank()) " — busybox output: ${log.takeLast(2000)}" else ""
                    )
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

    private fun isDeviceOrFifo(entry: TarArchiveEntry): Boolean =
        entry.isFIFO || entry.isCharacterDevice || entry.isBlockDevice

    /**
     * Manually resolves a path within [root], following symlinks (relative or
     * absolute-as-if-rooted-at [root], the same way proot itself would) up to
     * a small depth limit, and reports whether it lands on a real, non-empty
     * regular file.
     */
    private fun resolvesToRealFile(root: File, relativePath: String, depth: Int = 0): Boolean {
        if (depth > 10) return false // guard against symlink loops
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
