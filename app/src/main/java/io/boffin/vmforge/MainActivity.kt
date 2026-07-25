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

        val label = if (accel.mode == KvmDetector.AccelMode.KVM) "⚡ KVM (দ্রুত)" else "🐢 TCG (সফটওয়্যার এমুলেশন, স্লো)"
        statusView.text = "$label\n${accel.reason}"

        // v0.1: Termux RUN_COMMAND দিয়ে VM চালু করে (path B বান্ডলিং এখনো বাকি)
        startButton.text = "VM চালু করুন (Termux দিয়ে)"
        startButton.setOnClickListener {
            if (TermuxVmController.hasPermission(this)) {
                TermuxVmController.startVm(this)
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
