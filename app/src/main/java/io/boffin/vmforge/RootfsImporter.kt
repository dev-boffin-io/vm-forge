package io.boffin.vmforge

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Extracts a PRoot rootfs tarball (.tar.gz, e.g. a Debian/Ubuntu/Alpine
 * arm64 base rootfs from proot-distro or debootstrap) into
 * filesDir/proot-rootfs/.
 *
 * This shells out to a bundled `busybox tar` (jniLibs/arm64-v8a/
 * libbusybox.so, dynamically linked — see LD_LIBRARY_PATH below)
 * instead of parsing the tar format by hand. A real tar
 * implementation handles symlinks (including usrmerge layouts where /bin
 * is a symlink to usr/bin, created before usr/bin itself exists in
 * archive order) and permission bits correctly — the previous hand-rolled
 * Kotlin parser silently dropped some symlinks, producing a rootfs that
 * "extracted successfully" but was missing /usr/bin/sh.
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
                // "busybox tar -xzf - -C destDir" reads the archive from stdin,
                // which we feed from the picked content:// Uri, and extracts
                // with full symlink/permission fidelity into destDir.
                val process = ProcessBuilder(
                    busybox.absolutePath, "tar", "-xzf", "-", "-C", destDir.absolutePath
                )
                    .redirectErrorStream(true)
                    .apply {
                        // libbusybox.so is dynamically linked (NDK-built, not static)
                        // and depends on libandroid-selinux.so bundled alongside it —
                        // without this it fails to even start.
                        environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                    }
                    .start()

                var pumpError: Exception? = null
                val pumpThread = Thread {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            process.outputStream.use { out -> input.copyTo(out) }
                        } ?: throw IllegalStateException("Could not open picked file")
                    } catch (e: Exception) {
                        pumpError = e
                    }
                }
                pumpThread.start()

                val log = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                pumpThread.join()

                if (pumpError != null) {
                    onDone(false, "Failed reading picked file: ${pumpError?.message}")
                    return@Thread
                }
                if (exitCode != 0) {
                    onDone(false, "tar extraction failed (exit $exitCode): ${log.takeLast(500)}")
                    return@Thread
                }

                // Sanity-check that a shell actually resolves inside the extracted
                // rootfs. Belt-and-braces on top of a real tar: catches a wrong or
                // incompatible archive (e.g. one with no /bin/sh at all) rather than
                // only surfacing as proot's native "execve(...) No such file" error.
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
