# vm-forge

A self-built, trustworthy QEMU VM launcher for Android — a replacement
for untrusted third-party APKs. Not an Android port of
[Virt-Forge](../virt-forge) (which stays desktop-only); a separate project.
Fully standalone — the app bundles its own QEMU binary and runs an ARM64
Debian VM entirely on-device, no Termux dependency.

## What's in the app

- `KvmDetector.kt` — checks whether `/dev/kvm` is accessible and tells the
  user plainly whether the VM will run in KVM (fast) or TCG (software
  emulation, slow) mode
- `NativeVmLauncher.kt` — launches QEMU straight from
  `applicationInfo.nativeLibraryDir` (where Android extracted the bundled
  binary/libs at install time) with `LD_LIBRARY_PATH` set there
- `VmService.kt` — foreground service that owns the running QEMU process;
  bindable, so `TerminalActivity` can read/write its stdio directly
- `MainActivity.kt` — shows KVM/TCG status, Start/Stop VM, Open Terminal,
  and import buttons for the disk image + cloud-init seed
- `TerminalActivity.kt` — a minimal interactive console for the VM's
  serial output (ANSI codes stripped for readability; not a full
  VT100 emulator — good for shell use, not for full-screen apps like
  `top` or `vim`)

## GitHub Actions build

`.github/workflows/build.yml` automatically builds debug and release APKs
on every push/PR to `main`. Once the build finishes, go to the GitHub
Actions tab and download `vm-forge-release-apk` (installs directly, no
extra setup) from that run's "Artifacts" section — no local Android
Studio/PC setup required.

## Setting up the VM files

The app needs three files in place before it can boot a VM: the Debian
disk image, the UEFI firmware (bundled with the app already), and a
cloud-init seed for the first-boot password. The disk image + seed are
prepared once (on a PC or via Termux, since they need tools not worth
bundling into the app itself) and then imported into the app:

1. Get a Debian arm64 cloud image and build a seed ISO — either:
   - **Via Termux:** run `scripts/test-in-termux.sh` then
     `scripts/make-seed.sh` (see "How the native binary was collected"
     below for background) — produces `~/vm-test/debian-13-genericcloud-arm64.qcow2`
     and `~/vm-test/seed.iso`
   - Or prepare equivalent files any other way
2. Copy both files to somewhere the app's file picker can reach, e.g.
   `/sdcard/Download/` (`cp ~/vm-test/*.qcow2 ~/vm-test/seed.iso /sdcard/Download/`
   in Termux)
3. In the app, tap **"Import rootfs.qcow2"** and **"Import seed.iso"**,
   picking each file from Downloads — this copies them into the app's
   private storage under the exact names QEMU expects
4. Tap **"Start VM"**, then **"Open Terminal"** to watch it boot and log in
   (first-boot password is shown by `make-seed.sh` — save it, it's not
   shown again)

## How the native binary was collected (background, not needed day-to-day)

The `qemu-system-aarch64` binary installed via `pkg install` in Termux is
hard-linked to Termux's own prefix (`/data/data/com.termux/files/usr`)
for shared libraries (glib, pixman, etc.), so it can't be copied straight
into another app. The process used to get a working standalone build:

1. `scripts/test-in-termux.sh` — boot-tested the VM using Termux's own
   QEMU first, to confirm the image/UEFI/cloud-init setup all work
   before touching the app
2. `scripts/collect-native-deps.sh` — collected `qemu-system-aarch64` and
   all its transitive `.so` dependencies from Termux
3. `scripts/patch-for-jnilibs.sh` — renamed the versioned `.so` files
   (e.g. `libfoo.so.1` → `libfoo.so`) and used `patchelf` to fix up
   SONAME/NEEDED references so they still resolve, then placed everything
   in `app/src/main/jniLibs/arm64-v8a/` (**not** `assets/` — Android 10+
   blocks executing, or even dlopen-mapping-as-executable, files copied
   to an app's writable private storage at runtime, regardless of
   `chmod`; only `jniLibs`, extracted by PackageManager at install time,
   is exempt from this)
4. `app/build.gradle.kts` sets `packaging.jniLibs.useLegacyPackaging = true`
   — otherwise AGP keeps native libs uncompressed inside the APK instead
   of extracting them to disk, and the binary can't be exec'd from there

None of this needs to be repeated unless the bundled QEMU itself needs
updating.

## Adding another guest architecture (e.g. x86_64/AMD64)

The app supports selecting ARM64 or x86_64 (AMD64) as the guest
architecture from the UI. **x86_64 always runs in full software
emulation (TCG) on this ARM64 device** — KVM only accelerates
same-architecture virtualization, never cross-architecture, regardless
of `/dev/kvm` access. Expect it to be significantly slower than ARM64
guests.

