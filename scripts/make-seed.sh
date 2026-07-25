#!/bin/bash
# ব্যবহার: ./make-seed.sh
# একটা cloud-init NoCloud seed ISO বানায়, যেটাতে র‍্যান্ডম পাসওয়ার্ড সেট করা থাকে।
# এই ISO-টা QEMU-তে -cdrom দিয়ে বুট করালে প্রথমবার Debian cloud image
# এই পাসওয়ার্ডটা ব্যবহার করবে (Kalidroid-এর মতো ফিক্সড root/kali না)।
set -e

VM_DIR="${1:-$HOME/vm-test}"
mkdir -p "$VM_DIR"
cd "$VM_DIR"

PASSWORD=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16)

cat > user-data <<EOF
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
EOF

cat > meta-data <<EOF
instance-id: vm-forge-01
local-hostname: vm-forge
EOF

pkg install -y cdrtools 2>/dev/null || pkg install -y genisoimage 2>/dev/null
if command -v mkisofs >/dev/null; then
  mkisofs -output seed.iso -volid cidata -joliet -rock user-data meta-data
elif command -v genisoimage >/dev/null; then
  genisoimage -output seed.iso -volid cidata -joliet -rock user-data meta-data
else
  echo "mkisofs/genisoimage কোনোটাই পাওয়া যায়নি — pkg install cdrtools দিয়ে ইনস্টল করুন"
  exit 1
fi

echo ""
echo "seed.iso বানানো হয়েছে: $VM_DIR/seed.iso"
echo "ইউজারনেম: sumit"
echo "পাসওয়ার্ড:  $PASSWORD"
echo ""
echo "এই পাসওয়ার্ডটা নিরাপদে সংরক্ষণ করুন — এটা আর কোথাও দেখানো হবে না।"
