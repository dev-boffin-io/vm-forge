#!/bin/bash
# Triggered by the vm-forge app button via Termux RUN_COMMAND.
# Assumes test-in-termux.sh + make-seed.sh have already been run once
# (i.e. ~/vm-test already has the qcow2, seed.iso, and edk2 firmware).
set -e

VM_DIR="${PWD}"
DISK="$VM_DIR/debian-13-genericcloud-arm64.qcow2"
SEED="$VM_DIR/seed.iso"
FIRMWARE="$VM_DIR/edk2-aarch64-code.fd"

if [ ! -f "$DISK" ] || [ ! -f "$FIRMWARE" ]; then
    echo "❌ VM files not found in this directory: $VM_DIR"
    echo "Run scripts/test-in-termux.sh and scripts/make-seed.sh first to set up the VM."
    exit 1
fi

ACCEL_FLAG=""
if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then
    echo "⚡ KVM found — booting in fast mode"
    ACCEL_FLAG="-enable-kvm"
else
    echo "🐢 No KVM — booting in TCG (software emulation) mode, this will take a bit"
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
# seed.iso is only needed on first boot (to set the user/password); added if present
if [ -f "$SEED" ]; then
    CMD+=(-cdrom "$SEED")
fi

echo "Starting: ${CMD[*]}"
"${CMD[@]}"
