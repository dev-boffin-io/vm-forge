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

    // proot's own path canonicalization resolves whatever we pass as -r to
    // a /data/data/... path (confirmed via its "binding = ..." verbose log),
    // even when context.filesDir reports /data/user/0/... instead. These
    // are normally the same directory via a standard Android alias, but to
    // eliminate any chance of that alias being inconsistent on this device,
    // use the same explicit /data/data path ourselves everywhere.
    private val dataDir: File
        get() = File("/data/data/${context.packageName}/files")

    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val rootfsDir: File
        get() = File(dataDir, "proot-rootfs")

    private val tmpDir: File
        get() = File(dataDir, "proot-tmp").apply { mkdirs() }

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
        // dash (what sh resolves to) is a dynamically-linked ELF — the
        // kernel's own ELF loader needs this interpreter to exist at
        // exactly this path during execve(), or it fails with ENOENT
        // (which proot then reports as if the *original* program is
        // missing, not the interpreter). Never checked before.
        report.appendLine("Resolving /lib/ld-linux-aarch64.so.1 (dash's ELF interpreter):")
        walk("lib/ld-linux-aarch64.so.1")

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
            "/bin/sh"
        )
    }

    /**
     * Clean, proot-independent test for whether this device blocks
     * executing files from the app's own private data directory (the same
     * SELinux restriction that forced proot/busybox/qemu themselves into
     * nativeLibraryDir/jniLibs, per this project's own git history) —
     * copies a known-good nativeLibraryDir binary into filesDir and tries
     * to exec it directly via plain ProcessBuilder, no ptrace/proot
     * involved, so any permission denial shows up as a normal catchable
     * exception instead of proot's ambiguous "No such file" wrapping.
     */
    fun testExecFromFilesDir(): String {
        val source = File(nativeLibDir, "libproot.so")
        val dest = File(dataDir, "exec-test-copy")
        return try {
            source.copyTo(dest, overwrite = true)
            dest.setExecutable(true)
            val process = ProcessBuilder(dest.absolutePath, "--help")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            "Copied ${source.name} to ${dest.absolutePath} and executed it directly (no proot).\n" +
                "exit=$exitCode\n${output.take(500)}"
        } catch (e: Exception) {
            "Copied to ${dest.absolutePath} but exec FAILED: ${e.javaClass.simpleName}: ${e.message}\n" +
                "This would confirm files under filesDir/dataDir can't be executed on this device " +
                "(the same restriction that forced proot/busybox/qemu into nativeLibraryDir)."
        } finally {
            dest.delete()
        }
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
                // Verbose trace showed proot enabling its seccomp-based
                // ptrace acceleration right before execve() translation
                // stopped taking effect — a known class of device/kernel-
                // specific compatibility issue with that optimization.
                // Force classic ptrace-only mode instead.
                environment()["PROOT_NO_SECCOMP"] = "1"
            }
            .start()
    }
}
