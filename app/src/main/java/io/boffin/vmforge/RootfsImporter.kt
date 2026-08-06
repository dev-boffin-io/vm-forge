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
                                    // Best-effort — java.nio symlink creation; skip on failure
                                    try {
                                        outFile.parentFile?.mkdirs()
                                        java.nio.file.Files.createSymbolicLink(
                                            outFile.toPath(), File(entry.linkName).toPath()
                                        )
                                    } catch (_: Exception) { /* not fatal, some entries may fail */ }
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
                onDone(true, "Extracted $count entries")
            } catch (e: Exception) {
                onDone(false, e.message ?: "unknown error")
            }
        }.start()
    }
}
