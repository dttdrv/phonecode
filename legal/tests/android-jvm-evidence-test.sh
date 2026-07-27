#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TEMP=$(mktemp -d "${TMPDIR:-/tmp}/phonecode-android-jvm-evidence-test.XXXXXX")
trap 'rm -rf "$TEMP"' EXIT

printf 'alpha artifact\n' > "$TEMP/alpha.jar"
printf 'slf4j artifact\n' > "$TEMP/slf4j-api-1.7.36.jar"
for artifact in \
  atomicfu-jvm-0.23.2 \
  kotlinx-coroutines-android-1.9.0 \
  kotlinx-coroutines-core-jvm-1.9.0 \
  kotlinx-serialization-core-jvm-1.7.3 \
  kotlinx-serialization-json-jvm-1.7.3 \
  kotlin-stdlib-2.3.21
do
  printf '%s artifact\n' "$artifact" > "$TEMP/$artifact.jar"
done
mkdir -p \
  "$TEMP/modules-2/files-2.1/example.beta/beta/2.0/child-hash" \
  "$TEMP/modules-2/files-2.1/example.parent/beta-parent/1.0/parent-hash"
cat > "$TEMP/alpha.pom" <<'XML'
<project>
  <name>Alpha library</name>
  <url>https://example.invalid/alpha</url>
  <licenses>
    <license>
      <name>Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
</project>
XML
cat > "$TEMP/slf4j-api-1.7.36.pom" <<'XML'
<project>
  <name>SLF4J API Module</name>
  <url>https://www.slf4j.org</url>
  <licenses>
    <license>
      <name>MIT License</name>
      <url>https://opensource.org/license/mit</url>
    </license>
  </licenses>
</project>
XML
for pom in \
  atomicfu-jvm-0.23.2 \
  kotlinx-coroutines-android-1.9.0 \
  kotlinx-coroutines-core-jvm-1.9.0 \
  kotlinx-serialization-core-jvm-1.7.3 \
  kotlinx-serialization-json-jvm-1.7.3 \
  kotlin-stdlib-2.3.21
do
  cat > "$TEMP/$pom.pom" <<'XML'
<project>
  <name>Kotlin library</name>
  <url>https://github.com/JetBrains/kotlin</url>
  <licenses>
    <license>
      <name>Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
</project>
XML
done
cat > "$TEMP/modules-2/files-2.1/example.beta/beta/2.0/child-hash/beta-2.0.pom" <<'XML'
<project>
  <parent>
    <groupId>example.parent</groupId>
    <artifactId>beta-parent</artifactId>
    <version>1.0</version>
  </parent>
  <name>Beta library</name>
</project>
XML
cat > "$TEMP/modules-2/files-2.1/example.parent/beta-parent/1.0/parent-hash/beta-parent-1.0.pom" <<'XML'
<project>
  <groupId>example.parent</groupId>
  <artifactId>beta-parent</artifactId>
  <version>1.0</version>
  <licenses>
    <license>
      <name>MIT License</name>
      <url>https://opensource.org/license/mit</url>
    </license>
  </licenses>
</project>
XML

python3 - "$TEMP/inputs.json" "$TEMP" <<'PY'
import json
from pathlib import Path
import sys
import zipfile

root = Path(sys.argv[2])
with zipfile.ZipFile(root / "beta.aar", "w") as archive:
    archive.writestr("META-INF/LICENSE.txt", "Beta complete license text\n")
    archive.writestr("META-INF/NOTICE.txt", "Copyright 2026 Beta Authors\n")
document = [
    {
        "coordinate": "example.beta:beta:2.0",
        "artifact": str(root / "beta.aar"),
        "pom": str(
            root
            / "modules-2/files-2.1/example.beta/beta/2.0/child-hash/beta-2.0.pom"
        ),
    },
    {
        "coordinate": "example.alpha:alpha:1.0",
        "artifact": str(root / "alpha.jar"),
        "pom": str(root / "alpha.pom"),
    },
    {
        "coordinate": "org.slf4j:slf4j-api:1.7.36",
        "artifact": str(root / "slf4j-api-1.7.36.jar"),
        "pom": str(root / "slf4j-api-1.7.36.pom"),
    },
]
document.extend(
    [
    {
        "coordinate": coordinate,
        "artifact": str(root / f"{artifact}.jar"),
        "pom": str(root / f"{artifact}.pom"),
    }
    for coordinate, artifact in [
        ("org.jetbrains.kotlinx:atomicfu-jvm:0.23.2", "atomicfu-jvm-0.23.2"),
        (
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0",
            "kotlinx-coroutines-android-1.9.0",
        ),
        (
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0",
            "kotlinx-coroutines-core-jvm-1.9.0",
        ),
        (
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3",
            "kotlinx-serialization-core-jvm-1.7.3",
        ),
        (
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3",
            "kotlinx-serialization-json-jvm-1.7.3",
        ),
        ("org.jetbrains.kotlin:kotlin-stdlib:2.3.21", "kotlin-stdlib-2.3.21"),
    ]
    ]
)
Path(sys.argv[1]).write_text(json.dumps(document), encoding="utf-8")
PY

# Required supplements must fail closed when files are missing or altered.
mkdir -p "$TEMP/missing/legal/upstream" "$TEMP/tampered/legal/upstream"
cp "$ROOT/LICENSE" "$TEMP/missing/LICENSE"
cp "$ROOT/LICENSE" "$TEMP/tampered/LICENSE"
cp "$ROOT/legal/generate-android-jvm-evidence.py" "$TEMP/missing/legal/"
cp "$ROOT/legal/generate-android-jvm-evidence.py" "$TEMP/tampered/legal/"
cp "$ROOT/legal/upstream/"* "$TEMP/missing/legal/upstream/"
cp "$ROOT/legal/upstream/"* "$TEMP/tampered/legal/upstream/"
rm -f "$TEMP/missing/legal/upstream/kotlinx-atomicfu-0.23.2-NOTICE.txt"
printf '\ntampered\n' >> "$TEMP/tampered/legal/upstream/kotlinx-atomicfu-0.23.2-NOTICE.txt"
if "$TEMP/missing/legal/generate-android-jvm-evidence.py" \
  "$TEMP/inputs.json" "$TEMP/missing-output" 2>"$TEMP/missing-error"
