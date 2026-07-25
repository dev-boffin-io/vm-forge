#!/bin/bash
# Termux-এ (proot-এর ভেতরে না, বেস Termux-এ) রান করুন
set -e

echo "== ধাপ ১: QEMU ইনস্টল =="
pkg install -y qemu-system-aarch64-headless qemu-utils

echo "== ধাপ ২: বাইনারি লোকেশন ও dependency চেক =="
QEMU_BIN=$(which qemu-system-aarch64)
echo "বাইনারি: $QEMU_BIN"
readelf -d "$QEMU_BIN" | grep -i "rpath\|runpath" || true
echo ""
echo "এই RPATH/RUNPATH-টাই বোঝায় কেন বাইনারিটা অন্য অ্যাপে সরাসরি কপি করলে চলবে না —"
echo "এটা $PREFIX (Termux-এর নিজের prefix) থেকে shared library খুঁজবে।"

echo "== ধাপ ৩: Debian arm64 cloud image ও UEFI firmware ডাউনলোড =="
mkdir -p ~/vm-test && cd ~/vm-test
wget -c https://cloud.debian.org/images/cloud/trixie/latest/debian-13-genericcloud-arm64.qcow2
pkg install -y edk2-aarch64 2>/dev/null || echo "edk2-aarch64 প্যাকেজ না থাকলে ম্যানুয়ালি সংগ্রহ করতে হবে (নিচে নোট দেখুন)"

echo "== ধাপ ৪: ডিস্ক রিসাইজ (ডিফল্ট ইমেজ ছোট, ২GB+ বাড়ানো ভালো) =="
qemu-img resize debian-13-genericcloud-arm64.qcow2 +8G

echo ""
echo "সব রেডি থাকলে টেস্ট বুট করতে নিচের কমান্ড রান করুন:"
echo "qemu-system-aarch64 -M virt -cpu max -smp 2 -m 2048 \\"
echo "  -bios \$PREFIX/share/qemu/edk2-aarch64-code.fd \\"
echo "  -drive file=debian-13-genericcloud-arm64.qcow2,if=virtio,format=qcow2 \\"
echo "  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:2222-:22 -device virtio-net-device,netdev=net0 \\"
echo "  -nographic"
