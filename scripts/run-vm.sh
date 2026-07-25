#!/bin/bash
# vm-forge অ্যাপের বাটন থেকে Termux RUN_COMMAND দিয়ে এই স্ক্রিপ্ট ট্রিগার হয়।
# এটা ধরে নেয় test-in-termux.sh + make-seed.sh আগে একবার রান হয়ে গেছে
# (অর্থাৎ ~/vm-test-এ qcow2, seed.iso, edk2 ফার্মওয়্যার সবই আছে)।
set -e

VM_DIR="${PWD}"
DISK="$VM_DIR/debian-13-genericcloud-arm64.qcow2"
SEED="$VM_DIR/seed.iso"
FIRMWARE="$VM_DIR/edk2-aarch64-code.fd"

if [ ! -f "$DISK" ] || [ ! -f "$FIRMWARE" ]; then
    echo "❌ VM ফাইল পাওয়া যায়নি এই ডিরেক্টরিতে: $VM_DIR"
    echo "প্রথমে scripts/test-in-termux.sh ও scripts/make-seed.sh চালিয়ে VM সেটআপ করুন।"
    exit 1
fi

ACCEL_FLAG=""
if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
    echo "⚡ KVM পাওয়া গেছে — দ্রুত মোডে বুট হবে"
    ACCEL_FLAG="-enable-kvm"
else
    echo "🐢 KVM নেই — TCG (সফটওয়্যার এমুলেশন) মোডে বুট হবে, একটু সময় লাগবে"
fi

CMD=(qemu-system-aarch64 -M virt -cpu max -smp 2 -m 2048
     -bios "$FIRMWARE"
     -drive "file=$DISK,if=virtio,format=qcow2"
     -netdev "user,id=net0,hostfwd=tcp:127.0.0.1:2222-:22"
     -device virtio-net-device,netdev=net0
     -nographic)

if [ -n "$ACCEL_FLAG" ]; then
    CMD+=("$ACCEL_FLAG")
fi
# seed.iso শুধু প্রথমবার লাগে (ইউজার/পাসওয়ার্ড সেট করতে); থাকলে যোগ করা হবে
if [ -f "$SEED" ]; then
    CMD+=(-cdrom "$SEED")
fi

echo "চালু হচ্ছে: ${CMD[*]}"
"${CMD[@]}"
