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

    fun buildCommand(): List<String> {
        val prootBinary = File(nativeLibDir, "libproot.so")
        return listOf(
            prootBinary.absolutePath,
            "--link2symlink",
            "-0", // appear as root inside the rootfs (fakeroot-style, no real privilege)
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/bin/sh"
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
