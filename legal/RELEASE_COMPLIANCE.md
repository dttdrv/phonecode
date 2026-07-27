# Release compliance register

This is an operational release checklist, not legal advice. A release is blocked until every row
marked blocked has evidence attached to the release commit and artifact.

## Current blockers

| Area | Status | Required evidence |
| --- | --- | --- |
| PhoneCode original code | Apache-2.0 published | Preserve the root `LICENSE` file and the Copyright 2026 dttdrv notice in source distributions. Third-party components remain governed by their own licenses. |
| OpenCode adapted material | Notice present | Preserve the complete 2025 opencode MIT notice in the repository and app. Record the exact upstream revision used for future adaptations. |
| PRoot and talloc | Blocked | Remove the unmatched binaries or publish exact source, patches, build scripts, toolchain pin, reproducible outputs, GPL-2.0-or-later and LGPL-3.0-or-later texts, and relinking or modification materials required by the applicable licenses. |
| Alpine rootfs | Blocked | Publish exact corresponding source and build recipes for every installed package, complete notices, and an SBOM beside the binary download. |
| Mermaid | Blocked | The authenticated 10.9.6 package metadata and byte-identical bundled asset are recorded. Prove the exact bundled dependency closure, bind it to resolved versions, and collect complete notices before release. |
| Fonts | Evidence complete | The bundled version 2.305 TTFs match official JetBrains Mono revision `02bb50b082dad9ef8a0f33ac393839202b760223` byte-for-byte. `legal/release/` records immutable source URLs, paths, Git blob IDs, hashes, copyright, and the complete upstream OFL-1.1 text. Upstream did not publish a `v2.305` tag or release archive. |
| Android and JVM graph | Blocked | The locked `releaseRuntimeClasspath` SBOM and coordinate evidence authenticate every resolved external artifact and record upstream Maven POM declarations. Complete copyright notices and license texts still require independent collection and review. |
| QEMU VM payload | Not shipping | Publish QEMU, kernel, initramfs, and linked-library source and changes beside any future binary; preserve the executable-process boundary. |
| Privacy and terms | In progress | Keep `legal/` identical to the in-app assets, publish the public URLs, and make Play Data safety and Apple privacy answers match actual network behavior. |

