package io.boffin.vmforge

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val RUN_COMMAND_PERMISSION_REQUEST = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val accel = KvmDetector.detect()
        val statusView = findViewById<TextView>(R.id.accelStatus)
        val startButton = findViewById<Button>(R.id.startVmButton)
        val stopButton = findViewById<Button>(R.id.stopVmButton)
        val startStandaloneButton = findViewById<Button>(R.id.startStandaloneButton)
        val stopStandaloneButton = findViewById<Button>(R.id.stopStandaloneButton)

        val label = if (accel.mode == KvmDetector.AccelMode.KVM) "⚡ KVM (fast)" else "🐢 TCG (software emulation, slow)"
        statusView.text = "$label\n${accel.reason}"

        // v0.1: VM start/stop is driven via Termux RUN_COMMAND
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

        // Path B: fully standalone, no Termux dependency — runs the bundled
        // QEMU + libs (from assets/qemu-libs) via VmService/NativeVmLauncher.
        // Needs rootfs.qcow2 and seed.iso to already be present in
        // filesDir/vm/ (not downloaded by the app yet — copy them there
        // manually for now, e.g. via adb push, until a proper setup flow
        // is added).
        startStandaloneButton.setOnClickListener {
            startForegroundService(Intent(this, VmService::class.java))
        }
        stopStandaloneButton.setOnClickListener {
            stopService(Intent(this, VmService::class.java))
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
