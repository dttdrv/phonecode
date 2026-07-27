#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TEMP=$(mktemp -d "${TMPDIR:-/tmp}/phonecode-mermaid-evidence-test.XXXXXX")
trap 'rm -rf "$TEMP"' EXIT

GENERATOR="$ROOT/legal/generate-mermaid-evidence.py"
ASSET="$ROOT/app/src/main/assets/mermaid.min.js"
PACKAGE_SNAPSHOT="$ROOT/legal/mermaid/mermaid-10.9.6-package.json"
EVIDENCE="$ROOT/legal/release"

"$GENERATOR" verify "$ASSET" "$PACKAGE_SNAPSHOT" "$EVIDENCE"

python3 - "$EVIDENCE" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
provenance = json.loads((root / "mermaid-PROVENANCE.json").read_text(encoding="utf-8"))
declared = json.loads(
    (root / "mermaid-declared-dependencies.json").read_text(encoding="utf-8")
)
sbom = json.loads((root / "mermaid-SBOM.cdx.json").read_text(encoding="utf-8"))
notices = (root / "mermaid-NOTICES.md").read_text(encoding="utf-8")

scope = provenance["evidenceScope"]
assert scope["authenticatedPackageArtifact"] is True
assert scope["bundledAssetByteIdenticalToPackageMember"] is True
assert scope["resolvedDependencyVersions"] is False
assert scope["bundledDependencyClosure"] is False
assert scope["completeThirdPartyNotices"] is False

assert declared["dependencyKind"] == "declared-runtime-dependency-ranges"
assert declared["resolvedVersions"] is False
assert declared["completeBundledDependencyClosure"] is False
assert declared["dependencies"] == sorted(
    declared["dependencies"], key=lambda item: item["name"]
)
assert len(declared["dependencies"]) == 20
assert any(
    item == {"name": "dagre-d3-es", "declaredRange": "7.0.13"}
    for item in declared["dependencies"]
)

assert sbom["bomFormat"] == "CycloneDX"
assert sbom["specVersion"] == "1.6"
assert len(sbom["components"]) == 1
assert sbom["components"][0]["name"] == "mermaid"
assert sbom["components"][0]["version"] == "10.9.6"
properties = {
    item["name"]: item["value"] for item in sbom["metadata"]["properties"]
}
assert properties["phonecode:resolved-dependency-closure-complete"] == "false"
assert properties["phonecode:third-party-notices-complete"] == "false"

assert "INCOMPLETE THIRD-PARTY NOTICE EVIDENCE" in notices
assert "does not prove resolved dependency versions" in notices
assert "does not clear the Mermaid release blocker" in notices
PY

cp -R "$EVIDENCE" "$TEMP/evidence"
python3 - "$TEMP/evidence/mermaid-PROVENANCE.json" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
document = json.loads(path.read_text(encoding="utf-8"))
document["evidenceScope"]["completeThirdPartyNotices"] = True
path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

if "$GENERATOR" verify "$ASSET" "$PACKAGE_SNAPSHOT" "$TEMP/evidence" >"$TEMP/tamper.log" 2>&1; then
    echo "mermaid evidence test: tampered evidence unexpectedly passed" >&2
    exit 1
fi
grep -q "does not match deterministic output" "$TEMP/tamper.log"

cp "$ASSET" "$TEMP/mermaid.min.js"
printf '\n' >> "$TEMP/mermaid.min.js"
if "$GENERATOR" verify "$TEMP/mermaid.min.js" "$PACKAGE_SNAPSHOT" "$EVIDENCE" >"$TEMP/asset.log" 2>&1; then
    echo "mermaid evidence test: tampered asset unexpectedly passed" >&2
    exit 1
fi
grep -q "bundled asset SHA-256 mismatch" "$TEMP/asset.log"

printf 'mermaid evidence test: PASS\n'
