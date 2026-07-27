# PhoneCode guest runtime v1

Status: **validated fail-closed build contract; no guest artifacts are shipped.**

This directory pins the first guest build contract and the exact authenticated inputs established
so far. It deliberately does not produce release bytes until the matching signed kernel/module
package and complete corresponding-source cache are retained. The Android launcher has dedicated
read-only descriptors for all three guest artifacts and a virtio-serial port for the protocol. The
current contents are not sufficient for a Google Play release and must not be copied into
`app/src/release/assets/vm`.

The eventual build has exactly three outputs:

- `vmlinuz`: AArch64 Linux kernel for QEMU `virt`.
- `initramfs.cpio.gz`: minimal early userspace containing the trusted `phonecode-guestd`.
- `system.img`: immutable base system disk.

The host/guest command protocol is pinned in [`protocol-v1.md`](protocol-v1.md) and
[`schemas/protocol-v1.schema.json`](schemas/protocol-v1.schema.json). Build evidence must conform to
[`schemas/build-manifest-v1.schema.json`](schemas/build-manifest-v1.schema.json).

## Contract validation

```sh
./guest-runtime/build-guest.sh --check
```

This validates lock syntax, HTTPS and SHA-256 authentication, the exact package allowlist, source
manifest coverage, canonical initramfs inventory, deterministic mtimes, and clean-tree comparison
behavior. Invoking the script without `--check` fails closed and names the first retained-input
blocker. The contract regression suite is:

```sh
./guest-runtime/tests/guest-build-test.sh
```

The test includes positive fixtures and proves that unresolved locks, unauthenticated inputs,
unexpected packages or initramfs entries, missing licenses or source manifests, nondeterministic
metadata, and divergent clean builds are rejected.

## Runtime architecture

The immutable base payload is the exact signed Alpine 3.24.1 AArch64 minirootfs tarball. `system.img`
is a deterministic 4,023,808-byte carrier: the 4,023,732-byte upstream payload followed by 76 zero
bytes so virtio-blk can expose the complete image in 512-byte sectors. `/init` separately verifies
the carrier size/hash and the upstream payload size/hash before extracting the payload into bounded
tmpfs. It is not a mountable filesystem image. `/workspace` is explicitly ephemeral for this
tranche. Persistent project storage belongs to the separate descriptor and lifecycle work.

## Release boundary

The release gate remains authoritative. Completion requires reproducible, licensed bytes plus
authenticated host-project workspace transport into the integrated isolated-VM backend, device
lifecycle evidence, and signed-AAB inspection. A valid schema or skeleton check is not runtime or
release evidence.
