package io.boffin.vmforge

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val accel = KvmDetector.detect()
        val statusView = findViewById<TextView>(R.id.accelStatus)
        val startButton = findViewById<Button>(R.id.startVmButton)

        val label = if (accel.mode == KvmDetector.AccelMode.KVM) "⚡ KVM (দ্রুত)" else "🐢 TCG (সফটওয়্যার এমুলেশন, স্লো)"
        statusView.text = "$label\n${accel.reason}"

        startButton.setOnClickListener {
            val intent = Intent(this, VmService::class.java)
            startForegroundService(intent)
        }
    }
}
