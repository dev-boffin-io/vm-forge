#!/bin/bash
# Run this in Termux (base Termux, not inside a proot)
set -e

echo "== Step 1: Install QEMU =="
pkg install -y qemu-system-aarch64-headless qemu-utils

echo "== Step 2: Binary location and dependency check =="
QEMU_BIN=$(command -v qemu-system-aarch64)
echo "Binary: $QEMU_BIN"
if command -v readelf >/dev/null; then
  readelf -d "$QEMU_BIN" | grep -i "rpath\|runpath" || true
else
  echo "(readelf not found — install with 'pkg install binutils' to see the RPATH, this is optional)"
fi
echo ""
echo "Bottom line: this binary depends on shared libraries under \$PREFIX"
echo "(Termux's own prefix, $PREFIX). That's why it won't run if copied"
echo "directly into another app."

echo "== Step 3: Download Debian arm64 cloud image and UEFI firmware =="
pkg install -y wget
mkdir -p ~/vm-test && cd ~/vm-test
wget -c https://cloud.debian.org/images/cloud/trixie/latest/debian-13-genericcloud-arm64.qcow2
pkg install -y edk2-aarch64 2>/dev/null || echo "edk2-aarch64 package not found — you may need to fetch it manually (see note below)"

echo "== Step 4: Resize the disk (the default image is small, growing by 8G+ helps) =="
qemu-img resize debian-13-genericcloud-arm64.qcow2 +8G

echo ""
echo "Once everything is ready, boot-test with:"
echo "qemu-system-aarch64 -M virt -cpu max -smp 2 -m 2048 \\"
echo "  -bios \$PREFIX/share/qemu/edk2-aarch64-code.fd \\"
echo "  -drive file=debian-13-genericcloud-arm64.qcow2,if=virtio,format=qcow2 \\"
echo "  -netdev user,id=net0,hostfwd=tcp:127.0.0.1:2222-:22 -device virtio-net-device,netdev=net0 \\"
echo "  -nographic"
