# vm-forge

Android-এর জন্য নিজে-বানানো, বিশ্বাসযোগ্য QEMU VM লঞ্চার — Kalidroid-এর মতো
অজানা সোর্সের APK-এর বিকল্প হিসেবে। [Virt-Forge](../virt-forge)-এর Android ভার্সন না,
আলাদা প্রজেক্ট (কারণ Virt-Forge ডেস্কটপ-টার্গেটেড)।

## এই স্কেলিটনে যা আছে

- `KvmDetector.kt` — `/dev/kvm` অ্যাক্সেসযোগ্য কিনা চেক করে, ইউজারকে
  স্পষ্টভাবে জানায় VM KVM (দ্রুত) নাকি TCG (সফটওয়্যার এমুলেশন, স্লো) মোডে চলবে
- `QemuLauncher.kt` — QEMU কমান্ড লাইন বানায়; SSH পোর্ট শুধু `127.0.0.1`-এ
  ফরওয়ার্ড করা থাকে (বাইরে এক্সপোজড না), র‍্যান্ডম লোকাল পোর্টে
- `VmService.kt` — ফরগ্রাউন্ড সার্ভিস, যাতে Android ব্যাকগ্রাউন্ডে VM প্রসেস
  kill না করে দেয়
- `MainActivity.kt` — শুরুতেই KVM/TCG স্ট্যাটাস দেখায়

## QEMU বাইনারি — কেন সরাসরি কপি করা যায় না

Termux-এ `pkg install`-করা `qemu-system-aarch64` বাইনারিটা Termux-এর নিজের
prefix (`/data/data/com.termux/files/usr`)-এর সাথে হার্ডকোডেড লিংকড
(glib, pixman ইত্যাদি shared library-র জন্য)। তাই এটা কপি করে সরাসরি
`vm-forge`-এ বসিয়ে দিলে চলবে না। দুইটা পথ:

- **Path A (আগে ভেরিফাই করার জন্য):** `scripts/test-in-termux.sh` — Termux-এর
  নিজের QEMU দিয়েই ডিভাইসে টেস্ট বুট করে দেখা, বাকি সবকিছু (ইমেজ, UEFI,
  cloud-init) ঠিক আছে কিনা যাচাই করা। App বান্ডলিং ছাড়াই।
- **Path B (আসল standalone অ্যাপ):** `termux-packages`-এর বিল্ড স্ক্রিপ্ট
  `io.boffin.vmforge`-এর নিজের prefix দিয়ে রি-কনফিগার করে ক্রস-কম্পাইল করে
  `app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so` (ও সব
  dependency `.so`) হিসেবে বান্ডল করা।

## এখনো যা বাকি (পরবর্তী ধাপ)

1. ✅ ~~QEMU বাইনারি সংগ্রহ~~ → Path A দিয়ে verify করুন আগে (`scripts/test-in-termux.sh`)
2. ✅ ~~কার্নেল + rootfs~~ → Debian `genericcloud-arm64.qcow2` (স্ক্রিপ্টেই ডাউনলোড হয়) +
   UEFI ফার্মওয়্যার — আলাদা কার্নেল extract করা লাগছে না
3. ✅ ~~প্রথম-বুট পাসওয়ার্ড~~ → `scripts/make-seed.sh` cloud-init seed ISO বানায়,
   র‍্যান্ডম পাসওয়ার্ড দেখায়
4. **টার্মিনাল UI:** সিরিয়াল কনসোলের আউটপুট দেখানোর জন্য একটা টার্মিনাল ভিউ
   যোগ করা (Termux-এর `TerminalView` লাইব্রেরি বা নিজের `ReTerminal`
   প্রজেক্টের কোড রি-ইউজ করা যেতে পারে)
5. **Path B বাস্তবায়ন:** `termux-packages` ক্লোন করে custom prefix দিয়ে
   QEMU + dependency ক্রস-কম্পাইল, `jniLibs`-এ বান্ডল
6. **ভেরিফিকেশন:** Android Studio (PC-তে) দিয়ে প্রথম `gradlew assembleDebug`
   ভেরিফাই করে নেওয়া সহজ হবে
