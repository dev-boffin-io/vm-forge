package io.boffin.vmforge

import android.content.Context
import java.io.File

/**
 * PRoot Container mode: an alternative to the QEMU VM path. Instead of
 * emulating a whole machine, this uses PRoot to chroot (without root)
 * into a plain Linux rootfs directory that shares the host's kernel —
 * much lighter/faster than QEMU, but same-architecture only (ARM64) and
 * less isolated (host /proc, /dev etc. are bind-mounted in).
 *
 * The rootfs is a plain extracted tarball (e.g. a Debian arm64 base
 * rootfs from proot-distro or debootstrap) under filesDir/proot-rootfs/,
 * NOT a bootable disk image — there's no kernel/init/systemd involved,
 * PRoot just directly execs a shell inside the rootfs directory.
 */
class PRootLauncher(private val context: Context) {

    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val rootfsDir: File
        get() = File(context.filesDir, "proot-rootfs")

    private val tmpDir: File
        get() = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    fun rootfsExists(): Boolean = rootfsDir.exists() && (rootfsDir.listFiles()?.isNotEmpty() == true)

    /**
     * Beyond "is the folder non-empty" (rootfsExists), confirms a shell
     * binary is actually reachable — catches the half-extracted-rootfs case
     * that used to surface only as proot's native "execve(...) No such file"
     * error deep in the log.
     */
    fun rootfsHasShell(): Boolean {
        fun resolves(path: String, depth: Int = 0): Boolean {
            if (depth > 10) return false
            val f = File(rootfsDir, path.removePrefix("/"))
            if (!java.nio.file.Files.isSymbolicLink(f.toPath())) return f.isFile && f.length() > 0
            val link = java.nio.file.Files.readSymbolicLink(f.toPath()).toString()
            val next = if (link.startsWith("/")) link
                       else File(f.parentFile, link).path.removePrefix(rootfsDir.path).removePrefix("/")
            return resolves(next, depth + 1)
        }
        return resolves("bin/sh") || resolves("usr/bin/sh")
    }

    /**
     * Detailed version of [rootfsHasShell] for when it (or a live proot
     * session) says the shell is missing but extraction reported success —
     * walks the same symlink chain and reports exactly where it breaks,
     * plus what's actually at the top level of the rootfs, instead of
     * just true/false.
     */
    fun verifyRootfs(): String {
        val report = StringBuilder()
        report.appendLine("rootfsDir = ${rootfsDir.absolutePath}")
        report.appendLine("exists = ${rootfsDir.exists()}, top-level entries:")
        rootfsDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val kind = when {
                java.nio.file.Files.isSymbolicLink(f.toPath()) ->
                    "symlink -> ${java.nio.file.Files.readSymbolicLink(f.toPath())}"
                f.isDirectory -> "directory"
                else -> "file (${f.length()} bytes)"
            }
            report.appendLine("  ${f.name}: $kind")
        } ?: report.appendLine("  (couldn't list — rootfsDir missing or not a directory)")

        fun walk(path: String, depth: Int = 0) {
            val indent = "  ".repeat(depth + 1)
            if (depth > 10) {
                report.appendLine("$indent(too many symlink hops, stopping)")
                return
            }
            val f = File(rootfsDir, path.removePrefix("/"))
            when {
                java.nio.file.Files.isSymbolicLink(f.toPath()) -> {
                    val link = java.nio.file.Files.readSymbolicLink(f.toPath()).toString()
                    report.appendLine("$indent$path -> symlink -> $link")
                    val next = if (link.startsWith("/")) link
                               else File(f.parentFile, link).path.removePrefix(rootfsDir.path).removePrefix("/")
                    walk(next, depth + 1)
                }
                f.isFile -> report.appendLine("$indent$path -> real file, ${f.length()} bytes, executable=${f.canExecute()}")
                f.isDirectory -> report.appendLine("$indent$path -> directory (not a shell)")
                else -> report.appendLine("$indent$path -> MISSING (nothing here)")
            }
        }
        report.appendLine("Resolving /bin/sh:")
        walk("bin/sh")
        report.appendLine("Resolving /usr/bin/sh:")
        walk("usr/bin/sh")

        return report.toString()
    }

    fun buildCommand(): List<String> {
        val prootBinary = File(nativeLibDir, "libproot.so")
        return listOf(
            prootBinary.absolutePath,
            "-0", // appear as root inside the rootfs (fakeroot-style, no real privilege)
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/bin/dash" // temporarily bypassing /bin/sh's sh->dash symlink to isolate the issue — see PRootLauncher history
        )
    }

    fun start(): Process {
        val cmd = buildCommand()
        return ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
                // PRoot was compiled with Termux's own tmp path baked in as a
                // default (/data/data/com.termux/files/usr/tmp/), which our app
                // can't access (different UID, private dir) — point it at our
                // own writable directory instead.
                environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                environment()["TMPDIR"] = tmpDir.absolutePath
            }
            .start()
    }
}
