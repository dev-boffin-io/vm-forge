package io.boffin.vmforge

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Runs as a *separate process*, spawned by Shizuku under shell UID (ADB-
 * level privilege) instead of this app's own sandboxed UID — see
 * IShellService.aidl and RootfsImporter/PRootLauncher's class docs for
 * why that matters. Do not add app-specific state here beyond what's
 * needed to track the one long-running process this service manages;
 * this class is instantiated fresh in a separate process and has no
 * access to the running app's memory.
 */
class ShellUserService : IShellService.Stub() {

    @Volatile private var process: Process? = null

    override fun runShell(script: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = p.inputStream.bufferedReader().readText()
            val exitCode = p.waitFor()
            "$exitCode\n$output"
        } catch (e: Exception) {
            "-1\n${e.message}"
        }
    }

    override fun startProcess(cmd: Array<String>, env: Array<String>?): Array<ParcelFileDescriptor> {
        val builder = ProcessBuilder(*cmd)
        if (env != null) {
            builder.environment().apply {
                for (entry in env) {
                    val idx = entry.indexOf('=')
                    if (idx > 0) put(entry.substring(0, idx), entry.substring(idx + 1))
                }
            }
        }
        val p = builder.start()
        process = p
        val stdinPfd = ParcelFileDescriptor.dup((p.outputStream as FileOutputStream).fd)
        val stdoutPfd = ParcelFileDescriptor.dup((p.inputStream as FileInputStream).fd)
        return arrayOf(stdinPfd, stdoutPfd)
    }

    override fun isProcessAlive(): Boolean = process?.isAlive == true

    override fun waitForProcess(): Int = process?.waitFor() ?: -1

    override fun destroyProcess() {
        process?.destroy()
        process = null
    }

    override fun destroy() {
        process?.destroy()
        System.exit(0)
    }
}
