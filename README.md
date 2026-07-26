# vm-forge

A self-built, trustworthy QEMU VM launcher for Android — a replacement
for untrusted third-party APKs like Kalidroid. Not an Android port of
[Virt-Forge](../virt-forge) (which stays desktop-only); a separate project.

## What's in this skeleton

- `KvmDetector.kt` — checks whether `/dev/kvm` is accessible and tells the
  user plainly whether the VM will run in KVM (fast) or TCG (software
  emulation, slow) mode
- `QemuLauncher.kt` — builds the QEMU command line for path B (standalone
  bundling, not yet implemented); the SSH port is only forwarded to
  `127.0.0.1` (never exposed externally), on a random local port
- `VmService.kt` — foreground service so Android doesn't kill the VM
  process in the background (path B, not yet used)
- `MainActivity.kt` — shows the KVM/TCG status on launch, has Start/Stop
  buttons
- `TermuxVmController.kt` — v0.1: triggers VM start/stop inside Termux via
  the `RUN_COMMAND` intent

## GitHub Actions build

`.github/workflows/build.yml` automatically builds a debug APK on every
push/PR to `main`. Once the build finishes, go to the GitHub Actions tab
and download `vm-forge-debug-apk` (or `vm-forge-release-apk`, which
installs directly without extra setup) from that run's "Artifacts"
section — no local Android Studio/PC setup required.

## v0.1 — usable right now (via Termux RUN_COMMAND)

Instead of bundling QEMU, the vm-forge app triggers the QEMU install
already inside Termux. To set this up:

1. Add this line to `~/.termux/termux.properties` in Termux (create the
   file if it doesn't exist):
   ```
   allow-external-apps=true
   ```
   Then fully close and reopen Termux (force-stop from settings, or
   swipe it away and relaunch — a plain back-press won't reload the
   property).
2. Run `scripts/test-in-termux.sh` and `scripts/make-seed.sh` once to get
   the VM files (qcow2, seed.iso, edk2 firmware) ready in `~/vm-test`
   (skip if already done).
3. Install and open the vm-forge app, tap "Start VM (via Termux)" — the
   first time, it will ask for the RUN_COMMAND permission; allow it.
4. A new Termux session opens where the VM boots (`scripts/run-vm.sh`
   runs, with `~/vm-test` as its working directory).
5. Tap "Stop VM" in the app to kill the running QEMU process
   (`scripts/stop-vm.sh`, runs in the background — no new session
   needed).

That's v0.1 — the VM runs as a Termux process, vm-forge just triggers it
with a button. Path B (bundling QEMU directly into the app, no Termux
dependency, fully standalone) will be built out gradually.

## Why the QEMU binary can't just be copied over

The `qemu-system-aarch64` binary installed via `pkg install` in Termux is
hard-linked to Termux's own prefix (`/data/data/com.termux/files/usr`)
for shared libraries (glib, pixman, etc.). Copying it straight into
`vm-forge` won't work. Two paths:

- **Path A (used to verify things first):** `scripts/test-in-termux.sh` —
  boot-tests on-device using Termux's own QEMU, to confirm the image,
  UEFI, and cloud-init setup all work. No app bundling involved.
- **Path B (the real standalone app):** reconfigure `termux-packages`'
  build scripts with `io.boffin.vmforge`'s own prefix, cross-compile, and
  bundle the result (and all its dependency `.so` files) as
  `app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so`.

## Still to do

1. ✅ ~~Get a QEMU binary~~ → verified via path A (`scripts/test-in-termux.sh`)
2. ✅ ~~Kernel + rootfs~~ → Debian `genericcloud-arm64.qcow2` (downloaded by
   the script) + UEFI firmware — no separate kernel extraction needed
3. ✅ ~~First-boot password~~ → `scripts/make-seed.sh` builds a cloud-init
   seed ISO and shows a random password
4. ✅ ~~Start/stop control~~ → v0.1, via Termux RUN_COMMAND
5. **Terminal UI:** add a terminal view to show the serial console output
   inside the app (could reuse Termux's `TerminalView` library or code
   from the [[ReTerminal]] project)
6. **Implement path B:** clone `termux-packages`, cross-compile QEMU and
   its dependencies with a custom prefix, bundle into `jniLibs`
7. **Verification:** an Android Studio (PC) build is the easiest way to
   verify a first `gradlew assembleDebug` locally
