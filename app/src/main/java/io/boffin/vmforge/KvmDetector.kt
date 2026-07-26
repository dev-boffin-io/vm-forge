package io.boffin.vmforge

import java.io.File

/**
 * Detects whether hardware virtualization (/dev/kvm) is accessible on this
 * device. Most non-Pixel devices (MediaTek/Xiaomi, etc.) don't have it —
 * in that case QEMU falls back to software emulation (TCG), which is
 * noticeably slower.
 *
 * This is surfaced to the user clearly, not hidden.
 */
object KvmDetector {

    enum class AccelMode { KVM, TCG }

    data class AccelResult(
        val mode: AccelMode,
        val reason: String
    )

    fun detect(): AccelResult {
        val kvmNode = File("/dev/kvm")

        if (!kvmNode.exists()) {
            return AccelResult(
                mode = AccelMode.TCG,
                reason = "No /dev/kvm on this device — no pKVM/AVF support. " +
                    "The VM will run in software emulation, noticeably slower than normal."
            )
        }

        if (!kvmNode.canRead() || !kvmNode.canWrite()) {
            return AccelResult(
                mode = AccelMode.TCG,
                reason = "/dev/kvm exists but this app doesn't have access to it. " +
                    "Will run in TCG (software) mode."
            )
        }

        return AccelResult(
            mode = AccelMode.KVM,
            reason = "Hardware virtualization is available — the VM will run close to native speed."
        )
    }
}
