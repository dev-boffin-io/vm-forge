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
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the bundled, standalone QEMU (via NativeVmLauncher) as a foreground
 * service so Android doesn't kill the VM process in the background.
 *
 * Supports ARM64 and x86_64 VMs **simultaneously**: each architecture gets
 * its own QEMU process, its own `vm/<arch>` directory (disk image, seed ISO,
 * firmware, last-command log), and its own serial console. Starting, reading
 * output, or stopping one architecture never touches the other.
 */
class VmService : Service() {

    companion object {
        const val EXTRA_SSH_PORT = "ssh_port"
        const val EXTRA_VNC_PORT = "vnc_port"
        const val EXTRA_SPICE_PORT = "spice_port"
        const val EXTRA_HEADLESS = "headless"
        const val EXTRA_ARCH = "arch" // "arm64" or "x86_64"
        const val ARCH_ARM64 = "arm64"
        const val ARCH_X86_64 = "x86_64"

        fun archKey(arch: KvmDetector.GuestArch): String =
            if (arch == KvmDetector.GuestArch.X86_64) ARCH_X86_64 else ARCH_ARM64
    }

    inner class LocalBinder : Binder() {
        fun getService(): VmService = this@VmService
    }
    private val binder = LocalBinder()

    private val processes = ConcurrentHashMap<String, Process>()

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
        startForeground(1, buildNotification())
        updateNotification()

        val archKey = intent?.getStringExtra(EXTRA_ARCH) ?: ARCH_ARM64
        if (!isRunning(archKey)) {
            val sshPort = intent?.getIntExtra(EXTRA_SSH_PORT, 2222) ?: 2222
            val vncPort = intent?.getIntExtra(EXTRA_VNC_PORT, -1)?.takeIf { it > 0 }
            val spicePort = intent?.getIntExtra(EXTRA_SPICE_PORT, -1)?.takeIf { it > 0 }
            val headless = intent?.getBooleanExtra(EXTRA_HEADLESS, true) ?: true
            val arch = if (archKey == ARCH_X86_64)
                KvmDetector.GuestArch.X86_64 else KvmDetector.GuestArch.ARM64
            try {
                val launcher = NativeVmLauncher(this, arch, sshPort, vncPort, spicePort, headless)
                val archDir = File(File(filesDir, "vm"), archKey).apply { mkdirs() }
                File(archDir, "last_command.txt").writeText(launcher.buildCommand().joinToString(" "))
                processes[archKey] = launcher.start()
                updateNotification()
            } catch (e: Exception) {
                Toast.makeText(this, "VM $archKey failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        return START_STICKY
    }

    fun isRunning(archKey: String): Boolean = processes[archKey]?.isAlive == true

    fun getProcess(archKey: String): Process? = processes[archKey]?.takeIf { it.isAlive }

    /** Stops one architecture's VM only — the other keeps running untouched. */
    fun stopVm(archKey: String) {
        processes[archKey]?.let { proc ->
            proc.destroy()
            processes.remove(archKey)
        }
        updateNotification()
        if (processes.isEmpty()) {
            stopSelf()
        }
    }

    fun runningArches(): List<String> = processes.keys.filter { isRunning(it) }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        processes.values.forEach { it.destroy() }
        processes.clear()
        super.onDestroy()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    private fun buildNotification(): Notification {
        val running = runningArches()
        val text = if (running.isEmpty()) "No VM running"
        else "Running: ${running.joinToString(", ")}"
        return Notification.Builder(this, channelId)
            .setContentTitle("vm-forge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}