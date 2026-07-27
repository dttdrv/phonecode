# On-device development backend

PhoneCode will ship one global Google Play application and execute development work on the phone. It
will not provide, require, or fall back to remote execution.

## Release boundary

Google Play prohibits downloading executable code outside Play unless that code runs inside a real
virtual machine or interpreter. PRoot translates Linux paths and syscalls but executes guest ELF
code natively under PhoneCode's Android UID. It is neither a VM nor a security boundary.

The PRoot and Alpine environment is therefore a debug-only development prototype, not the Google
Play release architecture. Its root filesystem and native binaries live only in the Android debug
source set, and a release-input assertion rejects either payload from the `main` or `release` source
sets. Arbitrary `apk`, native `pip` wheels, native npm modules, downloaded ELF files, and shared
libraries must not ship as a claimed Play-safe capability. A release cannot rely on
foreground-service classification, disclosure, or user consent to bypass this rule.

## Target runtime

The production runtime is a software-emulated QEMU system VM using TCG. QEMU, the Linux kernel,
initramfs, trusted guest daemon, and base image are delivered in the signed app and updated only
through Google Play. Downloaded Linux packages execute only on QEMU's virtual CPU and receive no
Android API bridge.

The initial Play release supports `arm64-v8a`, matching the reproducibly built and device-tested QEMU
host runtime. `x86_64` remains a later architecture and must not be advertised until its own host
runtime, guest, 16 KiB alignment, legal inventory, and device lifecycle evidence pass. Android
Virtualization Framework is not available to ordinary third-party apps, so it cannot be the primary
or fallback backend. WASM Linux remains a research option, not a production dependency.

The app's `bash` and `process` tools now depend on a runtime-neutral `ShellBackend`. Debug builds
select the existing PRoot/Alpine backend. Release builds select an explicit unavailable backend
until the isolated VM has a packaged guest, command protocol, coherent project storage, and tested
lifecycle. This boundary prevents a release build from silently falling back to PRoot or an Android
host shell; it is integration scaffolding, not proof that the production VM is ready.

## Isolation

QEMU runs in an Android isolated-process service with no app permissions. The main process passes
only explicit file descriptors. The first implemented boundary carries the kernel, initramfs,
immutable system image, console, and one virtio-serial guest-control channel. Project disks,
brokered networking, and any separate QMP channel remain future reviewed additions. The VM receives
no Android path and cannot open PhoneCode's credentials, sessions, provider configuration, or
unrelated projects.

The main process owns provider and Git credentials. Any credential request from the guest is
host-validated, scoped to the configured origin, short-lived, and never persisted in the guest.
Guest networking is brokered by the main process. Private, loopback, link-local, multicast,
carrier-grade NAT, and IPv6 ULA destinations are denied by default. User-started preview servers are
exposed only through app-local loopback ports unless a later reviewed feature states otherwise.

## Persistent state

The runtime uses three storage layers:

- An immutable, hash-verified Alpine base delivered only through the signed app.
- A per-project disk for `/workspace`, Git data, packages, dependencies, build output, and project
  Skills.
- Android-owned state for chats, provider credentials, permissions, Storage Access Framework grants,
  project metadata, and process history.

There is no mutable global package layer in the first release. This avoids cross-project state,
serialization, and recovery ambiguity while keeping the base reproducible. A damaged base can be
restored without deleting project disks.

## Phone folders

Android document-tree URIs do not provide POSIX or Git filesystem semantics and are not mounted
directly. A selected folder is imported into the project disk. `.git`, dependencies, virtual
environments, caches, and build output remain private to the project disk.

User-visible files return to the phone folder at explicit checkpoints and through Sync now. Sync
tracks a last-common content hash, uses recoverable temporary and backup writes, and surfaces
conflicts without overwriting either side. Git administrative files are never synchronized
file-by-file. Unlinking asks whether to retain or delete the project disk.

Until that bridge passes destructive-provider, concurrent-edit, low-storage, symlink, and large-tree
tests, linked phone folders remain accessible only through the explicit shared-file tools.

## Lifecycle

Active turns and user-started VM processes use a `specialUse` foreground service with a persistent
notification and Stop action. The service exists only while perceptible work is active. The Stop
action cancels the turn, terminates managed processes, exits QEMU, and releases the wake lock.

The workspace and disks are permanent; the VM is not. Android can still stop work after force stop,
reboot, resource pressure, or permission changes. Restored sessions report interrupted work instead
of claiming that a process survived.

## First vertical slice

