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
import java.io.File

/**
 * Runs the bundled, standalone QEMU (via NativeVmLauncher) as a foreground
 * service so Android doesn't kill the VM process in the background.
 * Also bindable — TerminalActivity binds to this to read/write the running
 * QEMU process's stdio directly for an interactive console.
 */
class VmService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VmService = this@VmService
    }
    private val binder = LocalBinder()

    var qemuProcess: Process? = null
        private set

    private val channelId = "vm_forge_running"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "VM running", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("vm-forge")
            .setContentText("VM running in background")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)

        if (qemuProcess == null || qemuProcess?.isAlive != true) {
            try {
                qemuProcess = NativeVmLauncher(this).start()
            } catch (e: Exception) {
                Toast.makeText(this, "VM failed to start: ${e.message}", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }

        return START_STICKY
    }

    fun isRunning(): Boolean = qemuProcess?.isAlive == true

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        qemuProcess?.destroy()
        qemuProcess = null
        super.onDestroy()
    }
}
