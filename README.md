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
  import buttons for the disk image + cloud-init seed, and the
  "Open Proot Forge Terminal" entrance to the integrated proot-forge core
- `TerminalActivity.kt` — a minimal interactive console for the VM's
  serial output (ANSI codes stripped for readability; not a full
  VT100 emulator — good for shell use, not for full-screen apps like
  `top` or `vim`; the Proot Forge terminal is the full-featured one)
- `core/main`, `core/proot`, `core/components`, `core/resources` — the
  complete proot-forge core: Compose terminal, SessionService, init
  scripts, and the native PRoot runtime (`libproot.so`/`libloader.so`
  built from C source by the `:core:proot` CMake project), including the
  Boffin rootfs-URL download installer

## GitHub Actions build

`.github/workflows/build.yml` automatically builds debug and release APKs
on every push/PR to `main` (via the checked-in Gradle wrapper — the AGP
9.2.1 / Kotlin 2.3.20 toolchain used by the proot core, so no local
Android Studio/PC setup is required). Once the build
finishes, go to the GitHub Actions tab and download
`vm-forge-release-apk` (installs directly, no extra setup) from that
run's "Artifacts" section.

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
5. Also copy `edk2-x86_64-code.fd` **and** `edk2-x86_64-vars.fd` (both
   from `$PREFIX/share/qemu/`) into `app/src/main/assets/qemu-libs/`,
   alongside the existing `edk2-aarch64-code.fd`. The `VARS` file is
   mandatory — see below.
6. Get an x86_64 Debian cloud image (same idea as
   `debian-13-genericcloud-arm64.qcow2` but the `-amd64.qcow2` variant
   from the same `cloud.debian.org` path) and import it the same way

**x86_64 = q35 + OVMF pflash (fixed & verified):** unlike the ARM64
`virt` machine (which loads its full EDK2 image via `-bios`), the q35
machine **refuses an OVMF `CODE` image via `-bios`** — it hard-exits
with `could not load PC BIOS` because q35's `-bios` slot expects a 2MB
image and OVMF CODE is 3.6MB. The launcher therefore gives q35 two
pflash drives instead:

- `-drive if=pflash,format=raw,unit=0,file=edk2-x86_64-code.fd,readonly=on`
- `-drive if=pflash,format=raw,unit=1,file=edk2-x86_64-vars.fd`
  (writable, so UEFI NVRAM now persists across boots)

plus `-vga none` (q35 creates a default std VGA whose `vgabios-stdvga.bin`
lives in QEMU's Termux data dir — absent on-device without Termux) and a
`virtio-net-pci` NIC with `romfile=` (q35 has no `virtio-bus`, and
`virtio-net-device` dies with `No 'virtio-bus' bus found`; the empty
`romfile=` also skips the `efi-virtio.rom` that would otherwise be looked
up in that same missing data dir). Boot-tested: OVMF → GRUB → Debian
cloud amd64 kernel comes all the way up.

## Proot Forge (integrated PRoot/Boffin subsystem)

Alongside the QEMU VM path, the app ships the **full proot-forge core**
(from the `proot-forge` project, as `:core:main` + `:core:proot` +
`:core:components` + `:core:resources` Gradle modules) as its PRoot
container subsystem. This replaces the app's original hand-rolled
PRoot container (`PRootLauncher`/`ProotService`) entirely:

- **Compose terminal** (`TerminalView`/`TerminalEmulator` from Termux) —
  a real ANSI/color/screen-redraw terminal, unlike the VM's line-based
  `TerminalActivity`
- **Session service** — `SessionService` keeps each container session
  running in the foreground; multiple sessions, custom sessions, rename,
  sort, session switching
- **Init scripts** — `init-host.sh`/`init.sh`/`rm-wrapper.sh`,
  auto-extracted from assets into `<filesDir-parent>/local/bin` on every
  app start (`UpdateManager`)
- **PRoot runtime with libloader** — built from C source by `:core:proot`
  (`libproot.so` + `libloader.so`, custom ARM64 build, `--link2symlink`,
  seccomp, etc.), replaced the previous bundled `libproot.so`
- **Boffin rootfs URL install** — tap **Add Session → Boffin** inside the
  terminal to enter a direct `.tar.gz` download URL (manifest-free, no
  file picker); the archive streams to `filesDir/boffin.tar.gz` and is
  extracted by `init-host.sh`
- **Android** (host shell) session mode and user-defined **custom
  sessions** also work, plus a Settings/Customization drawer

All container state lives in `<filesDir-parent>/local/`. With this app's
`applicationId = io.boffin.vmforge`, that resolves to
`/data/user/0/io.boffin.vmforge/local/` (init scripts use `$PREFIX` =
`filesDir.parentFile` and `$PKG` = package name, so there are no
hardcoded `/data/data/io.boffin.proot/...` paths left anywhere).

Build-wise, the proot core requires the newer AGP/Kotlin toolchain that
`proot-forge` already used (AGP 9.2.1, Kotlin 2.3.20, Gradle 9.4.1 via
the checked-in wrapper and `gradle/libs.versions.toml` version catalog).

### Setup

1. Open **"Open Proot Forge Terminal"** on the main screen
2. Tap the menu → **Add Session → Boffin**, paste the rootfs `.tar.gz`
   URL, and **Download**
3. The download streams to `filesDir/boffin.tar.gz`, extracts to
   `local/boffin/root`, and drops you into the container shell — no
   storage permissions or external tools needed

## Still to do

- **In-app VM setup:** the disk image + seed still need to be prepared
  externally (Termux) and imported by hand; a fully in-app
  download/provisioning flow would remove that step
- **Boffin rootfs URL:** the direct-URL installer is in (from
  proot-forge) — previously the app also shipped an official Debian
  (bookworm) rootfs downloader, which has been removed
- **KVM devices:** untested on a device that actually has `/dev/kvm`
  access (e.g. Pixel with pKVM) — should be significantly faster there
  for ARM64 guests (never applies to x86_64 guests, see above)