1. Reproducibly build QEMU for arm64-v8a with pinned sources and Android NDK 28.2.
2. Package a pinned Alpine kernel and minimal BusyBox initramfs.
3. Boot one virtual CPU with 256 MiB in an isolated service using descriptor-only inputs.
4. Complete protocol-v1 nonce/capability negotiation over virtio-serial, run one shell command, and
   return bounded output.
5. Keep the command alive for two minutes while backgrounded with the screen off.
6. Stop it from the notification and verify that QEMU and the guest process exit.
7. Verify that the isolated process cannot open a credential sentinel in PhoneCode's data directory.
8. Measure boot time, RSS, idle CPU, battery, thermal state, and app size on API 26, 34, and 35.

Package installation, persistent disks, networking, project synchronization, and loopback preview
ports are added only after this slice passes.

## Verified foundation

The checked-in `native-runtime` pipeline builds QEMU 11.0.2 and its exact Android arm64 dependency
closure with NDK 28.2.13676358, API 26, pinned source hashes, offline Python build wheels, Android
portability patches, license texts, unstripped symbols, and a fail-closed ELF auditor. Two clean
builds in different random work directories produced byte-identical complete output trees. The
auditor verifies AArch64 PIE/DSO type, GNU build IDs, exact `DT_NEEDED` entries, `$ORIGIN` runtime
paths, RELRO, immediate binding, non-executable stacks, no text relocations, a denylist of known
API-26-incompatible imports,
and 16 KiB-aligned load segments.

The stripped runtime hashes are:

- `libphonecode_qemu.so` (11,804,864 bytes):
  `433b33b1cf00fe53b35d5d621db5a19c8a801bae9f87a554eb81d9708e9284ec`
- `libglib-2.0.so`: `01ac901adcb7e2e58a054f1a1fcfb7bbe3cd6adc64a9d2eb43e51c823b3fea8d`
- `libiconv.so`: `7ef3f474dc27a94d915c5cee957c9011942edeb8aa44c6e14248c73e6804dbac`
- `libpcre2-8.so`: `e2cdd8d3c925493a58c8e7173130cec581376296f5714d59ae5d2daac7851a31`

That exact QEMU payload booted one virtual CPU with 256 MiB on an API 34 arm64 emulator and emitted
`PHONECODE_VM_BOOT_OK`. The host then sent `SIGTERM` and confirmed that no QEMU process remained.
This proves Android loading, TCG boot, and host termination for the final host bytes; it does not yet
prove graceful guest shutdown or Android background lifecycle behavior. The nonshipping proof kernel
SHA-256 is `47970e0ee0478fe5c60824a89f162d5a353fa29466e5d3bddb0f9c506f1ed756`; the proof initramfs
SHA-256 is `e530da460998be9029223f6c74e9025cac70f1254e2f41d4caa1f1dc2f7fc104`.

The app now contains a private isolated-process service, descriptor-only Binder contract, native
fork/exec and process-group stop shim, and an emulator-tested isolation probe. The probe confirms
that the service UID differs from the app UID, cannot open an app-private sentinel by path, and can
read the same file only when the app delegates a read-only descriptor.

The host runtime output remains a nonshipping release candidate. The Play source set deliberately
contains none of these libraries, and the release gate fails until all four audited files are placed
under `app/src/release/jniLibs/arm64-v8a`. Reproducible, licensed guest kernel, initramfs, and base
image sources; controller-to-service integration; ready/command/graceful-shutdown control; API 26
and 35 coverage; a 16 KiB device; background lifecycle; persistent disks; networking; energy
measurements; x86_64 host support; and final signed AAB inventory remain release blockers.

## Initial resource gates

- Less than 150 MB compressed download per delivered ABI.
- Less than 400 MB installed before user packages.
- 256-512 MiB guest RAM and one virtual CPU by default.
- No more than 768 MiB and two virtual CPUs initially.
- Less than 180 MiB host overhead beyond guest RAM.
- Less than 1% idle CPU.
- Cold boot under 15 seconds on a flagship and 30 seconds on a midrange device.
- A 2 GiB sparse system-disk quota and 4 GiB sparse per-project default with an 80% warning.

## Licensing gate

Before distribution, PhoneCode publishes the complete license texts, corresponding sources,
Android portability patches, reproducible build scripts, NDK version, source offer, SBOM, and hashes
for QEMU, the Linux kernel, BusyBox, apk-tools, and every linked library. QEMU remains a separate
executable and process rather than a JNI library.
