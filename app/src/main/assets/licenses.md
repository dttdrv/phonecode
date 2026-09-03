# Open-source notices

PhoneCode includes and adapts third-party software. Each component remains governed by its own
license. PhoneCode is independent from the projects listed here.

PhoneCode's original code is Copyright 2026 dttdrv and licensed under the Apache License 2.0. The
complete license text is published in the repository's root `LICENSE` file.

## OpenCode

Adapted prompt structure, tool schemas, agent-loop design, and tools.

MIT License

Copyright (c) 2025 opencode

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Misul Agent

Native agent runtime.

MIT License

Copyright (c) 2026 Misul Computing

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES
OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Mermaid 10.9.6

MIT License. Copyright (c) 2014-2022 Knut Sveidqvist. Mermaid and its embedded dependency notices
are tracked in the repository's `THIRD_PARTY.md` inventory.

## Fonts

- JetBrains Mono: Copyright 2020 The JetBrains Mono Project Authors, SIL Open Font License 1.1.

## Android and JVM libraries

AndroidX, Jetpack Compose, Kotlin, kotlinx, OkHttp, Okio, Haze, Tink, Gson, AtomicFU, JSpecify,
JavaEWAH, Apache Commons Codec, and related components are primarily Apache-2.0. Eclipse JGit uses
the Eclipse Distribution License 1.0. SLF4J uses MIT.

## Development Linux prototype

The development build contains an Alpine 3.21.7 root filesystem with independently licensed
packages, including GPL-2.0-only BusyBox, apk-tools, alpine-baselayout, and pax-utils components. It
also contains PRoot under GPL-2.0-or-later and talloc under LGPL-3.0-or-later. These prototype
artifacts are blocked from release until complete corresponding source and notices are available.

## Play VM runtime

The planned Play runtime uses QEMU as a separate executable process with GLib, libiconv, PCRE2,
libffi, device-tree compiler code, and proxy-libintl components under their respective GPL, LGPL,
BSD, and permissive licenses. Its guest includes the Linux kernel, BusyBox, and other independently
licensed packages. The release gate requires the complete host and guest license texts, corresponding
source manifests, SBOMs, and exact hashes before any of those files may enter a release candidate.

Complete component hashes, source status, copyright notices, and release blockers are published in
`THIRD_PARTY.md` and `legal/RELEASE_COMPLIANCE.md` at github.com/dttdrv/phonecode.
