package io.boffin.vmforge

import android.content.Context
import java.io.File
import kotlin.random.Random

/**
 * Path B (not yet implemented): launches a QEMU process using a binary and
 * disk images bundled inside the app's own filesDir. No root or
 * system-level permission required.
 *
 * Binary location (must be bundled at app build time):
 *   app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so
 *   (named as a .so so Android extracts and marks it executable
 *    automatically on APK install)
 *
 * v0.1 boot strategy: Debian genericcloud arm64 qcow2 (no separate
 * -kernel needed) + edk2 UEFI firmware + cloud-init seed.iso for a
 * randomly generated first-boot password (see scripts/make-seed.sh).
 * The image/firmware/seed must be placed in vmDir per the README steps —
 * the app does not download them itself.
 */
class QemuLauncher(private val context: Context) {

    private val vmDir: File
        get() = File(context.filesDir, "vm").apply { mkdirs() }

    private val sshHostPort = 2222 + Random.nextInt(1000) // random local port per run

    fun buildCommand(): List<String> {
        val accel = KvmDetector.detect()
        val qemuBinary = File(context.applicationInfo.nativeLibraryDir, "libqemu_system_aarch64.so")
        val uefiCode = File(vmDir, "edk2-aarch64-code.fd")
        val disk = File(vmDir, "rootfs.qcow2")
        val seedIso = File(vmDir, "seed.iso")

        val cmd = mutableListOf(
            qemuBinary.absolutePath,
            "-M", "virt",
            "-cpu", "max",
            "-smp", "2",                 // more vCPUs isn't always better under TCG — start with 2
            "-m", "2048",
            "-bios", uefiCode.absolutePath,
            "-drive", "file=${disk.absolutePath},if=virtio,format=qcow2",
            "-device", "virtio-net-device,netdev=net0",
            // SSH forwarded to localhost only, never exposed externally — unlike Kalidroid
            "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:$sshHostPort-:22",
            "-nographic"                 // v0.1: serial console only, no GUI/VNC — for performance
        )

        // seed.iso is only needed on first boot (to set user/password); add it if present
        if (seedIso.exists()) {
            cmd.add("-cdrom")
            cmd.add(seedIso.absolutePath)
        }

        if (accel.mode == KvmDetector.AccelMode.KVM) {
            cmd.add("-enable-kvm")
        }
        // Nothing extra needed without KVM — QEMU falls back to TCG by default

        return cmd
    }

    fun sshPort(): Int = sshHostPort
}
