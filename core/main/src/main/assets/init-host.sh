DISTRO_DIR=${DISTRO_DIR:-boffin}
DISTRO_ARCHIVE=${DISTRO_ARCHIVE:-boffin.tar.gz}
ROOTFS_DIR=$PREFIX/local/$DISTRO_DIR

mkdir -p $ROOTFS_DIR

if [ -z "$(ls -A "$ROOTFS_DIR" | grep -vE '^(root|tmp)$')" ]; then
    tar -xf "$PREFIX/files/$DISTRO_ARCHIVE" -C "$ROOTFS_DIR"
fi

# Rootfs archives ship without /etc/resolv.conf (it is normally
# provided by the container runtime), so drop in a static one or DNS won't work at all.
if [ ! -e "$ROOTFS_DIR/etc/resolv.conf" ]; then
    mkdir -p "$ROOTFS_DIR/etc"
    printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > "$ROOTFS_DIR/etc/resolv.conf"
fi

# Only install the busybox-based rm wrapper when the rootfs actually ships busybox
# (e.g. Debian-based Boffin rootfs archives ship /bin/busybox; some minimal ones do not).
if [ -f "$BIN/rm" ] && { [ -x "$ROOTFS_DIR/bin/busybox" ] || [ -x "$ROOTFS_DIR/usr/bin/busybox" ]; }; then
    rm -f "$ROOTFS_DIR/bin/rm"
    cp "$BIN/rm" "$ROOTFS_DIR/bin/rm"
    chmod +x "$ROOTFS_DIR/bin/rm"
fi

ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi


ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

if [ ! -d "$PREFIX/local/$DISTRO_DIR/tmp" ]; then
 mkdir -p "$PREFIX/local/$DISTRO_DIR/tmp"
 chmod 1777 "$PREFIX/local/$DISTRO_DIR/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/$DISTRO_DIR/tmp:/dev/shm"

ARGS="$ARGS -r $PREFIX/local/$DISTRO_DIR"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$PROOT $ARGS sh $PREFIX/local/bin/init "$@"
