#!/bin/bash
# Renames versioned .so files (e.g. libfoo.so.1) to plain "libfoo.so" names
# so Android's jniLibs packaging picks them up, and patches SONAME + all
# NEEDED references (with patchelf) so they still resolve correctly.
# Run this on the folder produced by collect-native-deps.sh.
set -e

pkg install -y patchelf

SRC_DIR="${1:-$HOME/vm-forge-native}"
OUT_DIR="${2:-$HOME/vm-forge-jnilibs}"
mkdir -p "$OUT_DIR"

cd "$SRC_DIR"

declare -A rename_map

# Step 1: figure out the new name for every file that doesn't already end in .so
for f in *; do
    if [[ "$f" == *.so ]]; then
        new_name="$f"
    else
        # strip everything after the first ".so" occurrence, keep ".so"
        new_name=$(echo "$f" | sed -E 's/(\.so)\..*/\1/')
        if [[ "$new_name" != *.so ]]; then
            # qemu-system-aarch64 binary itself, or edk2 firmware — handle separately
            continue
        fi
    fi
    rename_map["$f"]="$new_name"
done

# Step 2: copy files under their new names
for old_name in "${!rename_map[@]}"; do
    new_name="${rename_map[$old_name]}"
    cp "$old_name" "$OUT_DIR/$new_name"
done

# The qemu binary itself needs the "lib...so" convention too, for jniLibs
cp qemu-system-aarch64 "$OUT_DIR/libqemu_system_aarch64.so"
# Firmware is just data (never executed/mapped exec), fine to keep as-is —
# put it in assets instead, not jniLibs (see next step's instructions)

# Step 3: patch SONAME on renamed libs (only where it differs from new name)
for old_name in "${!rename_map[@]}"; do
    new_name="${rename_map[$old_name]}"
    target="$OUT_DIR/$new_name"
    patchelf --set-soname "$new_name" "$target" 2>/dev/null || true
done

# Step 4: patch NEEDED references in every file to point at the new names
for f in "$OUT_DIR"/*.so; do
    for old_name in "${!rename_map[@]}"; do
        new_name="${rename_map[$old_name]}"
        if [ "$old_name" != "$new_name" ]; then
            patchelf --replace-needed "$old_name" "$new_name" "$f" 2>/dev/null || true
        fi
    done
done

echo ""
echo "Done. $(ls "$OUT_DIR" | wc -l) files ready in $OUT_DIR"
echo "Next: copy these into app/src/main/jniLibs/arm64-v8a/ on your PC"
echo "(NOT assets/qemu-libs/ — that approach is blocked by Android's W^X policy)."
echo "Keep edk2-aarch64-code.fd separately in assets/qemu-libs/ (it's just data, never executed)."
