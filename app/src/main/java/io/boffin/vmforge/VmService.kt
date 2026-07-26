package io.boffin.vmforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Path B: runs the bundled, standalone QEMU (via NativeVmLauncher) as a
 * foreground service so Android doesn't kill the VM process in the
 * background. This is the fully self-contained alternative to v0.1's
 * Termux RUN_COMMAND approach — no Termux dependency at all.
 */
class VmService : Service() {

    private var qemuProcess: Process? = null
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
            .setContentText("VM running in background (standalone)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)

        qemuProcess = NativeVmLauncher(this).start()

        // TODO: stream qemuProcess.inputStream to the UI as a log
        return START_STICKY
    }

    override fun onDestroy() {
        qemuProcess?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
