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
        private const val PICK_ROOTFS_REQUEST = 203
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

        // If a previous SyscallDiagnostic.runDiagnostic() run force-closed
        // the app, this reports exactly which step it died on.
        SyscallDiagnostic.readAndClearCrashedStep(this)?.let { crashedStep ->
            showFullTextDialog(
                "Syscall diagnostic result",
                "The app force-closed during the last diagnostic run while attempting: $crashedStep\n\n" +
                    "This is the syscall that's actually being seccomp-killed."
            )
        }

        val accelStatusView = findViewById<TextView>(R.id.accelStatus)
        val startButton = findViewById<Button>(R.id.startVmButton)
        val stopButton = findViewById<Button>(R.id.stopVmButton)
        val openTerminalButton = findViewById<Button>(R.id.openTerminalButton)
        val importDiskButton = findViewById<Button>(R.id.importDiskButton)
        val importSeedButton = findViewById<Button>(R.id.importSeedButton)
        val archRadioGroup = findViewById<android.widget.RadioGroup>(R.id.archRadioGroup)

        fun selectedArch(): KvmDetector.GuestArch =
            if (archRadioGroup.checkedRadioButtonId == R.id.archX86_64)
                KvmDetector.GuestArch.X86_64 else KvmDetector.GuestArch.ARM64

        fun refreshAccelStatus() {
            val accel = KvmDetector.detect(selectedArch())
            val label = if (accel.mode == KvmDetector.AccelMode.KVM) "⚡ KVM (fast)" else "🐢 TCG (software emulation, slow)"
            accelStatusView.text = "$label\n${accel.reason}"
        }
        refreshAccelStatus()
        archRadioGroup.setOnCheckedChangeListener { _, _ -> refreshAccelStatus() }

        val sshPortInput = findViewById<android.widget.EditText>(R.id.sshPortInput)
        val vncPortInput = findViewById<android.widget.EditText>(R.id.vncPortInput)
        val spicePortInput = findViewById<android.widget.EditText>(R.id.spicePortInput)
        val headlessCheckbox = findViewById<android.widget.CheckBox>(R.id.headlessCheckbox)

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
            val headless = headlessCheckbox.isChecked
            val arch = selectedArch()
            if (!headless) {
                Toast.makeText(
                    this,
                    "Headless off: the in-app Terminal won't show console output — connect via VNC/SPICE instead",
                    Toast.LENGTH_LONG
                ).show()
            }

            Toast.makeText(this, "Starting ${arch.label} VM…", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, VmService::class.java).apply {
                    putExtra(VmService.EXTRA_SSH_PORT, sshPort)
                    putExtra(VmService.EXTRA_HEADLESS, headless)
                    putExtra(VmService.EXTRA_ARCH, if (arch == KvmDetector.GuestArch.X86_64) "x86_64" else "arm64")
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
            startActivity(Intent(this, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_TARGET, TerminalActivity.TARGET_VM)
            })
        }

        findViewById<Button>(R.id.viewCommandButton).setOnClickListener {
            val cmdFile = File(File(filesDir, "vm"), "last_command.txt")
            val content = if (cmdFile.exists()) cmdFile.readText() else "(no VM started yet)"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Last Launch Command")
                .setMessage(content)
                .setPositiveButton("Close", null)
                .show()
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

        // --- PRoot Container (separate mode from the QEMU VM above) ---
        findViewById<Button>(R.id.importRootfsButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, PICK_ROOTFS_REQUEST)
        }
        findViewById<Button>(R.id.diagnoseSyscallButton).setOnClickListener {
            Toast.makeText(
                this,
                "Running syscall diagnostic — the app may force-close, that's expected. Reopen it after.",
                Toast.LENGTH_LONG
            ).show()
            Thread {
                val result = SyscallDiagnostic.runDiagnostic(this)
                runOnUiThread {
                    showFullTextDialog("Syscall diagnostic — none crashed", result)
                }
            }.start()
        }
        findViewById<Button>(R.id.verifyRootfsButton).setOnClickListener {
            showFullTextDialog("Rootfs contents", PRootLauncher(this).verifyRootfs())
        }
        findViewById<Button>(R.id.testExecButton).setOnClickListener {
            Thread {
                val dir = File(File(filesDir.parentFile, "local"), "exec-test")
                val result = PRootLauncher(this).testExecFrom(dir)
                runOnUiThread { showFullTextDialog("Exec test result", result) }
            }.start()
        }
        findViewById<Button>(R.id.startProotButton).setOnClickListener {
            if (!PRootLauncher(this).rootfsExists()) {
                Toast.makeText(this, "No rootfs imported yet — use \"Import PRoot rootfs\" first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Starting PRoot container…", Toast.LENGTH_SHORT).show()
            startForegroundService(Intent(this, ProotService::class.java))
        }
        findViewById<Button>(R.id.stopProotButton).setOnClickListener {
            stopService(Intent(this, ProotService::class.java))
            Toast.makeText(this, "Stop requested", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.openProotTerminalButton).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_TARGET, TerminalActivity.TARGET_PROOT)
            })
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
            PICK_ROOTFS_REQUEST -> {
                Toast.makeText(this, "Extracting rootfs… this can take a while for a full distro", Toast.LENGTH_LONG).show()
                RootfsImporter.extract(this, uri) { success, message ->
                    runOnUiThread {
                        if (success) {
                            Toast.makeText(this, "Rootfs ready: $message", Toast.LENGTH_LONG).show()
                        } else {
                            showFullTextDialog("Extract failed", message)
                        }
                    }
                }
                return
            }
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

    /**
     * Long diagnostic text (native tool output, stack traces) gets silently
     * truncated by Toast — this shows it in full, scrollable and selectable
     * so it can be copied out for a bug report.
     */
    private fun showFullTextDialog(title: String, message: String) {
        val textView = TextView(this).apply {
            text = message
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
            textSize = 13f
        }
        val scroll = android.widget.ScrollView(this).apply { addView(textView) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }
}
