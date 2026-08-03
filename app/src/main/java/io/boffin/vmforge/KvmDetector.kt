package io.boffin.vmforge

import java.io.File

/**
 * Detects whether hardware virtualization (/dev/kvm) is accessible on this
 * device, and whether it would even apply to the selected guest
 * architecture. KVM only accelerates SAME-architecture virtualization —
 * on an ARM64 host, an x86_64 (AMD64) guest can NEVER use KVM regardless
 * of /dev/kvm access; it always runs in software emulation (TCG).
 *
 * This is surfaced to the user clearly, not hidden.
 */
object KvmDetector {

    enum class AccelMode { KVM, TCG }
    enum class GuestArch(val label: String) { ARM64("ARM64"), X86_64("x86_64 (AMD64)") }

    data class AccelResult(
        val mode: AccelMode,
        val reason: String
    )

    fun detect(guestArch: GuestArch = GuestArch.ARM64): AccelResult {
        if (guestArch == GuestArch.X86_64) {
            return AccelResult(
                mode = AccelMode.TCG,
                reason = "x86_64 (AMD64) guest on an ARM64 device — KVM can never accelerate " +
                    "cross-architecture virtualization, only same-architecture. This will always " +
                    "run in full software emulation, significantly slower than ARM64 guests."
            )
        }

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