GNU GPL v2 section 3 requires object-code distribution to be accompanied by complete corresponding
source, a qualifying written offer valid for at least three years, or the narrow noncommercial
pass-through alternative. When binaries are offered for download, equivalent source access from the
same place counts. The source includes build and installation scripts. See the
[GNU GPL v2 text](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html#section3).

[QEMU states that it is GPL version 2](https://www.qemu.org/docs/master/about/license.html), while
individual files may have their own licenses. The final QEMU source package must therefore be built
from the exact release source plus every PhoneCode Android patch, not only a link to an upstream tag.

## Required release outputs

1. A signed AAB or IPA whose complete file inventory is captured after shrinking and packaging.
2. An SPDX 2.3 or CycloneDX SBOM generated from the resolved runtime graph and vendored payload.
3. A notice bundle containing all copyright notices and complete license texts.
4. Exact source archives, local patches, build scripts, toolchain versions, and hashes for every
   copyleft binary delivered by PhoneCode.
5. A source download placed beside the binary release, or a reviewed written offer when that route is
   deliberately chosen and can be honored for its full term.
6. Reproduction logs proving that rebuilt native outputs match the shipped hashes.
7. Play Data safety and Apple privacy declarations reconciled with the manifest, network endpoints,
   report retention, linked folders, model providers, Git hosts, package repositories, and MCP.

`VENDORED_CHECKSUMS` authenticates committed bytes only. It does not establish provenance, license
compliance, source availability, or security.

## Android and JVM dependency evidence

`./gradlew :app:verifyAndroidJvmReleaseEvidence` resolves the strictly locked external
`releaseRuntimeClasspath`, queries each Maven POM, and regenerates
`legal/release/android-jvm-SBOM.cdx.json` plus `legal/release/android-jvm-NOTICES.md`. The verifier
requires an exact coordinate inventory, exact artifact SHA-256 values, complete coordinate coverage
in the notices, a hashed Maven POM ancestry for every inherited declaration, and an explicit evidence
status for every license declaration.

The current 92-component graph has no `NOASSERTION` entries. Eighty-eight declarations are in the
component POM and four are inherited from authenticated parent POMs: Guava ListenableFuture, Commons
Codec, JGit, and SLF4J. Legal files embedded in the resolved JAR/AAR artifacts are included verbatim
for 55 components. The complete Apache-2.0 text is included once and referenced for Apache-family
components. The missing SLF4J text is supplied from the exact upstream `v_1.7.36` commit with both
upstream and normalized local hashes, producing complete license-text coverage for 92 of 92
components.

The verifier now closes on complete license-text inclusion, but 37 artifacts contain no embedded
legal file, so the artifact/POM corpus alone does not establish source-level copyright or NOTICE
completeness. Obtain authenticated upstream legal/source artifacts and independently reconcile all
required copyright and NOTICE material before closing the broader Android/JVM compliance blocker.

## Mermaid package and asset evidence

`./gradlew :app:verifyMermaidReleaseEvidence` verifies the committed package metadata snapshot and
bundled asset against pinned hashes, then requires every generated provenance, declared-dependency,
partial-SBOM, and incomplete-notice file to match deterministic output. The stricter regeneration
path accepts only the authenticated npm tarball:

```sh
legal/generate-mermaid-evidence.py generate \
  /path/to/mermaid-10.9.6.tgz \
  app/src/main/assets/mermaid.min.js \
  legal/mermaid/mermaid-10.9.6-package.json \
  legal/release
```

The tarball must match the pinned npm integrity, SHA-512, SHA-256, and byte size before the generator
reads it. The generator rejects unsafe archive members and requires the shipped asset to be
byte-identical to `package/dist/mermaid.min.js`.

This evidence intentionally contains only Mermaid itself as an exact SBOM component. The package's
20 direct dependency ranges are preserved separately because they do not prove resolved versions,
actual bundled presence, transitive closure, or license obligations. The Mermaid release blocker
remains active until an independently audited exact closure and complete notice bundle replace this
partial evidence.

## JetBrains Mono evidence

`./gradlew :app:verifyJetBrainsMonoReleaseEvidence` verifies the three bundled TTF files against
official immutable JetBrains Mono revision `02bb50b082dad9ef8a0f33ac393839202b760223`. The check
requires the exact file SHA-256 and Git blob SHA-1 values, upstream paths and URLs, embedded version,
unique IDs, PostScript names, copyright, OFL metadata, and the byte-exact upstream `OFL.txt`.

The files identify themselves as version 2.305. JetBrains did not publish that version as a Git tag
or GitHub release, so the provenance deliberately records an unreleased official repository
revision rather than inventing a release identifier.

## Existing-release remediation

Previous PhoneCode releases that contain the unmatched PRoot binaries or Alpine rootfs need review.
For each affected asset, either attach the exact corresponding-source package and complete notices,
or remove the binary asset and publish a corrected replacement. Record which versions were affected,
what was removed or supplied, and when. Do not describe a source package as corresponding source
unless it can rebuild the distributed bytes with the documented patches and toolchain.

## Ko-fi and store payments

| Surface | Decision | Wording and implementation |
| --- | --- | --- |
| GitHub and public website | Allowed outside the store app | `Support PhoneCode on Ko-fi — optional, and it unlocks no app features or services.` Do not call it tax-deductible unless that is legally true. Ko-fi's Stripe processing is external to PhoneCode. |
| Global Google Play app | Do not add an external Ko-fi or Stripe button yet | Google requires Play Billing for payments tied to digital features and forbids steering to other payment methods except defined programs and exceptions. Its explicit donation exception is for tax-exempt donations; ordinary developer support has not been established as that exception. Keep the existing Ko-fi link on GitHub and the web while obtaining written policy guidance or use an approved store-compliant model. |
| Future iOS app | Use a consumable In-App Purchase for a developer tip | Apple explicitly allows IAP to tip a developer. Do not link to Ko-fi or Stripe in the iOS app. The individual-gift exception requires a completely optional gift with 100% going to the receiver and must not unlock digital content; do not assume a processed Ko-fi payment qualifies. |

The controlling references are Google's current
[Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en) and
Apple's current [App Review Guidelines 3.1.1 and 3.2.1](https://developer.apple.com/app-store/review/guidelines/).
Recheck both on every submission because payment rules and regional programs change.
