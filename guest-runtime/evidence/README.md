# Guest runtime evidence requirements

No guest build evidence exists yet.

Before `vmlinuz`, `initramfs.cpio.gz`, or `system.img` can become release inputs, this directory must
contain evidence for the exact candidate:

- build manifest conforming to `../schemas/build-manifest-v1.schema.json`;
- complete corresponding source archives and authenticated source manifest;
- exact source, package, toolchain, configuration, and patch locks;
- complete license and copyright notices for shipped and build-only components;
- CycloneDX SBOM matching the extracted initramfs and system image inventory;
- two clean builds in independent directories with byte-identical output trees;
- kernel configuration, initramfs file listing, filesystem/package listing, and guest-daemon hash;
- offline rebuild log proving the build consumes only authenticated cached inputs;
- boot transcript proving exact READY nonce validation and one bounded command;
- graceful shutdown, forced shutdown, corruption, low-storage, and crash/restart results;
- API 26, 34, and 35 device evidence plus a 16 KiB-page device;
- signed-AAB inventory proving the packaged bytes and hashes match this evidence.

A future evidence record must distinguish source-only, emulator, physical-device, signed-artifact,
and Play-delivered results. Passing one category must not be reported as another.
