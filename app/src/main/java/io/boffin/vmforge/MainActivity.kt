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
        private const val RUN_COMMAND_PERMISSION_REQUEST = 101
        private const val NOTIFICATION_PERMISSION_REQUEST = 102
        private const val PICK_DISK_REQUEST = 201
        private const val PICK_SEED_REQUEST = 202
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 13+ requires this at runtime, or the foreground service
        // notification silently never shows (the service can still run,
        // but you'd have no visible confirmation it started).
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
        val startStandaloneButton = findViewById<Button>(R.id.startStandaloneButton)
        val stopStandaloneButton = findViewById<Button>(R.id.stopStandaloneButton)
        val importDiskButton = findViewById<Button>(R.id.importDiskButton)
        val importSeedButton = findViewById<Button>(R.id.importSeedButton)

        val label = if (accel.mode == KvmDetector.AccelMode.KVM) "⚡ KVM (fast)" else "🐢 TCG (software emulation, slow)"
        statusView.text = "$label\n${accel.reason}"

        startButton.setOnClickListener {
            if (TermuxVmController.hasPermission(this)) {
                TermuxVmController.startVm(this)
            } else {
                TermuxVmController.requestPermission(this, RUN_COMMAND_PERMISSION_REQUEST)
            }
        }
        stopButton.setOnClickListener {
            if (TermuxVmController.hasPermission(this)) {
                TermuxVmController.stopVm(this)
            } else {
                TermuxVmController.requestPermission(this, RUN_COMMAND_PERMISSION_REQUEST)
            }
        }

        startStandaloneButton.setOnClickListener {
            val vmDir = File(filesDir, "vm")
            val disk = File(vmDir, "rootfs.qcow2")
            if (!disk.exists()) {
                Toast.makeText(
                    this,
                    "rootfs.qcow2 not found — use \"Import rootfs.qcow2\" below first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Starting VmService…", Toast.LENGTH_SHORT).show()
            try {
                startForegroundService(Intent(this, VmService::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        stopStandaloneButton.setOnClickListener {
            stopService(Intent(this, VmService::class.java))
            Toast.makeText(this, "Stop requested", Toast.LENGTH_SHORT).show()
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

        findViewById<Button>(R.id.viewLogButton).setOnClickListener {
            val logFile = File(File(filesDir, "vm"), "vm.log")
            val content = if (logFile.exists()) {
                // Show only the tail — the full boot log can be long
                logFile.readText().takeLast(6000)
            } else {
                "(no log yet — start the standalone VM first)"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("VM Log (last part)")
                .setMessage(content)
                .setPositiveButton("Close", null)
                .show()
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RUN_COMMAND_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            TermuxVmController.startVm(this)
        }
    }
}
