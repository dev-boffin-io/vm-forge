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

## এখনো যা বাকি (পরবর্তী ধাপ)

1. **QEMU বাইনারি সংগ্রহ:** Termux-এর `termux-packages` রিপো থেকে
   `qemu-system-aarch64`-এর build script রি-ইউজ করে ARM64 বাইনারি বানিয়ে
   `app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so` নামে রাখা
   (নাম `lib*.so` না দিলে Android APK ইনস্টলের সময় executable extract করে না)
2. **কার্নেল + rootfs ইমেজ:** একটা মিনিমাল ARM64 কার্নেল (Alpine/Debian-এর
   জন্য বিল্ড করা virt-machine-compatible) + rootfs qcow2 ইমেজ বানিয়ে
   প্রথমবার অ্যাপ চালু হওয়ার সময় assets থেকে `filesDir/vm/`-এ কপি করা
   (`QemuLauncher.vmDir` যেটা এক্সপেক্ট করছে)
3. **টার্মিনাল UI:** সিরিয়াল কনসোলের আউটপুট দেখানোর জন্য একটা টার্মিনাল ভিউ
   যোগ করা (Termux-এর `TerminalView` লাইব্রেরি বা নিজের `ReTerminal`
   প্রজেক্টের কোড রি-ইউজ করা যেতে পারে)
4. **প্রথম-বুট পাসওয়ার্ড:** rootfs ইমেজে ডিফল্ট পাসওয়ার্ড না রেখে, প্রথম বুটে
   র‍্যান্ডম পাসওয়ার্ড জেনারেট করে ইউজারকে দেখানো (cloud-init স্টাইলে)
5. **ভেরিফিকেশন:** ল্যাপটপ/PC না থাকলে সরাসরি `gradlew assembleDebug`
   Termux-এ রান করা কঠিন হবে — Android Studio (PC-তে) দিয়ে প্রথম বিল্ডটা
   ভেরিফাই করে নেওয়া সহজ হবে
