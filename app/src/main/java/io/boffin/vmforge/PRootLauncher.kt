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
 * Both the rootfs (see RootfsImporter) and proot itself now run via
 * Shizuku (shell UID), at /data/local/tmp — this device denies exec() of
 * any file under the app's own private data directory
 * (testExecFromFilesDir confirms: error=13/EACCES), the same restriction
 * that originally forced proot/busybox/qemu into nativeLibraryDir/jniLibs
 * at build time. A runtime-imported rootfs can't be pre-bundled that way,
 * so it has to live somewhere shell UID can both read *and execute* from
 * — /data/local/tmp is the standard such location. See RootfsImporter's
 * class doc for the fuller history of how this was diagnosed.
 */
class PRootLauncher(private val context: Context) {

    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val rootfsDir: File
        get() = File(RootfsImporter.ROOTFS_DIR)

    private val tmpDir = "/data/local/tmp/vmforge-proot-tmp"

    fun rootfsExists(): Boolean = rootfsDir.exists() && (rootfsDir.listFiles()?.isNotEmpty() == true)

    /**
     * Beyond "is the folder non-empty" (rootfsExists), confirms a shell
     * binary is actually reachable — catches a half-extracted-rootfs case.
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
     * Detailed version of [rootfsHasShell] — walks the same symlink chain
     * and reports exactly where it breaks, plus what's at the top level of
     * the rootfs, instead of just true/false.
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
     * executing files from the app's own private data directory. Copies a
     * known-good nativeLibraryDir binary into the app's filesDir and tries
     * to exec it directly via plain ProcessBuilder, no ptrace/proot
     * involved, so a permission denial shows up as a normal catchable
     * exception instead of proot's ambiguous "No such file" wrapping.
     * (This is what originally confirmed the restriction — kept as a
     * standing diagnostic in case it's ever useful on a different device.)
     */
    fun testExecFromFilesDir(): String {
        val source = File(nativeLibDir, "libproot.so")
        val dest = File(context.filesDir, "exec-test-copy")
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
            "Copied to ${dest.absolutePath} but exec FAILED: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            dest.delete()
        }
    }

    /**
     * Starts proot via Shizuku (shell UID) — running it as this app's own
     * process (like before) hits the same exec-from-app-data restriction
     * RootfsImporter's class doc describes, even though libproot.so itself
     * lives in nativeLibraryDir (that part was always fine); the problem
     * is everything proot then tries to *execute inside the rootfs*.
     * Running proot itself under shell UID sidesteps this for the whole
     * session at once, rather than needing a workaround per rootfs binary.
     */
    fun start(): rikka.shizuku.ShizukuRemoteProcess {
        File(tmpDir).apply { if (!exists()) mkdirs() }
        // Note: this mkdir happens via our own (app) UID — /data/local/tmp
        // is world-writable so this works fine even though proot itself
        // will run as shell UID afterward.
        val cmd = buildCommand()
        val env = arrayOf(
            "LD_LIBRARY_PATH=${nativeLibDir.absolutePath}",
            "PROOT_TMP_DIR=$tmpDir",
            "TMPDIR=$tmpDir",
            // Verbose trace showed proot's seccomp-based ptrace acceleration
            // enabling right before execve() translation stopped taking
            // effect on this device — force classic ptrace-only mode.
            "PROOT_NO_SECCOMP=1"
        )
        return ShizukuHelper.newProcess(cmd, env, null)
    }
}
