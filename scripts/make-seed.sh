#!/bin/bash
# Usage: ./make-seed.sh
# Builds a cloud-init NoCloud seed ISO with a randomly generated password.
# Boot this ISO in QEMU with -cdrom and, on first boot, the Debian cloud
# image will use this password (instead of a fixed default login).
set -e

VM_DIR="${1:-$HOME/vm-test}"
mkdir -p "$VM_DIR"
cd "$VM_DIR"

PASSWORD=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16)

cat > user-data <<INNER_EOF
#cloud-config
hostname: vm-forge
users:
  - name: sumit
    sudo: ALL=(ALL) NOPASSWD:ALL
    lock_passwd: false
    plain_text_passwd: '${PASSWORD}'
    shell: /bin/bash
ssh_pwauth: true
chpasswd:
  expire: false
INNER_EOF

cat > meta-data <<INNER_EOF
instance-id: vm-forge-01
local-hostname: vm-forge
INNER_EOF

pkg install -y xorriso 2>/dev/null || pkg install -y cdrtools 2>/dev/null || pkg install -y genisoimage 2>/dev/null
if command -v xorriso >/dev/null; then
  xorriso -as mkisofs -output seed.iso -volid cidata -joliet -rock user-data meta-data
elif command -v mkisofs >/dev/null; then
  mkisofs -output seed.iso -volid cidata -joliet -rock user-data meta-data
elif command -v genisoimage >/dev/null; then
  genisoimage -output seed.iso -volid cidata -joliet -rock user-data meta-data
else
  echo "None of xorriso/mkisofs/genisoimage found — install with 'pkg install xorriso'"
  exit 1
fi

echo ""
echo "seed.iso created at: $VM_DIR/seed.iso"
echo "Username: sumit"
echo "Password: $PASSWORD"
echo ""
echo "Save this password somewhere safe — it won't be shown again."
