package io.boffin.vmforge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream

/**
 * A minimal interactive console for the running QEMU VM's serial output
 * (started with -nographic, so its stdio IS the guest's console).
 *
 * This is NOT a full VT100/ANSI terminal emulator — no cursor positioning,
 * no colors, no screen redraw handling. ANSI escape sequences are stripped
 * so the log stays readable. It's good enough for line-based shell use
 * (login prompts, running commands, reading output) but things like `top`,
 * `vim`, or tab-completion redraws won't render correctly. A real terminal
 * emulator (e.g. Termux's TerminalView/TerminalEmulator libraries) would be
 * the next step if that's needed.
 */
class TerminalActivity : AppCompatActivity() {

    private var vmService: VmService? = null
    private var bound = false
    private var readerThread: Thread? = null
    @Volatile private var keepReading = true

    private lateinit var outputView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText

    // Strips ANSI escape/color codes (e.g. "\u001B[0;32m") for readability
    private val ansiRegex = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VmService.LocalBinder
            vmService = binder.getService()
            bound = true
            startReadingOutput()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            vmService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        outputView = findViewById(R.id.terminalOutput)
        scrollView = findViewById(R.id.terminalScroll)
        inputField = findViewById(R.id.terminalInput)
        val sendButton = findViewById<Button>(R.id.terminalSendButton)

        val sendCurrentInput = {
            val text = inputField.text.toString()
            inputField.setText("")
            sendLine(text)
        }
        sendButton.setOnClickListener { sendCurrentInput() }
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput(); true
            } else false
        }

        // Ensure the VM is running, then bind to the service to get its process streams
        startForegroundService(Intent(this, VmService::class.java))
        bindService(Intent(this, VmService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun startReadingOutput() {
        keepReading = true
        readerThread = Thread {
            val stream = vmService?.qemuProcess?.inputStream ?: return@Thread
            val buffer = ByteArray(4096)
            while (keepReading) {
                val n = try { stream.read(buffer) } catch (_: Exception) { break }
                if (n <= 0) break
                val chunk = ansiRegex.replace(String(buffer, 0, n, Charsets.UTF_8), "")
                runOnUiThread {
                    outputView.append(chunk)
                    scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendLine(text: String) {
        val out: OutputStream = vmService?.qemuProcess?.outputStream ?: return
        try {
            out.write((text + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
            // process likely no longer running
        }
    }

    override fun onDestroy() {
        keepReading = false
        readerThread?.interrupt()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
