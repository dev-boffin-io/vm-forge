package io.boffin.vmforge

import java.io.File

/**
 * চেক করে এই ডিভাইসে হার্ডওয়্যার ভার্চুয়ালাইজেশন (/dev/kvm) অ্যাক্সেসযোগ্য কিনা।
 * বেশিরভাগ non-Pixel ডিভাইসে (MediaTek/Xiaomi ইত্যাদি) এটা থাকে না —
 * সেক্ষেত্রে QEMU সফটওয়্যার এমুলেশনে (TCG) চলবে, যেটা লক্ষণীয়ভাবে স্লো।
 *
 * এই তথ্য ইউজারকে স্পষ্টভাবে জানানো হবে, লুকানো হবে না।
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
                reason = "এই ডিভাইসে /dev/kvm নেই — pKVM/AVF সাপোর্ট নেই। " +
                    "VM সফটওয়্যার এমুলেশনে চলবে, স্বাভাবিকের চেয়ে অনেক স্লো হবে।"
            )
        }

        if (!kvmNode.canRead() || !kvmNode.canWrite()) {
            return AccelResult(
                mode = AccelMode.TCG,
                reason = "/dev/kvm আছে কিন্তু এই অ্যাপের অ্যাক্সেস পারমিশন নেই। " +
                    "TCG (সফটওয়্যার) মোডে চলবে।"
            )
        }

        return AccelResult(
            mode = AccelMode.KVM,
            reason = "হার্ডওয়্যার ভার্চুয়ালাইজেশন উপলব্ধ — VM নেটিভ স্পিডের কাছাকাছি চলবে।"
        )
    }
}