To add the x86_64 binary (this only needs to be done once):

1. `pkg install qemu-system-x86_64-headless` in Termux
2. `scripts/collect-native-deps.sh qemu-system-x86_64` — merges into the
   same `~/vm-forge-native` folder used for ARM64 (most `.so`
   dependencies are shared between the two, since both are ARM64 host
   binaries that just emulate different guest architectures)
3. `scripts/patch-for-jnilibs.sh` — now renames *every* `qemu-system-*`
   binary it finds (not just aarch64) to the `libqemu_system_<arch>.so`
   convention
4. Copy the result into `app/src/main/jniLibs/arm64-v8a/` as before —
   you should end up with both `libqemu_system_aarch64.so` and
   `libqemu_system_x86_64.so` side by side, sharing the same dependency
   `.so` files
5. Also copy `edk2-x86_64-code.fd` (from `$PREFIX/share/qemu/`) into
   `app/src/main/assets/qemu-libs/`, alongside the existing
   `edk2-aarch64-code.fd`
6. Get an x86_64 Debian cloud image (same idea as
   `debian-13-genericcloud-arm64.qcow2` but the `-amd64.qcow2` variant
   from the same `cloud.debian.org` path) and import it the same way

**Untested / worth verifying:** the x86_64 machine type (`q35`) is
launched with only the `CODE` firmware file via `-bios`, mirroring the
ARM64 `virt` machine's setup — but x86 OVMF conventionally wants a
separate writable `VARS` file too (`edk2-x86_64-vars.fd`) for NVRAM
persistence across boots. It may work fine read-only for a single
session; if UEFI boot menu settings don't persist or boot fails,
splitting into `-drive if=pflash,file=...code.fd,readonly=on` +
`-drive if=pflash,file=...vars.fd` (copied to a writable location first)
is the standard fix.

## PRoot Container (separate mode, no VM at all)

Alongside the QEMU VM path, the app has a second, independent mode:
**PRoot Container**. Instead of emulating a machine, PRoot chroots
(without root) into a plain Linux rootfs directory that shares this
device's own kernel — much lighter and faster than a QEMU VM, but with
tradeoffs:

- **ARM64-only** — same-architecture; no x86_64 option here (that would
  need QEMU user-mode emulation layered inside PRoot, not set up)
- **No boot, no kernel, no GUI** — PRoot just execs `/bin/sh` directly
  inside the rootfs; there's no init/systemd, no display, just a shell
- **Less isolated** — the host's `/dev`, `/proc`, `/sys` are bind-mounted
  into the rootfs

Good for quickly running ARM64 Linux command-line tools without the
overhead of a full VM; not a substitute for the VM path if you need a
real boot sequence, a desktop GUI, or x86_64 software.

### Setup

1. Collect the `proot` binary the same way QEMU was collected (the
   scripts already work with any binary name):
   ```
   pkg install proot
   ./scripts/collect-native-deps.sh proot
   ./scripts/patch-for-jnilibs.sh
   ```
   copy the result into `jniLibs/arm64-v8a/` as before — you'll get
   `libproot.so` alongside the QEMU binaries (dependencies are mostly
   shared)
2. Get a rootfs tarball — e.g. install `proot-distro` in Termux
   (`pkg install proot-distro`) and use it to fetch one
   (`proot-distro download debian` or similar produces a `.tar.gz`
   rootfs you can point the app at), or build one with `debootstrap`
3. In the app, tap **"Import PRoot rootfs (.tar.gz)"** and pick the
   tarball from Downloads (same no-adb approach as the VM disk import —
   extraction happens in-app, no `tar` binary needed)
4. **"Start PRoot Container"**, then **"Open PRoot Terminal"**

**Untested / worth verifying:** this hasn't been run end-to-end yet —
worth checking that `-b /dev -b /proc -b /sys` is sufficient for typical
package-manager operations inside the rootfs, and that a real
proot-distro-produced tarball extracts cleanly (symlink handling in
particular is best-effort in `RootfsImporter.kt`).

## Still to do

- **PRoot Container:** see above — not yet verified end-to-end

- **x86_64 UEFI vars persistence:** see above — not yet verified
- **In-app VM setup:** the disk image + seed still need to be prepared
  externally (Termux) and imported by hand; a fully in-app
  download/provisioning flow would remove that step
- **Real terminal emulator:** `TerminalActivity` is line-based and strips
  ANSI codes rather than rendering them — full-screen console apps
  inside the VM won't display correctly; swapping in Termux's
  `TerminalView`/`TerminalEmulator` libraries would fix this
- **KVM devices:** untested on a device that actually has `/dev/kvm`
  access (e.g. Pixel with pKVM) — should be significantly faster there
  for ARM64 guests (never applies to x86_64 guests, see above)
