#!/usr/bin/env python3
"""Verify the exact JetBrains Mono files bundled in the Android app."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path
from typing import Any


UPSTREAM_REPOSITORY = "https://github.com/JetBrains/JetBrainsMono"
SOURCE_REVISION = "02bb50b082dad9ef8a0f33ac393839202b760223"
SOURCE_COMMIT_URL = f"{UPSTREAM_REPOSITORY}/commit/{SOURCE_REVISION}"
COPYRIGHT = (
    "Copyright 2020 The JetBrains Mono Project Authors "
    "(https://github.com/JetBrains/JetBrainsMono)"
)
EMBEDDED_VERSION = "Version 2.305; ttfautohint (v1.8.4.7-5d5b)"
LICENSE_SHA256 = "30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad"
LICENSE_GIT_BLOB_SHA1 = "8bee4148c1d54dbf5dae6d6c117fc80414266abb"

EXPECTED_FONTS = {
    "jetbrainsmono_bold.ttf": {
        "embeddedUniqueId": "2.305;JB;JetBrainsMono-Bold",
        "gitBlobSha1": "cd1bee0704e05a899cd70f0640a801f1afe046b2",
        "postScriptName": "JetBrainsMono-Bold",
        "sha256": "d22c4f3821d725eb01210d278d95dfcfcaadc34699a06658d47c8a5cc5830ada",
        "size": 274096,
        "upstreamPath": "fonts/ttf/JetBrainsMono-Bold.ttf",
    },
    "jetbrainsmono_medium.ttf": {
        "embeddedUniqueId": "2.305;JB;JetBrainsMono-Medium",
        "gitBlobSha1": "dc2e5d08677d603b6755dc6c34fc8ad9aa6fc8a5",
        "postScriptName": "JetBrainsMono-Medium",
        "sha256": "d16e6dc99672734698d629705f617c79f6eb6040f5113efe3a145204dc988109",
        "size": 270316,
        "upstreamPath": "fonts/ttf/JetBrainsMono-Medium.ttf",
    },
    "jetbrainsmono_regular.ttf": {
        "embeddedUniqueId": "2.305;JB;JetBrainsMono-Regular",
        "gitBlobSha1": "711830ede02a366f8b99f88e52f3148405e67eaf",
        "postScriptName": "JetBrainsMono-Regular",
        "sha256": "e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1",
        "size": 270224,
        "upstreamPath": "fonts/ttf/JetBrainsMono-Regular.ttf",
    },
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git_blob_sha1(data: bytes) -> str:
    header = f"blob {len(data)}\0".encode()
    return hashlib.sha1(header + data).hexdigest()


def name_table(font: bytes, source: Path) -> dict[int, set[str]]:
    if len(font) < 12:
        raise ValueError(f"{source} is not a valid sfnt font")
    table_count = struct.unpack_from(">H", font, 4)[0]
    name_offset = None
    name_length = None
    for index in range(table_count):
        record_offset = 12 + index * 16
        if record_offset + 16 > len(font):
            raise ValueError(f"{source} has a truncated sfnt table directory")
        tag, _, offset, length = struct.unpack_from(">4sIII", font, record_offset)
        if tag == b"name":
            name_offset = offset
            name_length = length
            break
    if name_offset is None or name_length is None or name_offset + name_length > len(font):
        raise ValueError(f"{source} has no complete name table")

    table = font[name_offset : name_offset + name_length]
    if len(table) < 6:
        raise ValueError(f"{source} has a truncated name table")
    _, record_count, strings_offset = struct.unpack_from(">HHH", table, 0)
    values: dict[int, set[str]] = {}
    for index in range(record_count):
        record_offset = 6 + index * 12
        if record_offset + 12 > len(table):
            raise ValueError(f"{source} has a truncated name record")
        platform, _, _, name_id, length, offset = struct.unpack_from(
            ">HHHHHH", table, record_offset
        )
        start = strings_offset + offset
        end = start + length
        if end > len(table):
            raise ValueError(f"{source} has a name string outside its name table")
        encoding = "utf-16-be" if platform in (0, 3) else "mac_roman"
        try:
            value = table[start:end].decode(encoding).rstrip("\0")
        except UnicodeDecodeError as error:
            raise ValueError(f"{source} has an invalid name string") from error
        if value:
            values.setdefault(name_id, set()).add(value)
    return values


def require_name(
    names: dict[int, set[str]], name_id: int, expected: str, source: Path
) -> None:
    if expected not in names.get(name_id, set()):
        actual = ", ".join(sorted(names.get(name_id, set()))) or "<missing>"
        raise ValueError(
            f"{source} name ID {name_id} does not contain {expected!r}; found {actual!r}"
        )


def expected_provenance() -> dict[str, Any]:
    source_files = {}
    for local_name, expected in EXPECTED_FONTS.items():
        upstream_path = expected["upstreamPath"]
        source_files[local_name] = {
            "embeddedUniqueId": expected["embeddedUniqueId"],
            "gitBlobSha1": expected["gitBlobSha1"],
            "postScriptName": expected["postScriptName"],
            "size": expected["size"],
            "sourceUrl": (
                "https://raw.githubusercontent.com/JetBrains/JetBrainsMono/"
                f"{SOURCE_REVISION}/{upstream_path}"
            ),
            "upstreamPath": upstream_path,
        }
    return {
        "schemaVersion": 1,
        "name": "JetBrains Mono",
        "version": "2.305",
        "license": "OFL-1.1",
        "copyright": COPYRIGHT,
        "upstreamRepository": UPSTREAM_REPOSITORY,
        "sourceRevision": SOURCE_REVISION,
        "sourceCommitUrl": SOURCE_COMMIT_URL,
        "sourceRevisionDate": "2024-08-08T12:34:44Z",
        "sourceRevisionMessage": "Built fonts",
        "releaseStatus": "unreleased-upstream-revision",
        "releaseNote": (
            "The upstream project did not publish a v2.305 tag or release archive. "
            "These embedded-version 2.305 files match this immutable official "
            "repository revision byte-for-byte."
        ),
        "embeddedVersion": EMBEDDED_VERSION,
        "licenseFile": {
            "gitBlobSha1": LICENSE_GIT_BLOB_SHA1,
            "sha256": LICENSE_SHA256,
            "size": 4399,
            "sourceUrl": (
                "https://raw.githubusercontent.com/JetBrains/JetBrainsMono/"
                f"{SOURCE_REVISION}/OFL.txt"
            ),
            "upstreamPath": "OFL.txt",
        },
        "files": {
            name: expected["sha256"] for name, expected in EXPECTED_FONTS.items()
        },
        "sourceFiles": source_files,
    }


def verify(font_dir: Path, license_file: Path, provenance_file: Path) -> None:
    license_bytes = license_file.read_bytes()
    if len(license_bytes) != 4399:
        raise ValueError(f"{license_file} has an unexpected size")
    if sha256(license_bytes) != LICENSE_SHA256:
        raise ValueError(f"{license_file} does not match the pinned upstream OFL.txt")
    if git_blob_sha1(license_bytes) != LICENSE_GIT_BLOB_SHA1:
        raise ValueError(f"{license_file} does not match the pinned upstream Git blob")
    license_text = license_bytes.decode("utf-8")
    for marker in (COPYRIGHT, "SIL OPEN FONT LICENSE Version 1.1", "PREAMBLE"):
        if marker not in license_text:
            raise ValueError(f"{license_file} is missing {marker!r}")

    actual_font_names = sorted(path.name for path in font_dir.glob("jetbrainsmono_*.ttf"))
    if actual_font_names != sorted(EXPECTED_FONTS):
        raise ValueError(
            f"{font_dir} has an unexpected JetBrains Mono inventory: {actual_font_names}"
        )

    for local_name, expected in EXPECTED_FONTS.items():
        font_file = font_dir / local_name
        font = font_file.read_bytes()
        if len(font) != expected["size"]:
            raise ValueError(f"{font_file} has an unexpected size")
        if sha256(font) != expected["sha256"]:
            raise ValueError(f"{font_file} does not match the pinned upstream font")
        if git_blob_sha1(font) != expected["gitBlobSha1"]:
            raise ValueError(f"{font_file} does not match the pinned upstream Git blob")
        names = name_table(font, font_file)
        require_name(names, 0, COPYRIGHT, font_file)
        require_name(names, 3, expected["embeddedUniqueId"], font_file)
        require_name(names, 5, EMBEDDED_VERSION, font_file)
        require_name(names, 6, expected["postScriptName"], font_file)
        if not any(
            "SIL Open Font License, Version 1.1" in value
            for value in names.get(13, set())
        ):
            raise ValueError(f"{font_file} does not embed the OFL-1.1 license statement")
        require_name(names, 14, "https://openfontlicense.org", font_file)

    provenance = json.loads(provenance_file.read_text(encoding="utf-8"))
    expected = expected_provenance()
    if provenance != expected:
        raise ValueError(
            f"{provenance_file} does not exactly match the pinned JetBrains Mono evidence"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font-dir", required=True, type=Path)
    parser.add_argument("--license", required=True, type=Path)
    parser.add_argument("--provenance", required=True, type=Path)
    args = parser.parse_args()
    try:
        verify(args.font_dir, args.license, args.provenance)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        parser.error(str(error))
    print(
        "Verified JetBrains Mono 2.305 evidence for official revision "
        f"{SOURCE_REVISION}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
