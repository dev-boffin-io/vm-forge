#!/bin/bash
# Renames versioned .so files (e.g. libfoo.so.1) to plain "libfoo.so" names
# so Android's jniLibs packaging picks them up, and patches SONAME + all
# NEEDED references (with patchelf) so they still resolve correctly.
# Also renames any qemu-system-* binaries found (e.g. qemu-system-aarch64,
# qemu-system-x86_64) to the "libqemu_system_<arch>.so" convention jniLibs
# needs. Run this on the folder produced by collect-native-deps.sh.
set -e

pkg install -y patchelf

SRC_DIR="${1:-$HOME/vm-forge-native}"
OUT_DIR="${2:-$HOME/vm-forge-jnilibs}"
mkdir -p "$OUT_DIR"

cd "$SRC_DIR"

declare -A rename_map
qemu_binaries=()

# Step 1: figure out the new name for every file that doesn't already end in .so
for f in *; do
    [ -f "$f" ] || continue
    if [[ "$f" == qemu-system-* ]]; then
        qemu_binaries+=("$f")
        continue
    fi
    if [[ "$f" == edk2-*-code.fd ]]; then
        continue  # firmware — stays in assets/qemu-libs/, not jniLibs
    fi
    if [[ "$f" == *.so ]]; then
        new_name="$f"
    else
        # strip everything after the first ".so" occurrence, keep ".so"
        new_name=$(echo "$f" | sed -E 's/(\.so)\..*/\1/')
        [[ "$new_name" != *.so ]] && continue
    fi
    rename_map["$f"]="$new_name"
done

# Step 2: copy libs under their new names
for old_name in "${!rename_map[@]}"; do
    new_name="${rename_map[$old_name]}"
    cp "$old_name" "$OUT_DIR/$new_name"
done

# Step 2b: copy each qemu-system-* binary under the "lib...so" convention jniLibs needs
for bin in "${qemu_binaries[@]}"; do
    arch="${bin#qemu-system-}"
    cp "$bin" "$OUT_DIR/libqemu_system_${arch}.so"
    echo "  $bin -> libqemu_system_${arch}.so"
done

# Step 3: patch SONAME on renamed libs (only where it differs from new name)
for old_name in "${!rename_map[@]}"; do
    new_name="${rename_map[$old_name]}"
    target="$OUT_DIR/$new_name"
    patchelf --set-soname "$new_name" "$target" 2>/dev/null || true
done

# Step 4: patch NEEDED references in every file (libs AND qemu binaries) to
# point at the new names
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
echo "Keep edk2-*-code.fd firmware files separately in assets/qemu-libs/ (data, never executed)."
