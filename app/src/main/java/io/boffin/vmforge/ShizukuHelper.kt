package io.boffin.vmforge

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Centralizes Shizuku setup. Shizuku gives us shell UID (ADB-level
 * privilege) instead of this app's own sandboxed UID — needed because this
 * device denies exec() of any file under the app's private data directory
 * (confirmed via PRootLauncher.testExecFromFilesDir: error=13 Permission
 * denied), the same restriction that originally forced proot/busybox/qemu
 * themselves into nativeLibraryDir/jniLibs. Shell UID doesn't hit that
 * restriction for files under /data/local/tmp, which is where
 * RootfsImporter and PRootLauncher now keep everything.
 *
 * Requires the Shizuku app installed and *running* — since this device
 * isn't rooted, that means starting it via "Start via Wireless debugging"
 * in the Shizuku app (Settings > Developer options > Wireless debugging,
 * paired once, then started from the Shizuku app each boot — it doesn't
 * persist across reboots without root).
 */
object ShizukuHelper {

    const val PERMISSION_REQUEST_CODE = 9100

    /** True if the Shizuku app is installed and running (regardless of permission). */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false // Shizuku app not installed / not started
    }

    fun hasPermission(): Boolean =
        isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /**
     * Requests permission if needed. Returns immediately — the result comes
     * back via a listener registered with
     * [Shizuku.addRequestPermissionResultListener] (done once in
     * MainActivity.onCreate).
     */
    fun requestPermission() {
        if (isAvailable() && !hasPermission()) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Runs [cmd] as shell UID and returns the live remote process (exposes
     * inputStream/outputStream/waitFor()/destroy(), like java.lang.Process).
     * Throws if Shizuku isn't available/permitted or the call itself fails
     * — callers should catch and report, since this is often the very
     * first thing that can fail in a chain of otherwise-successful setup.
     */
    fun newProcess(cmd: List<String>, env: Array<String>? = null, dir: String? = null): rikka.shizuku.ShizukuRemoteProcess {
        check(hasPermission()) { "Shizuku not available or permission not granted" }
        return Shizuku.newProcess(cmd.toTypedArray(), env, dir)
    }

    /** Convenience for one-shot commands: runs, waits, returns (exitCode, combined output). */
    fun runShell(cmd: List<String>, env: Array<String>? = null, dir: String? = null): Pair<Int, String> {
        val process = newProcess(cmd, env, dir)
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return exitCode to output
    }
}
