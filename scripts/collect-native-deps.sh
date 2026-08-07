#!/bin/bash
# Collects a native binary (qemu-system-*, busybox, ...) + all its transitive
# .so dependencies into a staging folder, preserving their exact (versioned)
# filenames. These get bundled into vm-forge's app — no termux-packages/Docker
# cross-compile needed, since LD_LIBRARY_PATH is checked before RUNPATH.
#
# Usage: ./collect-native-deps.sh [binary-name] [out-dir]
#   ./collect-native-deps.sh                      # qemu-system-aarch64 (default)
#   ./collect-native-deps.sh qemu-system-x86_64    # for AMD64/x86_64 guest support
#   ./collect-native-deps.sh busybox               # busybox (usually static, but
#                                                   #   any .so deps it does have
#                                                   #   get pulled in too)
#
# Safe to run multiple times into the SAME out-dir for different binaries —
# it merges in new files and skips ones already collected (most .so deps
# are shared between the aarch64 and x86_64 QEMU builds since both are
# just ARM64 host binaries that emulate different guest architectures).
set -e
pkg install -y binutils 2>/dev/null || true
BIN_NAME="${1:-qemu-system-aarch64}"
OUT_DIR="${2:-$HOME/vm-forge-native}"
mkdir -p "$OUT_DIR"

BIN=$(command -v "$BIN_NAME") || {
    echo "❌ $BIN_NAME not found — install it first, e.g.:"
    case "$BIN_NAME" in
        qemu-system-aarch64) echo "   pkg install qemu-system-aarch64-headless" ;;
        qemu-system-x86_64)  echo "   pkg install qemu-system-x86_64-headless" ;;
        busybox)             echo "   pkg install busybox" ;;
        *)                   echo "   pkg install $BIN_NAME" ;;
    esac
    exit 1
}
cp "$BIN" "$OUT_DIR/"

# Also grab the matching UEFI firmware while we're at it (best-effort —
# only applies to qemu-system-* targets; busybox and other binaries have
# no firmware counterpart)
case "$BIN_NAME" in
    qemu-system-aarch64) FW="edk2-aarch64-code.fd" ;;
    qemu-system-x86_64)  FW="edk2-x86_64-code.fd" ;;
    *) FW="" ;;
esac
if [ -n "$FW" ]; then
    cp "$PREFIX/share/qemu/$FW" "$OUT_DIR/" 2>/dev/null || \
        echo "⚠️  Firmware $FW not found under \$PREFIX/share/qemu/ — check manually"
fi

declare -A seen
# Pre-mark files already present in OUT_DIR from a previous run (merge mode)
for existing in "$OUT_DIR"/*; do
    [ -f "$existing" ] && seen["$(basename "$existing")"]=1
done

queue=("$BIN")
while [ ${#queue[@]} -gt 0 ]; do
    current="${queue[0]}"
    queue=("${queue[@]:1}")
    # busybox is typically statically linked, so this usually finds nothing
    # for it — that's expected, not an error.
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
echo "Collected $(ls "$OUT_DIR" | wc -l) files total in $OUT_DIR"
echo "Total size: $(du -sh "$OUT_DIR" | cut -f1)"
echo ""
echo "Next: copy this folder's contents (with exact filenames preserved)"
echo "and run scripts/patch-for-jnilibs.sh on it."
