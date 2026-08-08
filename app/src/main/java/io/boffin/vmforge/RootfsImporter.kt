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
 * libbusybox.so) wrapped in `proot -0` (jniLibs/arm64-v8a/
 * libproot.so) instead of parsing the tar format by hand. A real tar
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
            val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

            if (!busybox.exists() || !busybox.canExecute()) {
                onDone(
                    false,
                    "Bundled busybox not found at ${busybox.absolutePath} — " +
                        "add libbusybox.so to app/src/main/jniLibs/arm64-v8a/"
                )
                return@Thread
            }
            if (!proot.exists() || !proot.canExecute()) {
                onDone(false, "Bundled proot not found at ${proot.absolutePath}")
                return@Thread
            }

            try {
                // Run tar under proot's fakeroot mode (-0): device nodes
                // (dev/null, dev/console, ...) need mknod(), and Android's
                // seccomp filter kills the process outright for that syscall
                // (SIGSYS, exit 159) rather than returning an error — busybox's
                // own --exclude flag isn't even compiled into this build to
                // dodge it. Under proot, mknod is intercepted via ptrace and
                // faked entirely in userspace before it ever reaches the
                // kernel, so the real (blocked) syscall never happens — the
                // same trick proot-distro/termux use to bootstrap rootfs
                // tarballs as a non-root app.
                val tmpDir = File(context.filesDir, "proot-tmp").apply { mkdirs() }
                val process = ProcessBuilder(
                    proot.absolutePath, "-0",
                    busybox.absolutePath, "tar", "-xzf", "-", "-C", destDir.absolutePath
                )
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                        environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                        environment()["TMPDIR"] = tmpDir.absolutePath
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

                if (exitCode != 0) {
                    // The process's own output is the real diagnostic here — a
                    // "Stream closed" pump error just means it died before we
                    // finished writing, which is a symptom, not the cause.
                    onDone(false, "tar extraction failed (exit $exitCode): ${log.takeLast(4000)}")
                    return@Thread
                }
                if (pumpError != null) {
                    onDone(false, "Failed reading picked file: ${pumpError?.message}" +
                        if (log.isNotBlank()) " — busybox output: ${log.takeLast(2000)}" else "")
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
