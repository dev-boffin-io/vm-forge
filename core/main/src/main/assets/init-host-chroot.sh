#!/bin/sh
SU="/system/bin/su"
ROOTFS_DIR=$PREFIX/local/debian

mkdir -p $ROOTFS_DIR

if [ -z "$(ls -A "$ROOTFS_DIR" | grep -vE '^(root|tmp)$')" ]; then
    tar -xf "$PREFIX/files/debian.tar.gz" -C "$ROOTFS_DIR"
fi

# The official Debian rootfs tarballs ship without /etc/resolv.conf (it is normally
# provided by the container runtime), so drop in a static one or DNS won't work at all.
if [ ! -e "$ROOTFS_DIR/etc/resolv.conf" ]; then
    $SU -c "mkdir -p '$ROOTFS_DIR/etc'"
    $SU -c "printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > '$ROOTFS_DIR/etc/resolv.conf'"
fi

# Only install the busybox-based rm wrapper when the rootfs actually ships busybox
# (the Alpine-based Kali rootfs did; official Debian rootfs tarballs do not).
if [ -f "$BIN/rm" ] && { [ -x "$ROOTFS_DIR/bin/busybox" ] || [ -x "$ROOTFS_DIR/usr/bin/busybox" ]; }; then
    rm -f "$ROOTFS_DIR/bin/rm"
    cp "$BIN/rm" "$ROOTFS_DIR/bin/rm"
    chmod +x "$ROOTFS_DIR/bin/rm"
fi

MOUNTS=""

mnt_bind() {
    src="$1"
    dst="$ROOTFS_DIR${2:-$1}"
    if [ -e "$src" ] && [ ! -e "$dst" ]; then
        mkdir -p "$(dirname "$dst")" 2>/dev/null
        if [ -d "$src" ]; then
            $SU -c "mkdir -p '$dst'"
        else
            $SU -c "touch '$dst'"
        fi
    fi
    if [ -e "$src" ]; then
        $SU -c "mount --bind '$src' '$dst'" 2>/dev/null
        MOUNTS="$MOUNTS $dst"
    fi
}

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do
    if [ -e "$system_mnt" ]; then
        system_mnt=$(realpath "$system_mnt")
        mnt_bind "$system_mnt"
    fi
done
unset system_mnt

mnt_bind /sdcard
mnt_bind /storage
mnt_bind /dev
mnt_bind /data
mnt_bind /proc
mnt_bind /sys
mnt_bind /dev/urandom /dev/random
mnt_bind $PREFIX

if [ -e "/proc/self/fd" ]; then mnt_bind /proc/self/fd /dev/fd; fi
if [ -e "/proc/self/fd/0" ]; then mnt_bind /proc/self/fd/0 /dev/stdin; fi
if [ -e "/proc/self/fd/1" ]; then mnt_bind /proc/self/fd/1 /dev/stdout; fi
if [ -e "/proc/self/fd/2" ]; then mnt_bind /proc/self/fd/2 /dev/stderr; fi

if [ ! -d "$PREFIX/local/debian/tmp" ]; then
    $SU -c "mkdir -p '$PREFIX/local/debian/tmp' && chmod 1777 '$PREFIX/local/debian/tmp'"
fi
mnt_bind "$PREFIX/local/debian/tmp" /dev/shm

if [ -e "$PREFIX/local/stat" ]; then
    $SU -c "cp '$PREFIX/local/stat' '$ROOTFS_DIR/proc/stat'" 2>/dev/null
fi
if [ -e "$PREFIX/local/vmstat" ]; then
    $SU -c "cp '$PREFIX/local/vmstat' '$ROOTFS_DIR/proc/vmstat'" 2>/dev/null
fi

cleanup() {
    for m in $MOUNTS; do
        $SU -c "umount -l '$m'" 2>/dev/null
    done
}
trap cleanup EXIT INT TERM

$SU -c "'$CHROOT' '$ROOTFS_DIR' /usr/bin/env -i HOME=/root PATH=/bin:/sbin:/usr/bin:/usr/sbin sh '$PREFIX/local/bin/init' $*"
cleanup
