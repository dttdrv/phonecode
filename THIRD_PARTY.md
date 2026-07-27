# Third-party software and attribution

This inventory covers the current source tree and development artifact. PhoneCode's original code
is licensed under Apache License 2.0 as stated in the root `LICENSE` file. Third-party components
remain governed by their own licenses, and OpenCode's MIT license applies only to the adapted
OpenCode material.

## OpenCode adapted material

PhoneCode's prompt structure, tool schemas, loop design, and several tools are adapted from
[OpenCode](https://github.com/anomalyco/opencode). PhoneCode is independent and is not built by,
endorsed by, or affiliated with the OpenCode team or Anomaly. OpenCode, OpenCode Zen, and OpenCode Go
are used only to describe origins and interoperability.

    MIT License

    Copyright (c) 2025 opencode

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

## Android and JVM runtime

The release runtime graph includes the following license families. Test-only dependencies are not
shipped in the app and must be inventoried separately if test artifacts are distributed.

| Components | License |
| --- | --- |
| AndroidX, Jetpack Compose, Material icons, Navigation, Tink, Gson, JSpecify, Guava ListenableFuture | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization, AtomicFU | Apache-2.0 |
| OkHttp and Okio | Apache-2.0 |
| Haze | Apache-2.0 |
| Eclipse JGit | Eclipse Distribution License 1.0 |
| JavaEWAH and Apache Commons Codec | Apache-2.0 |
| SLF4J | MIT |

The resolved release graph must be exported into the release SBOM and checked against this table for
every release. A dependency-family summary is not a substitute for the final component-level SBOM
and license bundle.

## Mermaid 10.9.6 bundle

- File: `app/src/main/assets/mermaid.min.js`
- SHA-256: `eda3a0ad572bbe69a318c1be0163e8233dd824f3f12939e5168feba207767151`
- Exact origin: `mermaid@10.9.6`, `dist/mermaid.min.js`, npm package integrity
  `sha512-XRjjRaI4aPCAMpVaOhxIwLYdx3U4Cb6mN0M268ggFAfFRqsvyFW8zxWbEZazN/mPkqsVWThb0oa1UawWK+XMNg==`
- Mermaid license: MIT, Copyright (c) 2014-2022 Knut Sveidqvist

The committed bytes match the published npm package exactly. The single-file build embeds Mermaid's
runtime dependencies, including sanitize-url, Cytoscape, D3, dagre-d3-es, Day.js, DOMPurify, ELK,
KaTeX, khroma, Lodash, mdast utilities, Stylis, UUID, and related transitive code. Their exact resolved
versions and notices are not present in this repository. This pinned backport includes the Mermaid
11.15.0 security fixes for CVE-2026-41148, CVE-2026-41149, CVE-2026-41150, and CVE-2026-41159.
`legal/release/mermaid-PROVENANCE.json` authenticates the package and bundled bytes, while
`legal/release/mermaid-declared-dependencies.json`, the partial SBOM, and the incomplete notice
evidence explicitly preserve the unresolved boundary. Derive the exact bundled dependency closure
and publish its complete third-party-notice inventory before release.

## Fonts

| File | SHA-256 | License |
| --- | --- | --- |
| `jetbrainsmono_bold.ttf` | `d22c4f3821d725eb01210d278d95dfcfcaadc34699a06658d47c8a5cc5830ada` | SIL Open Font License 1.1, Copyright 2020 The JetBrains Mono Project Authors |
| `jetbrainsmono_medium.ttf` | `d16e6dc99672734698d629705f617c79f6eb6040f5113efe3a145204dc988109` | SIL Open Font License 1.1, Copyright 2020 The JetBrains Mono Project Authors |
| `jetbrainsmono_regular.ttf` | `e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1` | SIL Open Font License 1.1, Copyright 2020 The JetBrains Mono Project Authors |

All three files embed version `2.305` and match the official JetBrains Mono repository at immutable
revision [`02bb50b082dad9ef8a0f33ac393839202b760223`](https://github.com/JetBrains/JetBrainsMono/commit/02bb50b082dad9ef8a0f33ac393839202b760223)
byte-for-byte. JetBrains did not publish a `v2.305` tag or release archive; the provenance therefore
records the exact repository revision, upstream paths, Git blob IDs, and SHA-256 values without
claiming a release tag. The complete upstream `OFL.txt` and machine-readable provenance are in
`legal/release/`. Run `./gradlew :app:verifyJetBrainsMonoReleaseEvidence` to authenticate them
against the bundled TTF metadata and bytes.

## Alpine 3.21.7 development root filesystem

- Debug-only file: `app/src/debug/assets/alpine-aarch64.rootfs`
- SHA-256: `d1d1a3fae5f4d6146e9742790a47fcb116199622cfb8439f218a4d5fbe5000da`
- Exact binary origin:
  `https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.7-aarch64.tar.gz`

The root filesystem's installed-package database records:

| Package | Version | Source package | Declared license |
| --- | --- | --- | --- |
| alpine-baselayout, alpine-baselayout-data | 3.6.8-r1 | alpine-baselayout | GPL-2.0-only |
| alpine-keys | 2.5-r0 | alpine-keys | MIT |
| alpine-release | 3.21.7-r0 | alpine-base | MIT |
| apk-tools | 2.14.6-r3 | apk-tools | GPL-2.0-only |
| busybox, busybox-binsh, ssl_client | 1.37.0-r14 | busybox | GPL-2.0-only |
| ca-certificates-bundle | 20260413-r0 | ca-certificates | MPL-2.0 AND MIT |
| libcrypto3, libssl3 | 3.3.7-r0 | openssl | Apache-2.0 |
| musl | 1.2.5-r11 | musl | MIT |
| musl-utils | 1.2.5-r11 | musl | MIT AND BSD-2-Clause AND GPL-2.0-or-later |
| scanelf | 1.3.8-r1 | pax-utils | GPL-2.0-only |
| zlib | 1.3.2-r0 | zlib | Zlib |

The minirootfs does not include complete corresponding source or the complete license bundle for
these packages. An upstream binary URL alone does not satisfy PhoneCode's obligations as a binary
redistributor. Do not distribute this rootfs until the exact source archives, Alpine build recipes
and patches, license texts, and a machine-readable SBOM are published beside the artifact.

## PRoot development prototype

- Debug-only files: `app/src/debug/jniLibs/arm64-v8a/libproot.so` and `libproot-loader.so`
- Licenses represented in the binaries: PRoot GPL-2.0-or-later and talloc LGPL-3.0-or-later
- Detailed audit: `app/src/debug/jniLibs/PROVENANCE.md`

The committed hashes do not match the formerly cited upstream build archive. Complete corresponding
source and reproducible build inputs are missing. These binaries are not distributable until they
are removed or replaced with a fully traceable build.

## Planned VM runtime

QEMU, the Linux kernel, the initramfs, and their linked dependency closure are not currently packaged
in the app. Their proof-of-concept files in `/tmp` are not release artifacts. Before adding any of
them, publish complete corresponding source, all Android changes, reproducible build scripts,
toolchain versions, license texts, an SPDX or CycloneDX SBOM, and final artifact hashes. QEMU must
remain a separate executable process rather than being linked into PhoneCode.

## Release status

The current release gate remains closed. The exact blockers and remediation procedure are in
`legal/RELEASE_COMPLIANCE.md`.