then
  printf 'android JVM evidence test: missing required supplement unexpectedly passed\n' >&2
  exit 1
fi
grep -q "authenticated legal supplement is missing" "$TEMP/missing-error"
if "$TEMP/tampered/legal/generate-android-jvm-evidence.py" \
  "$TEMP/inputs.json" "$TEMP/tampered-output" 2>"$TEMP/tampered-error"
then
  printf 'android JVM evidence test: tampered required supplement unexpectedly passed\n' >&2
  exit 1
fi
grep -q "authenticated legal supplement hash mismatch" "$TEMP/tampered-error"

"$ROOT/legal/generate-android-jvm-evidence.py" "$TEMP/inputs.json" "$TEMP/first"
"$ROOT/legal/generate-android-jvm-evidence.py" "$TEMP/inputs.json" "$TEMP/second"

cmp "$TEMP/first/android-jvm-SBOM.cdx.json" "$TEMP/second/android-jvm-SBOM.cdx.json"
cmp "$TEMP/first/android-jvm-NOTICES.md" "$TEMP/second/android-jvm-NOTICES.md"

python3 - "$TEMP/first/android-jvm-SBOM.cdx.json" "$TEMP/first/android-jvm-NOTICES.md" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

sbom = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
notices = Path(sys.argv[2]).read_text(encoding="utf-8")
assert sbom["bomFormat"] == "CycloneDX"
assert sbom["specVersion"] == "1.6"
components = sbom["components"]
assert [item["group"] + ":" + item["name"] + ":" + item["version"] for item in components] == [
    "example.alpha:alpha:1.0",
    "example.beta:beta:2.0",
    "org.jetbrains.kotlin:kotlin-stdlib:2.3.21",
    "org.jetbrains.kotlinx:atomicfu-jvm:0.23.2",
    "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0",
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3",
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3",
    "org.slf4j:slf4j-api:1.7.36",
]
alpha, beta = components[:2]
kotlin_stdlib = components[2]
atomicfu = components[3]
coroutines_android = components[4]
coroutines_core = components[5]
serialization_core = components[6]
serialization_json = components[7]
slf4j = components[8]
assert alpha["licenses"][0]["license"]["name"] == "Apache License, Version 2.0"
assert beta["licenses"][0]["license"]["name"] == "MIT License"
assert slf4j["licenses"][0]["license"]["name"] == "MIT License"
assert any(
    item["name"] == "phonecode:license-evidence" and item["value"] == "upstream-pom-declaration"
    for item in alpha["properties"]
)
assert any(
    item["name"] == "phonecode:license-evidence"
    and item["value"] == "inherited-upstream-pom-declaration"
    for item in beta["properties"]
)
assert any(
    item["name"] == "phonecode:pom-evidence-chain"
    and "example.parent:beta-parent:1.0@" in item["value"]
    for item in beta["properties"]
)
assert any(
    item["name"] == "phonecode:embedded-legal-files-included"
    and item["value"] == "2"
    for item in beta["properties"]
)
assert any(
    item["name"] == "phonecode:complete-license-text-included"
    and item["value"] == "true"
    for item in alpha["properties"]
)
assert any(
    item["name"] == "phonecode:complete-license-text-included"
    and item["value"] == "true"
    for item in beta["properties"]
)
assert any(
    item["name"] == "phonecode:complete-license-text-included"
    and item["value"] == "true"
    for item in slf4j["properties"]
)
for component in (
    atomicfu,
    coroutines_android,
    coroutines_core,
    serialization_core,
    serialization_json,
):
    assert any(
        item["name"] == "phonecode:notice-status" and item["value"] == "included"
        for item in component["properties"]
    )
    assert any(
        item["name"] == "phonecode:supplemental-legal-files-included"
        and item["value"] == "1"
        for item in component["properties"]
    )
assert any(
    item["name"] == "phonecode:third-party-license-expression"
    and item["value"] == "BSD-3-Clause"
    for item in kotlin_stdlib["properties"]
)
assert any(
    item["name"] == "phonecode:notice-status" and item["value"] == "included"
    for item in kotlin_stdlib["properties"]
)
assert alpha["hashes"][0]["content"] == hashlib.sha256(b"alpha artifact\n").hexdigest()
assert "example.alpha:alpha:1.0" in notices
assert "example.beta:beta:2.0" in notices
assert "org.slf4j:slf4j-api:1.7.36" in notices
assert "does not establish complete copyright or license-text coverage" in notices
assert "Upstream license declarations resolved: **9/9**" in notices
assert "Artifacts with embedded legal files included: **1/9**" in notices
assert "Components with a complete license text included: **9/9**" in notices
assert "Source: `LICENSE`" in notices
assert "Apache License" in notices
assert "Beta complete license text" in notices
assert "Copyright 2026 Beta Authors" in notices
assert "Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)" in notices
assert "kotlinx.coroutines library." in notices
assert "kotlinx.serialization library." in notices
assert "kotlinx.atoimcfu library." in notices
assert "Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos" in notices
assert "Redistributions in binary form must reproduce the above copyright notice" in notices
assert "NOASSERTION" not in notices
PY

printf 'android JVM evidence test: PASS\n'
