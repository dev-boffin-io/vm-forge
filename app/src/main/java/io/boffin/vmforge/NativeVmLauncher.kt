package io.boffin.vmforge

import android.content.Context
import java.io.File

/**
 * Launches QEMU directly from the app's nativeLibraryDir, where Android's
 * PackageManager already extracted it at install time (from
 * app/src/main/jniLibs/arm64-v8a/).
 *
 * IMPORTANT: this deliberately does NOT copy binaries out of assets/ into
 * filesDir at runtime and exec them from there — Android 10+ (API 29+)
 * blocks executing (and even dlopen-mapping-as-executable) files that live
 * in the app's writable private storage (W^X protection), regardless of
 * chmod. nativeLibraryDir is specifically exempt because the system
 * verifies and extracts those files itself at install time. See
 * scripts/patch-for-jnilibs.sh for how the bundled binary/libs were
 * renamed (to plain "libX.so" names, with patchelf fixing up SONAME/NEEDED
 * references) to fit Android's jniLibs packaging convention.
 *
 * The UEFI firmware (edk2-aarch64-code.fd) is just data — never executed
 * or mapped exec — so it's fine to keep in assets/qemu-libs/ and extract
 * normally to filesDir.
 *
 * @param sshPort local port SSH is forwarded to (default 2222 if null/blank)
 * @param vncPort if set, exposes a VNC server on 127.0.0.1:vncPort; disabled by default
 * @param spicePort if set, exposes a SPICE server on 127.0.0.1:spicePort; disabled by default
 */
class NativeVmLauncher(
    private val context: Context,
    private val sshPort: Int = 2222,
    private val vncPort: Int? = null,
    private val spicePort: Int? = null
) {

    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val vmDir: File
        get() = File(context.filesDir, "vm").apply { mkdirs() }

    /** Copies the UEFI firmware (plain data, not executed) from assets on first run. */
    private fun ensureFirmwareExtracted(): File {
        val dest = File(vmDir, "edk2-aarch64-code.fd")
        if (!dest.exists()) {
            context.assets.open("qemu-libs/edk2-aarch64-code.fd").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    fun buildCommand(): List<String> {
        val accel = KvmDetector.detect()
        val qemuBinary = File(nativeLibDir, "libqemu_system_aarch64.so")
        val uefiCode = ensureFirmwareExtracted()
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
            "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:$sshPort-:22",
            "-nographic"
        )
        if (seedIso.exists()) {
            cmd.add("-cdrom")
            cmd.add(seedIso.absolutePath)
        }
        if (accel.mode == KvmDetector.AccelMode.KVM) {
            cmd.add("-enable-kvm")
        }

        // VNC: QEMU's -vnc takes a display NUMBER, not a raw port — the
        // actual TCP port is always 5900+display. We accept a real port
        // from the user and convert it here so they don't have to think
        // about that offset.
        vncPort?.let { port ->
            val display = port - 5900
            if (display >= 0) {
                cmd.add("-vnc"); cmd.add("127.0.0.1:$display")
            }
        }

        // SPICE takes a raw port directly, no offset needed.
        spicePort?.let { port ->
            cmd.add("-spice"); cmd.add("port=$port,addr=127.0.0.1,disable-ticketing=on")
        }

        return cmd
    }

    /** Launches QEMU with LD_LIBRARY_PATH pointing at nativeLibraryDir (where all the bundled .so live). */
    fun start(): Process {
        val cmd = buildCommand()
        return ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath }
            .start()
    }
}
