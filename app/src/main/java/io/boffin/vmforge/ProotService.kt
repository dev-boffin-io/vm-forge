package io.boffin.vmforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.widget.Toast

/**
 * Runs PRoot as a foreground service — separate from VmService since a
 * PRoot container and a QEMU VM are independent, unrelated modes (not
 * meant to run at the same time in this app, though nothing technically
 * stops that). Bindable, so TerminalActivity can attach to its stdio the
 * same way it does for VmService.
 *
 * prootSession wraps a Shizuku shell-UID process (see PRootLauncher's
 * class doc for why), not a plain java.lang.Process.
 */
class ProotService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ProotService = this@ProotService
    }
    private val binder = LocalBinder()

    var prootSession: ShizukuProotSession? = null
        private set

    private val channelId = "vm_forge_proot_running"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "PRoot container running", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("vm-forge")
            .setContentText("PRoot container running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(2, notification)

        if (prootSession == null || prootSession?.isAlive != true) {
            if (!ShizukuHelper.hasPermission()) {
                Toast.makeText(
                    this,
                    "Shizuku permission required for PRoot on this device — grant it in the Shizuku app",
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return START_NOT_STICKY
            }
            val launcher = PRootLauncher(this)
            if (!launcher.rootfsExists()) {
                Toast.makeText(this, "No rootfs imported yet — use \"Import PRoot rootfs\" first", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            if (!launcher.rootfsHasShell()) {
                Toast.makeText(this, "Rootfs looks broken/incomplete (no /bin/sh) — re-import it", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            // bindShellService() blocks waiting for the Shizuku connection
            // — must not run on the main thread.
            Thread {
                try {
                    prootSession = launcher.start(this)
                } catch (e: Exception) {
                    Toast.makeText(this, "PRoot failed to start: ${e.message}", Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }.start()
        }

        return START_STICKY
    }

    fun isRunning(): Boolean = prootSession?.isAlive == true

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        prootSession?.destroy()
        prootSession = null
        super.onDestroy()
    }
}
