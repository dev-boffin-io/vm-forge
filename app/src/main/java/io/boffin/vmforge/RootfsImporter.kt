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
 * device/FIFO entries (dev/null, dev/console, ...) and converting hard
 * links to symlinks — into a plain, uncompressed tar stream fed to a
 * bundled `busybox tar` (jniLibs/arm64-v8a/libbusybox.so) over stdin.
 * Everything else (regular files, directories, symlinks, permissions)
 * is handled by the real `tar` unmodified, so usrmerge-style layouts
 * and permission bits come out correct — only the specific entry types
 * that would trigger a privileged syscall are touched.
 *
 * Why filter instead of extracting directly:
 * - A prior hand-rolled Kotlin tar *parser* (writing files itself)
 *   silently dropped some symlinks, leaving a rootfs that "succeeded"
 *   but was missing /usr/bin/sh.
 * - Device nodes need mknod(), and hard links need link()/linkat() —
 *   both of which Android's seccomp filter kills the whole process for
 *   (SIGSYS, exit 159) rather than returning an error — busybox's
 *   --exclude isn't even compiled into this build to dodge the device
 *   nodes, and PRootLauncher bind-mounts the host's real /dev over the
 *   rootfs at launch time anyway (-b /dev), so archived device nodes
 *   are never actually needed.
 * - Wrapping the extraction in `proot -0` to fake mknod() was tried,
 *   but proot's own path canonicalization fails on Android's deeply
 *   nested, tilde-containing install path
 *   (/data/app/~~.../lib/arm64/...) with a spurious "No such file or
 *   directory" — a known proot limitation, not a real missing file. So
 *   this avoids proot entirely for extraction.
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
                // Plain busybox tar reading an uncompressed, pre-filtered tar
                // stream from stdin ("-xf -", no "z" — we already decompress
                // and re-encode as plain tar below).
                val process = ProcessBuilder(
                    busybox.absolutePath, "tar", "-xf", "-", "-C", destDir.absolutePath
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
                                            when {
                                                isDeviceOrFifo(entry) -> {
                                                    // Skip — needs mknod(), seccomp-killed (see class doc).
                                                }
                                                entry.isLink -> {
                                                    // Hard links need link()/linkat(), which Android's
                                                    // seccomp filter appears to kill just like mknod().
                                                    // Recreate as a symlink to the same target instead —
                                                    // not byte-identical semantics, but functionally
                                                    // equivalent for a rootfs and avoids the crash.
                                                    val symlink = TarArchiveEntry(
                                                        entry.name,
                                                        org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK
                                                    )
                                                    symlink.linkName = entry.linkName
                                                    tarOut.putArchiveEntry(symlink)
                                                    tarOut.closeArchiveEntry()
                                                }
                                                else -> {
                                                    tarOut.putArchiveEntry(entry)
                                                    // No-op for directories/symlinks (zero-size entries)
                                                    // — only regular files actually have data to copy.
                                                    tarIn.copyTo(tarOut)
                                                    tarOut.closeArchiveEntry()
                                                }
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
                    // The process's own output is the real diagnostic here — a
                    // pump error just means it died before we finished writing,
                    // which is a symptom, not the cause.
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

                // Sanity-check that a shell actually resolves inside the extracted
                // rootfs — catches a wrong or incompatible archive (e.g. one with
                // no /bin/sh at all) rather than only surfacing later as proot's
                // native "execve(...) No such file" error.
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
            link // absolute inside the rootfs, same as proot's guest-root translation
        } else {
            File(target.parentFile, link).path.removePrefix(root.path).removePrefix("/")
        }
        return resolvesToRealFile(root, nextRelative, depth + 1)
    }
}
