package io.boffin.proot.ui.screens.terminal

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.child
import com.rk.libcommons.localDir
import com.rk.settings.Settings
import java.io.File

enum class ExecMode(val value: Int) {
    CHROOT(0),
    PROOT(1);

    companion object {
        fun fromInt(v: Int): ExecMode? = entries.firstOrNull { it.value == v }
    }
}

object Rootfs {
    var isInstalled = mutableStateOf(false)
    var isBoffinInstalled = mutableStateOf(false)
    var execMode = mutableStateOf(ExecMode.fromInt(Settings.exec_mode))

    fun setExecMode(mode: ExecMode) {
        execMode.value = mode
        Settings.exec_mode = mode.value
    }

    fun checkInstallation(context: Context) {
        isInstalled.value = isRootfsInstalled(context)
        isBoffinInstalled.value = isBoffinRootfsInstalled(context)
    }

    fun isRootfsInstalled(context: Context): Boolean {
        val debianDir = context.localDir().child("debian")
        val isExtracted = debianDir.exists() && (debianDir.list()?.any { it != "root" && it != "tmp" } == true)
        val isArchivePresent = context.filesDir.child("debian.tar.gz").exists()
        return isExtracted || isArchivePresent
    }

    fun isBoffinRootfsInstalled(context: Context): Boolean {
        val boffinDir = context.localDir().child("boffin")
        val isExtracted = boffinDir.exists() && (boffinDir.list()?.any { it != "root" && it != "tmp" } == true)
        val isArchivePresent = context.filesDir.child("boffin.tar.gz").exists()
        return isExtracted || isArchivePresent
    }
}
