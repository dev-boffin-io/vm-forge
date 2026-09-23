package io.boffin.vmforge

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
 * so the log stays readable. It's good enough for line-based console use
 * (login prompts, running commands, reading output) but things like `top`,
 * `vim`, or tab-completion redraws won't render correctly. A real terminal
 * emulator (e.g. Termux's TerminalView/TerminalEmulator libraries) would be
 * the next step if that's needed — the Proot Forge core that ships with this
 * app IS one, see the "Open Proot Forge Terminal" button on the main screen.
 */
class TerminalActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET = "target"
        const val TARGET_VM = "vm"
        const val EXTRA_ARCH = "arch" // "arm64" or "x86_64" — which VM console to attach to
    }

    private var target = TARGET_VM
    private var archKey = VmService.ARCH_ARM64
    private var vmService: VmService? = null
    private var bound = false
    private var readerThread: Thread? = null
    @Volatile private var keepReading = true

    private lateinit var outputView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var inputField: EditText

    // Strips ANSI escape/color codes (e.g. "\u001B[0;32m") for readability
    private val ansiRegex = Regex("\u001B\\[[0-9;?]*[a-zA-Z]")
    private val ansiPartial = Regex("^\u001B\\[[0-9;?]*[a-zA-Z]")
    // Holds a possibly-incomplete escape sequence split across two read() calls
    private val pending = StringBuilder()

    private fun currentProcess(): Process? = vmService?.getProcess(archKey)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vmService = (service as VmService.LocalBinder).getService()
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

        target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_VM
        archKey = intent.getStringExtra(EXTRA_ARCH) ?: VmService.ARCH_ARM64

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
        inputField.setOnClickListener {
            inputField.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        // Attach to the running QEMU VM's process. Bind-only (no
        // startForegroundService here): the terminal is for *attaching* to a
        // VM that the Start button already booted — it must not itself launch
        // a VM (and definitely not a default-arch one).
        bindService(Intent(this, VmService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    private fun startReadingOutput() {
        keepReading = true
        readerThread = Thread {
            // The process may not have finished starting yet — wait briefly.
            var stream = currentProcess()?.inputStream
            var waited = 0
            while (stream == null && waited < 10_000 && keepReading) {
                Thread.sleep(200)
                waited += 200
                stream = currentProcess()?.inputStream
            }
            if (stream == null) {
                runOnUiThread { outputView.append("\n[Timed out waiting for process to start]\n") }
                return@Thread
            }
            val buffer = ByteArray(4096)
            while (keepReading) {
                val n = try { stream.read(buffer) } catch (_: Exception) { break }
                if (n <= 0) break
                val text = pending.toString() + String(buffer, 0, n, Charsets.UTF_8)

                val lastEsc = text.lastIndexOf('\u001B')
                val safeText: String
                if (lastEsc == -1) {
                    safeText = text
                    pending.clear()
                } else {
                    val tail = text.substring(lastEsc)
                    val match = ansiPartial.find(tail)
                    if (match != null && match.range.last == tail.length - 1) {
                        // the tail is exactly one complete escape sequence — safe to include
                        safeText = text
                        pending.clear()
                    } else {
                        // incomplete (or something trailing after it) — hold back for next read,
                        // unless it's grown suspiciously long (not a real ANSI code), then just flush it
                        if (tail.length > 64) {
                            safeText = text
                            pending.clear()
                        } else {
                            safeText = text.substring(0, lastEsc)
                            pending.clear()
                            pending.append(tail)
                        }
                    }
                }

                val chunk = ansiRegex.replace(safeText, "")
                runOnUiThread {
                    outputView.append(chunk)
                    scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendLine(text: String) {
        val out: OutputStream = currentProcess()?.outputStream ?: return
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