package io.boffin.vmforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Path B (not yet implemented): runs the QEMU process as a foreground
 * service so Android doesn't kill the VM under memory pressure.
 * Process management (start/stop/log) will be added here — this file
 * is still a skeleton, unused while v0.1 (Termux RUN_COMMAND) is active.
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
            .setContentText("VM running in background")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)

        val cmd = QemuLauncher(this).buildCommand()
        qemuProcess = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        // TODO: stream qemuProcess.inputStream to the UI as a log
        return START_STICKY
    }

    override fun onDestroy() {
        qemuProcess?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
