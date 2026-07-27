#!/usr/bin/env python3
"""Validate PhoneCode guest release evidence with only the Python standard library."""

import hashlib
import json
import re
import sys
from pathlib import Path


SHA256 = re.compile(r"^[0-9a-f]{64}$")
ARTIFACT_NAMES = ("initramfs.cpio.gz", "system.img", "vmlinuz")


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"release evidence: {message}")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"invalid JSON in {path.name}: {error}")


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def exact_keys(value, expected, label: str) -> None:
    if not isinstance(value, dict) or set(value) != set(expected):
        fail(f"{label} has undeclared or missing fields")


def sha256_value(value, label: str) -> str:
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        fail(f"{label} is not a canonical SHA-256")
    return value


def validate_manifest(tree: Path, expected_epoch: int) -> None:
    manifest = load_json(tree / "build-manifest.json")
    exact_keys(
        manifest,
        (
            "architecture",
            "artifacts",
            "build_epoch",
            "packages_lock_sha256",
            "protocol_version",
            "schema",
            "sources_lock_sha256",
            "system_payload",
            "toolchain_lock_sha256",
        ),
        "build manifest",
    )
    if (
        manifest["schema"] != "phonecode-guest-build-manifest-v1"
        or manifest["protocol_version"] != 1
        or manifest["architecture"] != "aarch64"
        or manifest["build_epoch"] != expected_epoch
    ):
        fail("build manifest identity is invalid")

    for field, filename in (
        ("sources_lock_sha256", "SOURCES.lock"),
        ("packages_lock_sha256", "PACKAGES.lock"),
        ("toolchain_lock_sha256", "toolchain.lock"),
    ):
        if sha256_value(manifest[field], field) != digest(tree / filename):
            fail(f"{field} does not match {filename}")

    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or len(artifacts) != len(ARTIFACT_NAMES):
        fail("artifact inventory is not exact")
    for item, name in zip(artifacts, ARTIFACT_NAMES):
        exact_keys(item, ("bytes", "name", "sha256"), f"artifact {name}")
        if item["name"] != name:
            fail("artifact inventory is not exact")
        path = tree / "artifacts" / name
        if (
            not isinstance(item["bytes"], int)
            or isinstance(item["bytes"], bool)
            or item["bytes"] < 1
            or item["bytes"] != path.stat().st_size
            or sha256_value(item["sha256"], f"{name} sha256") != digest(path)
        ):
            fail(f"artifact metadata does not match bytes: {name}")

    payload = manifest["system_payload"]
    exact_keys(payload, ("bytes", "sha256"), "system payload")
    payload_bytes = payload["bytes"]
    carrier_path = tree / "artifacts" / "system.img"
    carrier_bytes = carrier_path.stat().st_size
    if (
        not isinstance(payload_bytes, int)
        or isinstance(payload_bytes, bool)
        or payload_bytes < 1
        or payload_bytes > carrier_bytes
        or carrier_bytes % 512 != 0
    ):
        fail("system carrier or payload length is invalid")
    with carrier_path.open("rb") as carrier:
        payload_digest = hashlib.sha256(carrier.read(payload_bytes)).hexdigest()
        padding = carrier.read()
    if sha256_value(payload["sha256"], "system payload sha256") != payload_digest:
        fail("system payload digest does not match carrier prefix")
    if any(padding):
        fail("system carrier padding is not canonical zero padding")


def package_inventory(path: Path):
    inventory = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or "=" in line and "|" not in line:
            continue
        fields = line.split("|")
        if len(fields) != 4:
            fail("PACKAGES.lock contains a malformed entry")
        inventory.append((fields[0], fields[1]))
    return inventory


def validate_sbom(tree: Path) -> None:
    sbom = load_json(tree / "SBOM.cdx.json")
    exact_keys(sbom, ("bomFormat", "components", "specVersion", "version"), "SBOM")
    if (
        sbom["bomFormat"] != "CycloneDX"
        or sbom["specVersion"] != "1.6"
        or sbom["version"] != 1
    ):
        fail("SBOM identity is invalid")
    components = sbom["components"]
    inventory = package_inventory(tree / "PACKAGES.lock")
    if not isinstance(components, list) or len(components) != len(inventory):
        fail("SBOM component inventory is not exact")
    for component, (name, version) in zip(components, inventory):
        exact_keys(component, ("bom-ref", "name", "type", "version"), f"component {name}")
        if component != {
            "bom-ref": f"pkg:phonecode/{name}@{version}",
            "name": name,
            "type": "library",
            "version": version,
        }:
            fail(f"SBOM component does not match PACKAGES.lock: {name}")


def main(argv) -> None:
    if len(argv) == 4 and argv[1] == "manifest":
        validate_manifest(Path(argv[2]), int(argv[3]))
        return
    if len(argv) == 3 and argv[1] == "sbom":
        validate_sbom(Path(argv[2]))
        return
    fail("usage: validate-release-evidence.py manifest TREE EPOCH | sbom TREE")


if __name__ == "__main__":
    main(sys.argv)
