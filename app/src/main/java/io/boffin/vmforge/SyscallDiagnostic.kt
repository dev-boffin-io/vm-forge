package io.boffin.vmforge

import android.content.Context
import android.system.Os
import java.io.File

/**
 * Isolates exactly which syscall on a symlink is seccomp-killed by Android.
 *
 * Extracting "bin -> usr/bin" (a plain top-level symlink) crashes busybox
 * with SIGSYS no matter what we've tried: forcing mode to 0777, skipping
 * mtime, adding --no-same-owner. Since symlink *creation* itself is proven
 * fine (hundreds succeeded earlier in this debugging history), something
 * busybox does *after* creating a symlink must be the real cause — but we
 * can't get the kernel's own seccomp audit log without root to know
 * exactly which syscall, so instead we reproduce each candidate directly
 * via android.system.Os, in our own process (same seccomp filter busybox
 * runs under, since it's a child of this process).
 *
 * A real SIGSYS crash is fatal and uncatchable in Java — it kills the
 * whole app immediately, not just this thread. So before each risky call
 * we write a breadcrumb file recording which step we're about to attempt;
 * if the app dies, [readAndClearCrashedStep] on the next launch tells you
 * exactly which one it was.
 *
 * Deliberately limited to Os methods with simple, stable signatures (no
 * *at()-family calls, whose exact FileDescriptor/flags signatures aren't
 * worth risking a build break over) — if none of these crash, that's
 * still a useful result: it means the cause is something busybox itself
 * does that we can't easily reproduce this way, not a fundamental
 * "any symlink attribute call crashes" issue.
 */
object SyscallDiagnostic {

    private const val BREADCRUMB = "syscall_diagnostic_step"

    /** Call once at app startup, before running the diagnostic again. */
    fun readAndClearCrashedStep(context: Context): String? {
        val f = File(context.filesDir, BREADCRUMB)
        if (!f.exists()) return null
        val step = f.readText()
        f.delete()
        return if (step.startsWith("DONE")) null else step
    }

    private fun mark(context: Context, step: String) {
        File(context.filesDir, BREADCRUMB).writeText(step)
    }

    /**
     * Runs each candidate syscall in order against a throwaway symlink. If
     * this returns normally, none of them crash — the real cause is
     * elsewhere. If the app force-closes instead, check
     * [readAndClearCrashedStep] on next launch.
     */
    fun runDiagnostic(context: Context): String {
        val dir = File(context.filesDir, "syscall-diag").apply { deleteRecursively(); mkdirs() }
        val linkPath = File(dir, "bin").absolutePath
        val results = StringBuilder()

        fun step(name: String, action: () -> Unit) {
            mark(context, name)
            try {
                action()
                results.appendLine("$name: OK")
            } catch (e: Exception) {
                results.appendLine("$name: FAILED (caught, not seccomp) — ${e.message}")
            }
        }

        step("1_symlink") { Os.symlink("usr/bin", linkPath) }
        step("2_lchown") { Os.lchown(linkPath, Os.getuid(), Os.getgid()) }
        step("3_readlink") { Os.readlink(linkPath) }
        step("4_lstat") { Os.lstat(linkPath) }

        mark(context, "DONE")
        return results.toString()
    }
}
