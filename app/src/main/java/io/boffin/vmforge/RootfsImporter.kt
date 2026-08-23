package io.boffin.vmforge

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Extracts a PRoot rootfs tarball (.tar.gz, e.g. a Debian/Ubuntu/Alpine
 * arm64 base rootfs from proot-distro or debootstrap) into
 * /data/local/tmp/vmforge-rootfs, using Shizuku (shell UID).
 *
 * FULL HISTORY, for anyone re-reading this — the long road here:
 * 1. Extracting a full Linux rootfs needs mknod(), link()/linkat(), and
 *    setxattr()/lsetxattr(). Android's seccomp-bpf filter — installed by
 *    Zygote for every regular app process — kills the whole process
 *    outright (SIGSYS) for these. A Kotlin-side filter (drop device/FIFO
 *    entries, convert hardlinks to symlinks, strip PAX xattr records)
 *    fixed extraction itself, piping into a bundled busybox tar.
 * 2. But *running* anything from the extracted rootfs still failed —
 *    proot reported "execve(...): No such file or directory" for files
 *    proven to exist (verified byte-for-byte, including the ELF
 *    interpreter). Eventually confirmed via a clean, proot-independent
 *    test (PRootLauncher.testExecFromFilesDir): this device returns
 *    error=13 (EACCES) for *any* exec attempt from the app's own private
 *    data directory (filesDir) — the exact same restriction that forced
 *    proot/busybox/qemu themselves into nativeLibraryDir/jniLibs at
 *    build time. A runtime-imported rootfs can't be pre-bundled that way.
 * 3. The fix: Shizuku. Shell UID (ADB-level privilege, no root needed)
 *    isn't subject to that restriction for files under /data/local/tmp,
 *    a standard shell-owned, exec-capable staging location. So both
 *    extraction *and* running proot itself (see PRootLauncher) now go
 *    through Shizuku, entirely at /data/local/tmp — no more Kotlin-side
 *    filtering needed either, since shell UID doesn't hit the seccomp
 *    restrictions from step 1 — a real, unmodified `tar` just works.
 */
object RootfsImporter {

    private const val STAGING_TAR = "/data/local/tmp/vmforge-import.tar.gz"
    const val ROOTFS_DIR = "/data/local/tmp/vmforge-rootfs"

    fun extract(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        Thread {
            if (!ShizukuHelper.hasPermission()) {
                onDone(
                    false,
                    "Shizuku permission required — grant it in the Shizuku app, or start Shizuku " +
                        "via Wireless debugging if it isn't running (Settings > Developer options > " +
                        "Wireless debugging, then open the Shizuku app)."
                )
                return@Thread
            }

            val stagingFile = File(STAGING_TAR)
            try {
                // App's own UID copies the picked archive to a shell-writable
                // staging location (/data/local/tmp is world-writable).
                context.contentResolver.openInputStream(uri)?.use { input ->
                    stagingFile.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    onDone(false, "Could not open picked file")
                    return@Thread
                }

                // Shell UID does the real, unmodified extraction — no
                // seccomp restrictions on mknod/link/setxattr here, and no
                // exec-from-app-data restriction either since the result
                // stays at /data/local/tmp.
                val (exitCode, output) = ShizukuHelper.runShell(
                    listOf(
                        "sh", "-c",
                        "rm -rf '$ROOTFS_DIR' && mkdir -p '$ROOTFS_DIR' && " +
                            "tar -xzf '${stagingFile.absolutePath}' -C '$ROOTFS_DIR'"
                    )
                )

                if (exitCode != 0) {
                    onDone(false, "tar extraction failed (exit $exitCode): ${output.takeLast(2000)}")
                    return@Thread
                }

                val shellOk = resolvesToRealFile("bin/sh") || resolvesToRealFile("usr/bin/sh")
                if (!shellOk) {
                    onDone(
                        false,
                        "Extraction finished but no /bin/sh or /usr/bin/sh was found — " +
                            "wrong or incompatible rootfs archive"
                    )
                    return@Thread
                }

                onDone(true, "Rootfs extracted successfully (via Shizuku, at $ROOTFS_DIR)")
            } catch (e: Exception) {
                onDone(false, e.message ?: "unknown error")
            } finally {
                stagingFile.delete()
            }
        }.start()
    }

    /**
     * Resolves [relativePath] within the extracted rootfs, following
     * symlinks up to a depth limit, and reports whether it lands on a
     * real, non-empty regular file. Reads directly via java.io.File since
     * /data/local/tmp is world-readable — no Shizuku call needed just to
     * check this.
     */
    private fun resolvesToRealFile(relativePath: String, depth: Int = 0): Boolean {
        if (depth > 10) return false
        val root = File(ROOTFS_DIR)
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
        return resolvesToRealFile(nextRelative, depth + 1)
    }
}
