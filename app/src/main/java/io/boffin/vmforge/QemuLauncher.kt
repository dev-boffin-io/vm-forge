package io.boffin.vmforge

import android.content.Context
import java.io.File
import kotlin.random.Random

/**
 * app-এর নিজের filesDir-এর ভেতরে QEMU বাইনারি + কার্নেল + ডিস্ক ইমেজ রেখে
 * একটা VM প্রসেস লঞ্চ করে। কোনো root বা সিস্টেম-লেভেল পারমিশন লাগে না।
 *
 * বাইনারি লোকেশন (অ্যাপ বিল্ড করার সময় বান্ডল করতে হবে):
 *   app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so
 *   (এক্সিকিউটেবলকে .so নামে রাখা হয়েছে যাতে APK ইনস্টলের সময়
 *    Android এটা automatically extract + executable করে দেয়)
 */
class QemuLauncher(private val context: Context) {

    private val vmDir: File
        get() = File(context.filesDir, "vm").apply { mkdirs() }

    private val sshHostPort = 2222 + Random.nextInt(1000) // প্রতি রানে র‍্যান্ডম লোকাল পোর্ট

    /**
     * v0.1 boot strategy: Debian genericcloud arm64 qcow2 (আলাদা -kernel দরকার নেই)
     * + edk2 UEFI firmware দিয়ে বুট + cloud-init seed.iso দিয়ে প্রথম-বুটে
     * র‍্যান্ডম পাসওয়ার্ড সেট (scripts/make-seed.sh)। ইমেজ/ফার্মওয়্যার/সিড
     * README-এ বলা ধাপ অনুযায়ী vmDir-এ রাখতে হবে — অ্যাপ নিজে ডাউনলোড করবে না।
     */
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
            "-smp", "2",                 // TCG মোডে বেশি vCPU সবসময় ভালো না — ২টা দিয়ে শুরু
            "-m", "2048",
            "-bios", uefiCode.absolutePath,
            "-drive", "file=${disk.absolutePath},if=virtio,format=qcow2",
            "-device", "virtio-net-device,netdev=net0",
            // SSH শুধু localhost-এ ফরওয়ার্ড, বাইরে এক্সপোজড না — Kalidroid-এর মতো না
            "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:$sshHostPort-:22",
            "-nographic"                 // v0.1: শুধু সিরিয়াল কনসোল, GUI/VNC নেই — পারফরম্যান্সের জন্য
        )

        // প্রথম বুটেই শুধু seed.iso যোগ করা হবে (পাসওয়ার্ড সেট করার পর সরিয়ে ফেলা যায়)
        if (seedIso.exists()) {
            cmd.add("-cdrom")
            cmd.add(seedIso.absolutePath)
        }

        if (accel.mode == KvmDetector.AccelMode.KVM) {
            cmd.add("-enable-kvm")
        }
        // KVM না থাকলে কিছু যোগ করার দরকার নেই — QEMU ডিফল্টেই TCG-তে ফলব্যাক করে

        return cmd
    }

    fun sshPort(): Int = sshHostPort
}
