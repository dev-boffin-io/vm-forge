#!/bin/bash
# Collects qemu-system-aarch64 + all its transitive .so dependencies
# into a staging folder, preserving their exact (versioned) filenames.
# These get bundled into vm-forge's assets — no termux-packages/Docker
# cross-compile needed, since LD_LIBRARY_PATH is checked before RUNPATH.
set -e

pkg install -y binutils 2>/dev/null || true

OUT_DIR="${1:-$HOME/vm-forge-native}"
mkdir -p "$OUT_DIR"

QEMU_BIN=$(command -v qemu-system-aarch64)
cp "$QEMU_BIN" "$OUT_DIR/"

# Also grab the UEFI firmware while we're at it
cp "$PREFIX/share/qemu/edk2-aarch64-code.fd" "$OUT_DIR/" 2>/dev/null || true

declare -A seen
queue=("$QEMU_BIN")

while [ ${#queue[@]} -gt 0 ]; do
    current="${queue[0]}"
    queue=("${queue[@]:1}")

    needed=$(readelf -d "$current" 2>/dev/null | grep NEEDED | sed -E 's/.*\[(.*)\]/\1/')

    for lib in $needed; do
        if [ -n "${seen[$lib]}" ]; then
            continue
        fi
        seen[$lib]=1

        found="$PREFIX/lib/$lib"
        if [ -f "$found" ]; then
            cp "$found" "$OUT_DIR/"
            queue+=("$found")
        else
            echo "⚠️  Could not find $lib under $PREFIX/lib — check manually"
        fi
    done
done

echo ""
echo "Collected $(ls "$OUT_DIR" | wc -l) files into $OUT_DIR"
echo "Total size: $(du -sh "$OUT_DIR" | cut -f1)"
echo ""
echo "Next: copy this folder's contents (with exact filenames preserved)"
echo "into vm-forge's app/src/main/assets/qemu-libs/ on your PC."
