package io.boffin.vmforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * QEMU প্রসেসকে ফরগ্রাউন্ড সার্ভিসে চালায় যাতে Android মেমরি প্রেশারে
 * VM-কে হুট করে kill না করে দেয়। প্রসেস ম্যানেজমেন্ট (start/stop/log)
 * এখানে যোগ হবে — এই ফাইলটা এখনো স্কেলিটন।
 */
class VmService : Service() {

    private var qemuProcess: Process? = null
    private val channelId = "vm_forge_running"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "VM চলছে", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("vm-forge")
            .setContentText("VM চলছে ব্যাকগ্রাউন্ডে")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)

        val cmd = QemuLauncher(this).buildCommand()
        qemuProcess = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        // TODO: qemuProcess.inputStream লগ হিসেবে UI-তে স্ট্রিম করা
        return START_STICKY
    }

    override fun onDestroy() {
        qemuProcess?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
