package io.boffin.vmforge

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts a PRoot rootfs tarball (.tar.gz, e.g. a Debian/Ubuntu/Alpine
 * arm64 base rootfs from proot-distro or debootstrap) into
 * filesDir/proot-rootfs/, picked via the file importer — no `tar` binary
 * needed since this parses the archive directly in Kotlin.
 */
object RootfsImporter {

    fun extract(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        Thread {
            val destDir = File(context.filesDir, "proot-rootfs").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            var count = 0
            var failedSymlinks = 0
            val failedNames = mutableListOf<String>()
            try {
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    GzipCompressorInputStream(raw).use { gz ->
                        TarArchiveInputStream(gz).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                val outFile = File(destDir, entry.name)
                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else if (entry.isSymbolicLink) {
                                    try {
                                        outFile.parentFile?.mkdirs()
                                        // If a previous entry (e.g. a stray placeholder) already
                                        // occupies this path, remove it first so the symlink can
                                        // actually be created instead of failing with EEXIST.
                                        if (java.nio.file.Files.isSymbolicLink(outFile.toPath()) || outFile.exists()) {
                                            outFile.delete()
                                        }
                                        java.nio.file.Files.createSymbolicLink(
                                            outFile.toPath(), File(entry.linkName).toPath()
                                        )
                                    } catch (e: Exception) {
                                        // No longer silent: track it so a broken rootfs is visible.
                                        failedSymlinks++
                                        failedNames.add("${entry.name} -> ${entry.linkName} (${e.message})")
                                    }
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                                    if ((entry.mode and 0b001000000) != 0) outFile.setExecutable(true)
                                }
                                count++
                                entry = tar.nextEntry
                            }
                        }
                    }
                }

                // Sanity-check that a shell actually resolves inside the extracted
                // rootfs — this is exactly what caught the "execve /usr/bin/sh:
                // No such file" failure. Follows symlinks manually since the
                // filesystem symlink target may not exist as an absolute host path.
                val shellOk = resolvesToRealFile(destDir, "bin/sh") || resolvesToRealFile(destDir, "usr/bin/sh")

                when {
                    failedSymlinks > 0 -> onDone(
                        false,
                        "Extracted $count entries but $failedSymlinks symlink(s) failed " +
                            "(rootfs is likely broken): ${failedNames.take(5).joinToString("; ")}" +
                            if (failedNames.size > 5) " …and ${failedNames.size - 5} more" else ""
                    )
                    !shellOk -> onDone(
                        false,
                        "Extracted $count entries but no working /bin/sh or /usr/bin/sh was found — " +
                            "rootfs is incomplete/incompatible"
                    )
                    else -> onDone(true, "Extracted $count entries")
                }
            } catch (e: Exception) {
                onDone(false, e.message ?: "unknown error")
            }
        }.start()
    }

    /**
     * Manually resolves a path within [root], following symlinks (relative or
     * absolute-as-if-rooted-at [root], the same way proot itself would) up to
     * a small depth limit, and reports whether it lands on a real, non-empty
     * regular file. Used post-extraction to confirm a shell actually exists,
     * since a "successful" tar extraction can still leave dangling symlinks.
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
