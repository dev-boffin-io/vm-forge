package io.boffin.vmforge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * Shizuku.newProcess() looked like the obvious API for this but turned out
 * to be private/inaccessible in dev.rikka.shizuku:api:13.1.5 (compile
 * error: "Cannot access 'newProcess': it is private in 'Shizuku'"). The
 * actually-supported route is binding our own [ShellUserService], which
 * Shizuku spawns as a separate process under shell UID — see
 * IShellService.aidl.
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
     * Binds [ShellUserService] and blocks (up to [timeoutSeconds]) until
     * connected, returning the live binder interface. Must be called from
     * a background thread, not the main thread. The caller owns the
     * returned service's lifetime — call [unbind] with the same args when
     * done, or leave it running for a whole PRoot session.
     */
    fun bindShellService(context: Context, timeoutSeconds: Long = 15): IShellService {
        check(hasPermission()) { "Shizuku not available or permission not granted" }

        val args = Shizuku.UserServiceArgs(ComponentName(context, ShellUserService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(1)

        val latch = CountDownLatch(1)
        var result: IShellService? = null
        var error: Exception? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    if (binder == null || !binder.pingBinder()) {
                        error = IllegalStateException("Shizuku user service binder was null/dead")
                    } else {
                        result = IShellService.Stub.asInterface(binder)
                    }
                } catch (e: Exception) {
                    error = e
                } finally {
                    latch.countDown()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { /* no-op */ }
        }

        connections[args] = connection
        Shizuku.bindUserService(args, connection)

        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out waiting for Shizuku user service to bind")
        }
        error?.let { throw it }
        return result ?: throw IllegalStateException("Shizuku user service bind returned no service")
    }

    // Keeps the ServiceConnection alive for unbindUserService, which needs
    // the exact same args+connection instance used to bind.
    private val connections = mutableMapOf<Shizuku.UserServiceArgs, ServiceConnection>()

    fun unbindShellService(context: Context) {
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShellUserService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
        connections[args]?.let {
            try { Shizuku.unbindUserService(args, it, true) } catch (_: Exception) { /* already gone */ }
            connections.remove(args)
        }
    }

    /** One-shot convenience: bind, run a command, unbind. For extraction. */
    fun runShellOnce(context: Context, script: String): Pair<Int, String> {
        val service = bindShellService(context)
        try {
            val raw = service.runShell(script)
            val newlineIndex = raw.indexOf('\n')
            return if (newlineIndex < 0) -1 to raw
            else (raw.substring(0, newlineIndex).toIntOrNull() ?: -1) to raw.substring(newlineIndex + 1)
        } finally {
            unbindShellService(context)
        }
    }
}
