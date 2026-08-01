package io.boffin.vmforge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 102
        private const val PICK_DISK_REQUEST = 201
        private const val PICK_SEED_REQUEST = 202
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 13+ requires this at runtime, or the foreground service
        // notification silently never shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }

        val accel = KvmDetector.detect()
        val statusView = findViewById<TextView>(R.id.accelStatus)
        val startButton = findViewById<Button>(R.id.startVmButton)
        val stopButton = findViewById<Button>(R.id.stopVmButton)
        val openTerminalButton = findViewById<Button>(R.id.openTerminalButton)
        val importDiskButton = findViewById<Button>(R.id.importDiskButton)
        val importSeedButton = findViewById<Button>(R.id.importSeedButton)

        val label = if (accel.mode == KvmDetector.AccelMode.KVM) "⚡ KVM (fast)" else "🐢 TCG (software emulation, slow)"
        statusView.text = "$label\n${accel.reason}"

        val sshPortInput = findViewById<android.widget.EditText>(R.id.sshPortInput)
        val vncPortInput = findViewById<android.widget.EditText>(R.id.vncPortInput)
        val spicePortInput = findViewById<android.widget.EditText>(R.id.spicePortInput)

        startButton.setOnClickListener {
            val disk = File(File(filesDir, "vm"), "rootfs.qcow2")
            if (!disk.exists()) {
                Toast.makeText(
                    this,
                    "rootfs.qcow2 not found — use \"Import rootfs.qcow2\" below first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            // Blank field = native default (SSH 2222, VNC/SPICE disabled)
            val sshPort = sshPortInput.text.toString().toIntOrNull() ?: 2222
            val vncPort = vncPortInput.text.toString().toIntOrNull()
            val spicePort = spicePortInput.text.toString().toIntOrNull()

            Toast.makeText(this, "Starting VM…", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, VmService::class.java).apply {
                    putExtra(VmService.EXTRA_SSH_PORT, sshPort)
                    vncPort?.let { putExtra(VmService.EXTRA_VNC_PORT, it) }
                    spicePort?.let { putExtra(VmService.EXTRA_SPICE_PORT, it) }
                }
                startForegroundService(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        stopButton.setOnClickListener {
            stopService(Intent(this, VmService::class.java))
            Toast.makeText(this, "Stop requested", Toast.LENGTH_SHORT).show()
        }

        openTerminalButton.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }

        // No-adb file import: pick a file from shared storage (e.g. Downloads,
        // after moving it there via Termux/share-forge) and copy it into
        // filesDir/vm/ under the exact name QEMU expects.
        importDiskButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, PICK_DISK_REQUEST)
        }
        importSeedButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, PICK_SEED_REQUEST)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data ?: return

        val destName = when (requestCode) {
            PICK_DISK_REQUEST -> "rootfs.qcow2"
            PICK_SEED_REQUEST -> "seed.iso"
            else -> return
        }

        val vmDir = File(filesDir, "vm").apply { mkdirs() }
        val dest = File(vmDir, destName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            Toast.makeText(this, "Imported as $destName (${dest.length() / 1024 / 1024} MB)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
