package io.boffin.vmforge

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity

/**
 * v0.1: QEMU বান্ডল করার বদলে Termux-এর নিজস্ব QEMU ইনস্টলকেই
 * com.termux.RUN_COMMAND Intent দিয়ে ট্রিগার করে। এর জন্য দুটো শর্ত লাগবে:
 *
 *   1. Termux-এ ~/.termux/termux.properties ফাইলে
 *      "allow-external-apps=true" লাইন থাকতে হবে (তারপর Termux রিস্টার্ট)
 *   2. vm-forge অ্যাপকে RUN_COMMAND পারমিশন (রানটাইমে) দিতে হবে
 *
 * এই পদ্ধতিতে VM নিজে Termux-এর প্রসেস হিসেবে চলে — vm-forge শুধু ট্রিগার করে।
 */
object TermuxVmController {

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

    private const val SCRIPT_PATH = "/data/data/com.termux/files/home/vm-forge/scripts/run-vm.sh"
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
     * Termux-এ scripts/run-vm.sh চালু করে — নতুন ভিজিবল সেশনে (background=false),
     * যাতে ইউজার সরাসরি VM-এর বুট লগ/কনসোল Termux-এ দেখতে পারে।
     */
    fun startVm(activity: FragmentActivity) {
        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", SCRIPT_PATH)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", WORK_DIR)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0") // নতুন সেশন, ফোরগ্রাউন্ডে দেখাও
        }
        activity.startForegroundService(intent)
    }
}
