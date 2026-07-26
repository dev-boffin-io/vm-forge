package io.boffin.vmforge

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity

/**
 * v0.1: instead of bundling QEMU, this triggers Termux's own QEMU install
 * via the com.termux.RUN_COMMAND intent. Two things are required for this
 * to work:
 *
 *   1. Termux's ~/.termux/termux.properties must contain
 *      "allow-external-apps=true" (then restart Termux)
 *   2. vm-forge must be granted the RUN_COMMAND runtime permission
 *
 * With this approach, the VM runs as a Termux process — vm-forge just
 * triggers start/stop.
 */
object TermuxVmController {

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

    private const val SCRIPTS_DIR = "/data/data/com.termux/files/home/vm-forge/scripts"
    private const val WORK_DIR = "/data/data/com.termux/files/home/vm-test"

    fun hasPermission(activity: FragmentActivity): Boolean =
        ActivityCompat.checkSelfPermission(activity, "com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED

    fun requestPermission(activity: FragmentActivity, requestCode: Int) {
        ActivityCompat.requestPermissions(
            activity, arrayOf("com.termux.permission.RUN_COMMAND"), requestCode
        )
    }

    /**
     * Launches scripts/run-vm.sh inside Termux in a new visible session
     * (background=false), so the user can see the VM boot log/console
     * directly in Termux.
     */
    fun startVm(activity: FragmentActivity) {
        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", "$SCRIPTS_DIR/run-vm.sh")
            putExtra("com.termux.RUN_COMMAND_WORKDIR", WORK_DIR)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0") // new session, bring to foreground
        }
        activity.startForegroundService(intent)
    }

    /**
     * Runs scripts/stop-vm.sh in the background (no new visible session needed)
     * to kill the running qemu-system-aarch64 process.
     */
    fun stopVm(activity: FragmentActivity) {
        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", "$SCRIPTS_DIR/stop-vm.sh")
            putExtra("com.termux.RUN_COMMAND_WORKDIR", WORK_DIR)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        activity.startForegroundService(intent)
    }
}
