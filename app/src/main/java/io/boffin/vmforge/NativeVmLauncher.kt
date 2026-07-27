package io.boffin.vmforge

import android.content.Context
import java.io.File

/**
 * Path B: extracts qemu-system-aarch64 and its bundled .so dependencies
 * from assets/qemu-libs/ (with their original, versioned filenames
 * preserved — e.g. "libglib-2.0.so.0") into the app's private files
 * directory, then launches QEMU with LD_LIBRARY_PATH pointing there.
 *
 * This works without a termux-packages cross-compile because the
 * binary's dynamic section uses DT_RUNPATH (not the older DT_RPATH) —
 * and the dynamic linker checks LD_LIBRARY_PATH before DT_RUNPATH.
 * See scripts/collect-native-deps.sh for how these files were collected.
 */
class NativeVmLauncher(private val context: Context) {

    private val nativeDir: File
        get() = File(context.filesDir, "native").apply { mkdirs() }

    private val vmDir: File
        get() = File(context.filesDir, "vm").apply { mkdirs() }

    /** Copies bundled assets into filesDir on first run (or if the asset list changed). */
    fun ensureExtracted() {
        val assetSubPath = "qemu-libs"
        val assetFiles = context.assets.list(assetSubPath) ?: return
        for (name in assetFiles) {
            val dest = File(nativeDir, name)
            if (dest.exists()) continue
            context.assets.open("$assetSubPath/$name").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.setExecutable(true)
        }
    }

    fun buildCommand(): List<String> {
        ensureExtracted()

        val accel = KvmDetector.detect()
        val qemuBinary = File(nativeDir, "qemu-system-aarch64")
        val uefiCode = File(nativeDir, "edk2-aarch64-code.fd")
        val disk = File(vmDir, "rootfs.qcow2")
        val seedIso = File(vmDir, "seed.iso")

        val cmd = mutableListOf(
            qemuBinary.absolutePath,
            "-M", "virt",
            "-cpu", "max",
            "-smp", "2",
            "-m", "2048",
            "-bios", uefiCode.absolutePath,
            "-drive", "file=${disk.absolutePath},if=virtio,format=qcow2",
            "-device", "virtio-net-device,netdev=net0",
            "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:2222-:22",
            "-nographic"
        )
        if (seedIso.exists()) {
            cmd.add("-cdrom")
            cmd.add(seedIso.absolutePath)
        }
        if (accel.mode == KvmDetector.AccelMode.KVM) {
            cmd.add("-enable-kvm")
        }
        return cmd
    }

    /** Launches QEMU with LD_LIBRARY_PATH pointing at the extracted bundled libs. */
    fun start(): Process {
        val cmd = buildCommand()
        return ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath }
            .start()
    }
}
