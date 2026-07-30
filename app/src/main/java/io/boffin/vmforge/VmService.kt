package io.boffin.vmforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import java.io.File

/**
 * Path B: runs the bundled, standalone QEMU (via NativeVmLauncher) as a
 * foreground service so Android doesn't kill the VM process in the
 * background. This is the fully self-contained alternative to v0.1's
 * Termux RUN_COMMAND approach — no Termux dependency at all.
 */
class VmService : Service() {

    private var qemuProcess: Process? = null
    private var logThread: Thread? = null
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

        try {
            qemuProcess = NativeVmLauncher(this).start()
            startLogging(qemuProcess!!)
        } catch (e: Exception) {
            Toast.makeText(this, "VM failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }

        return START_STICKY
    }

    /** Continuously copies the QEMU process's combined stdout/stderr into vm/vm.log. */
    private fun startLogging(process: Process) {
        val logFile = File(File(filesDir, "vm"), "vm.log")
        logThread = Thread {
            try {
                logFile.outputStream().use { out ->
                    process.inputStream.copyTo(out)
                }
            } catch (_: Exception) {
                // process ended / stream closed — nothing to do
            }
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        qemuProcess?.destroy()
        logThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
