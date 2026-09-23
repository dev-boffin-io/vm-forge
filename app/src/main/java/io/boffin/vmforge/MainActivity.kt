package io.boffin.vmforge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import java.io.File

/**
 * Home screen. Three independent subsystems, one tab each:
 *  - ARM64 VM   : QEMU aarch64 guest (KVM on this device, or TCG)
 *  - x86_64 VM  : QEMU amd64 guest (always TCG on ARM64 hardware)
 *  - Proot      : integrated Proot Forge terminal (PRoot/Boffin/Android modes)
 *
 * The two VM tabs are fully isolated so ARM64 and x86_64 can run at the SAME
 * TIME without stepping on each other:
 *  - separate vm/<arch> dirs (own rootfs.qcow2, seed.iso, firmware, last-command log)
 *  - separate default SSH ports (arm64=2222, x86_64=2322) so both can bind at once
 *  - per-arch QEMU process in VmService; stopping one arch never kills the other
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 102
        private const val PICK_DISK_ARM64 = 201
        private const val PICK_SEED_ARM64 = 202
        private const val PICK_DISK_X86 = 203
        private const val PICK_SEED_X86 = 204
        private const val PREFS = "vm_widget_state"
    }

    private inner class VmTab(val arch: KvmDetector.GuestArch) {
        val dirName = VmService.archKey(arch) // "arm64" / "x86_64"
        val defaultSshPort = if (arch == KvmDetector.GuestArch.X86_64) 2322 else 2222

        lateinit var section: LinearLayout
        lateinit var accelStatus: TextView
        lateinit var ssh: EditText
        lateinit var vnc: EditText
        lateinit var spice: EditText
        lateinit var headless: CheckBox

        fun bind() {
            val suffix = if (arch == KvmDetector.GuestArch.X86_64) "X86" else "Arm64"
            section = if (suffix == "Arm64") findViewById(R.id.sectionArm64)
            else findViewById(R.id.sectionX86)
            accelStatus = findViewById(resources.getIdentifier("accelStatus$suffix", "id", packageName))
            ssh = findViewById(resources.getIdentifier("sshPort$suffix", "id", packageName))
            vnc = findViewById(resources.getIdentifier("vncPort$suffix", "id", packageName))
            spice = findViewById(resources.getIdentifier("spicePort$suffix", "id", packageName))
            headless = findViewById(resources.getIdentifier("headless$suffix", "id", packageName))
        }

        fun vmDir(): File = File(File(filesDir, "vm"), dirName).apply { mkdirs() }

        fun loadWidgetState(prefs: SharedPreferences) {
            ssh.setText(prefs.getString("${dirName}_ssh", ""))
            vnc.setText(prefs.getString("${dirName}_vnc", ""))
            spice.setText(prefs.getString("${dirName}_spice", ""))
            headless.isChecked = prefs.getBoolean("${dirName}_headless", true)
        }

        fun saveWidgetState(prefs: SharedPreferences) {
            prefs.edit()
                .putString("${dirName}_ssh", ssh.text.toString())
                .putString("${dirName}_vnc", vnc.text.toString())
                .putString("${dirName}_spice", spice.text.toString())
                .putBoolean("${dirName}_headless", headless.isChecked)
                .apply()
        }

        fun refreshAccelStatus() {
            val accel = KvmDetector.detect(arch)
            val label = if (accel.mode == KvmDetector.AccelMode.KVM)
                "⚡ KVM (fast)" else "🐢 TCG (software emulation, slow)"
            accelStatus.text = "$label\n${accel.reason}"
        }
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var vmTabs: TabLayout
    private var arm64 = VmTab(KvmDetector.GuestArch.ARM64)
    private var x86 = VmTab(KvmDetector.GuestArch.X86_64)

    private var vmService: VmService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vmService = (service as VmService.LocalBinder).getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            vmService = null
        }
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

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        arm64.bind(); x86.bind()
        arm64.loadWidgetState(prefs); x86.loadWidgetState(prefs)
        arm64.refreshAccelStatus(); x86.refreshAccelStatus()

        vmTabs = findViewById(R.id.mainTabs)
        wireTabs()

        wireVmTabControls(arm64, R.id.startVmButtonArm64, R.id.stopVmButtonArm64,
            R.id.openTerminalButtonArm64, R.id.viewCommandButtonArm64,
            R.id.importDiskButtonArm64, R.id.importSeedButtonArm64,
            R.id.clearDataButtonArm64, PICK_DISK_ARM64, PICK_SEED_ARM64)
        wireVmTabControls(x86, R.id.startVmButtonX86, R.id.stopVmButtonX86,
            R.id.openTerminalButtonX86, R.id.viewCommandButtonX86,
            R.id.importDiskButtonX86, R.id.importSeedButtonX86,
            R.id.clearDataButtonX86, PICK_DISK_X86, PICK_SEED_X86)

        wireProotControls()

        // Bind (bind-only) so Stop can target a single architecture. Starting is
        // done via startForegroundService as before.
        bindService(Intent(this, VmService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun wireTabs() {
        val arm64Section = arm64.section
        val x86Section = x86.section
        val prootSection = findViewById<LinearLayout>(R.id.sectionProot)
        fun select(position: Int) {
            arm64Section.visibility = if (position == 0) View.VISIBLE else View.GONE
            x86Section.visibility = if (position == 1) View.VISIBLE else View.GONE
            prootSection.visibility = if (position == 2) View.VISIBLE else View.GONE
            findViewById<android.widget.ScrollView>(R.id.mainScroll).smoothScrollTo(0, 0)
            when (position) {
                0 -> arm64.refreshAccelStatus()
                1 -> x86.refreshAccelStatus()
                2 -> refreshRootfsStatus()
            }
        }
        vmTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = select(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) = select(tab.position)
        })
        select(0)
    }

    private fun wireVmTabControls(
        tab: VmTab,
        startId: Int, stopId: Int, terminalId: Int, viewCmdId: Int,
        importDiskId: Int, importSeedId: Int, clearId: Int,
        diskReq: Int, seedReq: Int
    ) {
        findViewById<Button>(startId).setOnClickListener {
            val disk = File(tab.vmDir(), "rootfs.qcow2")
            if (!disk.exists()) {
                Toast.makeText(
                    this,
                    "rootfs.qcow2 not found for ${tab.dirName} — use \"Import rootfs.qcow2\" below first",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            tab.saveWidgetState(prefs)
            // Blank field = per-arch default (arm64 2222 / x86_64 2322), VNC/SPICE disabled
            val sshPort = tab.ssh.text.toString().toIntOrNull() ?: tab.defaultSshPort
            val vncPort = tab.vnc.text.toString().toIntOrNull()
            val spicePort = tab.spice.text.toString().toIntOrNull()
            val headless = tab.headless.isChecked
            if (!headless) {
                Toast.makeText(
                    this,
                    "Headless off: the in-app Terminal won't show console output — connect via VNC/SPICE instead",
                    Toast.LENGTH_LONG
                ).show()
            }

            Toast.makeText(this, "Starting ${tab.arch.label} VM…", Toast.LENGTH_SHORT).show()
            try {
                val intent = Intent(this, VmService::class.java).apply {
                    putExtra(VmService.EXTRA_SSH_PORT, sshPort)
                    putExtra(VmService.EXTRA_HEADLESS, headless)
                    putExtra(VmService.EXTRA_ARCH, tab.dirName)
                    vncPort?.let { putExtra(VmService.EXTRA_VNC_PORT, it) }
                    spicePort?.let { putExtra(VmService.EXTRA_SPICE_PORT, it) }
                }
                startForegroundService(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(stopId).setOnClickListener {
            val svc = vmService
            if (svc != null && svc.isRunning(tab.dirName)) {
                svc.stopVm(tab.dirName)
                Toast.makeText(this, "Stopping ${tab.arch.label} VM…", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "${tab.arch.label} VM is not running", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(terminalId).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_TARGET, TerminalActivity.TARGET_VM)
                putExtra(TerminalActivity.EXTRA_ARCH, tab.dirName)
            })
        }

        findViewById<Button>(viewCmdId).setOnClickListener {
            val cmdFile = File(tab.vmDir(), "last_command.txt")
            val content = if (cmdFile.exists()) cmdFile.readText() else "(no ${tab.dirName} VM started yet)"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Last Launch Command (${tab.dirName})")
                .setMessage(content)
                .setPositiveButton("Close", null)
                .show()
        }

        // No-adb file import: pick a file from shared storage (e.g. Downloads,
        // after moving it there via Termux/share-forge) and copy it into this
        // arch's vm/<arch>/ dir under the exact name QEMU expects.
        findViewById<Button>(importDiskId).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                diskReq
            )
        }
        findViewById<Button>(importSeedId).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                seedReq
            )
        }

        findViewById<Button>(clearId).setOnClickListener {
            val dir = tab.vmDir()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear ${tab.dirName} disk images?")
                .setMessage(
                    "Permanently delete everything in ${dir.absolutePath}\n" +
                        "(rootfs.qcow2, seed.iso, firmware, launch log) for the ${tab.arch.label} VM slot. " +
                        "The ARM64 and x86_64 slots are independent, so the other architecture is untouched."
                )
                .setPositiveButton("Clear") { _, _ ->
                    val ok = dir.deleteRecursively()
                    Toast.makeText(
                        this,
                        if (ok) "${tab.arch.label} VM slot cleared" else "Clear failed (some files may be in use)",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun wireProotControls() {
        // Proot Forge: the full Compose terminal with the PRoot/Boffin/Android
        // container modes (rootfs download via URL, sessions, etc). All state lives in
        // <filesDir-parent>/local — with applicationId io.boffin.vmforge that's
        // /data/user/0/io.boffin.vmforge/local/.
        findViewById<Button>(R.id.openProotForgeButton).setOnClickListener {
            startActivity(Intent(this, io.boffin.proot.ui.activities.terminal.MainActivity::class.java))
        }

        findViewById<Button>(R.id.clearRootfsButton).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear Boffin rootfs?")
                .setMessage("Permanently delete the downloaded archive (boffin.tar.gz) AND its extracted rootfs, so the next session re-installs from scratch.")
                .setPositiveButton("Clear") { _, _ ->
                    clearBoffinRootfs()
                    refreshRootfsStatus()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshRootfsStatus()
    }

    private fun localDir(): File = File(filesDir.parentFile, "local")

    private fun boffinRootfsFiles(): Pair<File, File> =
        File(filesDir, "boffin.tar.gz") to File(localDir(), "boffin")

    private fun refreshRootfsStatus() {
        val (archive, extracted) = boffinRootfsFiles()
        val archiveMb = if (archive.exists()) archive.length() / 1024 / 1024 else 0L
        val extractedMb = if (extracted.isDirectory) extracted.walkTopDown().map { it.length() }.sum() / 1024 / 1024 else 0L
        rootfsStatus().text = when {
            archive.exists() && extracted.isDirectory ->
                "Boffin rootfs installed (archive $archiveMb MB, extracted $extractedMb MB)"
            archive.exists() ->
                "Boffin archive downloaded, not yet extracted ($archiveMb MB)"
            extracted.isDirectory ->
                "Boffin rootfs extracted ($extractedMb MB)"
            else ->
                "No Boffin rootfs — open Proot Forge Terminal and install one (URL) to set it up"
        }
    }

    private fun rootfsStatus(): TextView = findViewById(R.id.rootfsStatusText)

    private fun clearBoffinRootfs() {
        val (archive, extracted) = boffinRootfsFiles()
        archive.delete()
        File(filesDir, "boffin.tar.gz.part").delete()
        extracted.deleteRecursively()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data ?: return

        val (destName, destDir) = when (requestCode) {
            PICK_DISK_ARM64 -> "rootfs.qcow2" to arm64.vmDir()
            PICK_SEED_ARM64 -> "seed.iso" to arm64.vmDir()
            PICK_DISK_X86 -> "rootfs.qcow2" to x86.vmDir()
            PICK_SEED_X86 -> "seed.iso" to x86.vmDir()
            else -> return
        }
        val dest = File(destDir, destName)

        // Copying multi-GB qcow2/iso on the main thread stalls the UI past
        // the 5s ANR-WatchDog threshold and the app gets force-killed.
        // Do it on a background thread and report back on the UI thread.
        Toast.makeText(this, "Importing $destName (background)…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Imported $destName into vm/${destDir.name} (${dest.length() / 1024 / 1024} MB)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}