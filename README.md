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

## Still to do

- **In-app VM setup:** the disk image + seed still need to be prepared
  externally (Termux) and imported by hand; a fully in-app
  download/provisioning flow would remove that step
- **Real terminal emulator:** `TerminalActivity` is line-based and strips
  ANSI codes rather than rendering them — full-screen console apps
  inside the VM won't display correctly; swapping in Termux's
  `TerminalView`/`TerminalEmulator` libraries would fix this
- **KVM devices:** untested on a device that actually has `/dev/kvm`
  access (e.g. Pixel with pKVM) — should be significantly faster there
