package io.boffin.vmforge

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

        val label = if (accel.mode == KvmDetector.AccelMode.KVM) "⚡ KVM (fast)" else "🐢 TCG (software emulation, slow)"
        statusView.text = "$label\n${accel.reason}"

        // v0.1: VM start/stop is driven via Termux RUN_COMMAND (bundling is path B, still pending)
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
