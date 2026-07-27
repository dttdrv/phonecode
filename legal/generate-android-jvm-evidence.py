#!/usr/bin/env python3
"""Generate deterministic release dependency evidence from resolved artifacts and Maven POMs."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from urllib.parse import quote
import zipfile


MAX_POM_DEPTH = 20
MAX_EMBEDDED_LEGAL_FILE_BYTES = 4 * 1024 * 1024
MAX_EMBEDDED_LEGAL_TOTAL_BYTES = 16 * 1024 * 1024
LEGAL_FILE_NAME = re.compile(
    r"^(?:license|licence|notice|copying|copyright)(?:[._-].*)?$|^about\.html$",
    re.IGNORECASE,
)
SUPPLEMENTAL_LICENSES = {
    "org.slf4j:slf4j-api:1.7.36": {
        "license": "upstream/slf4j-1.7.36-LICENSE.txt",
        "provenance": "upstream/slf4j-1.7.36-LICENSE.provenance.json",
        "license_sha256": "6add69474639ec79e70f4a26be310aefd732b826fcbf9126da575a9efb97d72f",
        "source_sha256": "6fbe2eaf44b193b8a40eed9208f52848572224ad8d7672dd09418aa174847e73",
    },
}
AUTHENTICATED_SUPPLEMENT_FILES = {
    "kotlinx-atomicfu-notice": {
        "coordinates": ["org.jetbrains.kotlinx:atomicfu-jvm:0.23.2"],
        "file": "upstream/kotlinx-atomicfu-0.23.2-NOTICE.txt",
        "provenance": "upstream/kotlinx-atomicfu-0.23.2-NOTICE.provenance.json",
        "local_sha256": "2b1db3aa8302414ea2bb1be5391110268b4611a11c9c3d28e34bf3acfddc9ef4",
        "source_sha256": "2b1db3aa8302414ea2bb1be5391110268b4611a11c9c3d28e34bf3acfddc9ef4",
        "source_commit": "7e640bab1db140a398f735be97e3f07f97019ac0",
        "source_blob": "8a648f65adac22b7397f0788cc7a3d10172e2e11",
        "source_path": "license/NOTICE.txt",
        "source_repository": "https://github.com/Kotlin/kotlinx-atomicfu",
        "source_tag": "0.23.2",
        "license_expression": "Apache-2.0",
        "role": "notice",
    },
    "kotlinx-coroutines-notice": {
        "coordinates": [
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0",
        ],
        "file": "upstream/kotlinx-coroutines-1.9.0-NOTICE.txt",
        "provenance": "upstream/kotlinx-coroutines-1.9.0-NOTICE.provenance.json",
        "local_sha256": "421d45acbd92670b4131621a28fcbb41ba0b747d3a336cb13fca38c0685426c4",
        "source_sha256": "421d45acbd92670b4131621a28fcbb41ba0b747d3a336cb13fca38c0685426c4",
        "source_commit": "d8d6f8f37978b8e202d93b34f23f101df9c5724d",
        "source_blob": "01d81385e4ace2e64601668ea7021ca27cdbe376",
        "source_path": "license/NOTICE.txt",
        "source_repository": "https://github.com/Kotlin/kotlinx.coroutines",
        "source_tag": "1.9.0",
        "license_expression": "Apache-2.0",
        "role": "notice",
    },
    "kotlinx-serialization-notice": {
        "coordinates": [
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3",
        ],
        "file": "upstream/kotlinx-serialization-1.7.3-NOTICE.txt",
        "provenance": "upstream/kotlinx-serialization-1.7.3-NOTICE.provenance.json",
        "local_sha256": "ec77bba5c852830abfdefcdefbf4a22577ff4df79ec323a898d5100ca1d6f8d6",
        "source_sha256": "ec77bba5c852830abfdefcdefbf4a22577ff4df79ec323a898d5100ca1d6f8d6",
        "source_commit": "d4d066d72a9f92f06c640be5a36a22f75d0d7659",
        "source_blob": "ee516ec09649791af5ff26c0346e748a881cacb7",
        "source_path": "license/NOTICE.txt",
        "source_repository": "https://github.com/Kotlin/kotlinx.serialization",
        "source_tag": "v1.7.3",
        "license_expression": "Apache-2.0",
        "role": "notice",
    },
    "kotlin-stdlib-threetenbp-license": {
        "coordinates": ["org.jetbrains.kotlin:kotlin-stdlib:2.3.21"],
        "file": "upstream/kotlin-stdlib-2.3.21-THREETENBP-LICENSE.txt",
        "provenance": (
            "upstream/kotlin-stdlib-2.3.21-THREETENBP-LICENSE.provenance.json"
        ),
        "local_sha256": "d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331",
        "source_sha256": "d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331",
        "source_commit": "fea1ad8c18995b80d1ca0e3917056104613d23db",
        "source_blob": "bbed3563e938dd26bdc2daeb5f5cccdd0f03eb61",
        "source_path": "license/third_party/threetenbp_license.txt",
        "source_repository": "https://github.com/JetBrains/kotlin",
        "source_tag": "v2.3.21",
        "license_expression": "BSD-3-Clause",
        "role": "third-party-license",
    },
}
COORDINATE_SUPPLEMENTS = {
    "org.jetbrains.kotlinx:atomicfu-jvm:0.23.2": {
        "files": ["kotlinx-atomicfu-notice"],
        "license_expression": "Apache-2.0",
        "copyright_status": "included",
        "notice_status": "included",
    },
    "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0": {
        "files": ["kotlinx-coroutines-notice"],
        "license_expression": "Apache-2.0",
        "copyright_status": "included",
        "notice_status": "included",
    },
    "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0": {
        "files": ["kotlinx-coroutines-notice"],
        "license_expression": "Apache-2.0",
        "copyright_status": "included",
        "notice_status": "included",
    },
    "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3": {
        "files": ["kotlinx-serialization-notice"],
        "license_expression": "Apache-2.0",
        "copyright_status": "included",
        "notice_status": "included",
    },
    "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3": {
        "files": ["kotlinx-serialization-notice"],
        "license_expression": "Apache-2.0",
        "copyright_status": "included",
        "notice_status": "included",
    },
    "org.jetbrains.kotlin:kotlin-stdlib:2.3.21": {
        "files": ["kotlin-stdlib-threetenbp-license"],
        "license_expression": "Apache-2.0 AND BSD-3-Clause",
        "third_party_license_expression": "BSD-3-Clause",
        "third_party_origin": "ThreeTenBP-derived implementation in kotlin.time.Instant",
        "copyright_status": "included",
        "notice_status": "included",
    },
}
NOTICE_STATUSES = {"included", "source-reviewed-none", "not-applicable", "ambiguous"}
COPYRIGHT_STATUSES = {"included", "source-reviewed-none", "ambiguous"}


def fail(message: str) -> None:
    raise SystemExit(f"android JVM evidence: {message}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def direct_child(element: ET.Element, name: str) -> ET.Element | None:
    return next((child for child in element if local_name(child.tag) == name), None)


def child_text(element: ET.Element, name: str) -> str:
    child = direct_child(element, name)
    return (child.text or "").strip() if child is not None else ""


def pom_metadata(
    path: Path,
) -> tuple[str, list[dict[str, str]], tuple[str, str, str] | None]:
    try:
        project = ET.parse(path).getroot()
    except ET.ParseError as error:
        fail(f"invalid Maven POM {path}: {error}")
    project_url = child_text(project, "url")
    licenses_parent = direct_child(project, "licenses")
    licenses: list[dict[str, str]] = []
    if licenses_parent is not None:
        for license_element in licenses_parent:
            if local_name(license_element.tag) != "license":
                continue
            name = child_text(license_element, "name")
            url = child_text(license_element, "url")
            if name:
                license_entry = {"name": name}
                if url:
                    license_entry["url"] = url
                licenses.append(license_entry)
    licenses.sort(key=lambda item: (item["name"], item.get("url", "")))
    parent = direct_child(project, "parent")
    parent_coordinate = None
    if parent is not None:
        parent_fields = (
            child_text(parent, "groupId"),
            child_text(parent, "artifactId"),
            child_text(parent, "version"),
        )
        if all(parent_fields):
            parent_coordinate = parent_fields
    return project_url, licenses, parent_coordinate


def gradle_module_cache_root(path: Path) -> Path | None:
    for parent in path.parents:
        if parent.name == "files-2.1":
            return parent
    return None


def cached_parent_pom(
    child_pom: Path,
    coordinate: tuple[str, str, str],
) -> Path:
    cache_root = gradle_module_cache_root(child_pom)
    if cache_root is None:
        fail(
            f"{child_pom} declares parent {':'.join(coordinate)} but is not inside "
            "a Gradle modules-2/files-2.1 cache"
        )
    if any("${" in field or "}" in field for field in coordinate):
        fail(f"unresolved Maven parent coordinate in {child_pom}: {':'.join(coordinate)}")
    module = cache_root.joinpath(*coordinate)
    candidates = sorted(module.glob("*/*.pom"))
    if not candidates:
        fail(f"cached parent Maven POM is missing for {':'.join(coordinate)}")
    hashes = {sha256(candidate) for candidate in candidates}
    if len(hashes) != 1:
        fail(
            f"cached parent Maven POM is ambiguous for {':'.join(coordinate)}: "
            f"{[str(candidate) for candidate in candidates]}"
        )
    return candidates[0]


def resolved_pom_metadata(
    coordinate: str,
    path: Path,
) -> tuple[str, list[dict[str, str]], str, list[dict[str, str]]]:
    project_url = ""
    evidence_chain = []
    seen: set[str] = set()
    current_path = path
    current_coordinate = coordinate
    for depth in range(MAX_POM_DEPTH):
        if current_coordinate in seen:
            fail(f"Maven parent cycle for {coordinate}: {current_coordinate}")
        seen.add(current_coordinate)
        current_url, licenses, parent = pom_metadata(current_path)
        if not project_url and current_url:
            project_url = current_url
        evidence_chain.append(
            {
                "coordinate": current_coordinate,
                "sha256": sha256(current_path),
            }
        )
        if licenses:
            evidence = (
                "upstream-pom-declaration"
                if depth == 0
                else "inherited-upstream-pom-declaration"
            )
            return project_url, licenses, evidence, evidence_chain
        if parent is None:
            return (
                project_url,
                [],
                "missing-upstream-pom-declaration",
                evidence_chain,
            )
        current_coordinate = ":".join(parent)
        current_path = cached_parent_pom(current_path, parent)
    fail(f"Maven parent depth exceeds {MAX_POM_DEPTH} for {coordinate}")


def decode_legal_text(content: bytes, artifact: Path, entry: str) -> str:
    for encoding in ("utf-8-sig", "iso-8859-1"):
        try:
            return content.decode(encoding).replace("\r\n", "\n").replace("\r", "\n")
        except UnicodeDecodeError:
            pass
    fail(f"cannot decode embedded legal file {entry} in {artifact}")


def embedded_legal_files(artifact: Path) -> list[dict[str, str]]:
    if not zipfile.is_zipfile(artifact):
        return []
    records = []
    total_size = 0
    try:
        with zipfile.ZipFile(artifact) as archive:
            for info in sorted(archive.infolist(), key=lambda item: item.filename):
                if info.is_dir() or not LEGAL_FILE_NAME.fullmatch(Path(info.filename).name):
                    continue
                if info.file_size > MAX_EMBEDDED_LEGAL_FILE_BYTES:
                    fail(
                        f"embedded legal file is too large in {artifact}: "
                        f"{info.filename} ({info.file_size} bytes)"
                    )
                total_size += info.file_size
                if total_size > MAX_EMBEDDED_LEGAL_TOTAL_BYTES:
                    fail(f"embedded legal files exceed size limit in {artifact}")
                content = archive.read(info)
                records.append(
                    {
                        "path": info.filename,
                        "sha256": hashlib.sha256(content).hexdigest(),
                        "text": decode_legal_text(content, artifact, info.filename),
                    }
                )
    except zipfile.BadZipFile as error:
        fail(f"invalid ZIP artifact {artifact}: {error}")
    return records


def supplemental_legal_files(coordinate: str) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    specification = SUPPLEMENTAL_LICENSES.get(coordinate)
    legal_root = Path(__file__).resolve().parent
    if specification is not None:
        license_path = legal_root / specification["license"]
        provenance_path = legal_root / specification["provenance"]
        if not license_path.is_file() or not provenance_path.is_file():
            fail(f"authenticated legal supplement is missing for {coordinate}")
        license_content = license_path.read_bytes()
        license_sha256 = hashlib.sha256(license_content).hexdigest()
        if license_sha256 != specification["license_sha256"]:
            fail(f"authenticated legal supplement hash mismatch for {coordinate}")
        try:
            provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            fail(f"invalid legal supplement provenance for {coordinate}: {error}")
        if not isinstance(provenance, dict):
            fail(f"invalid legal supplement provenance for {coordinate}: expected object")
        source = provenance.get("source")
        if (
            provenance.get("coordinate") != coordinate
            or provenance.get("local_path") != f"legal/{specification['license']}"
            or provenance.get("local_sha256") != license_sha256
            or not isinstance(source, dict)
            or source.get("sha256") != specification["source_sha256"]
            or not re.fullmatch(r"[0-9a-f]{40}", str(source.get("commit", "")))
            or not re.fullmatch(
                r"https://raw\.githubusercontent\.com/.+",
                str(source.get("raw_url", "")),
            )
        ):
            fail(f"legal supplement provenance does not authenticate {coordinate}")
        records.append(
            {
                "path": "authenticated-upstream/LICENSE-slf4j-1.7.36.txt",
                "sha256": license_sha256,
                "text": decode_legal_text(
                    license_content,
                    license_path,
                    license_path.name,
                ),
                "source_commit": source["commit"],
                "source_sha256": source["sha256"],
                "source_url": source["raw_url"],
                "role": "license",
            }
        )

    coordinate_evidence = COORDINATE_SUPPLEMENTS.get(coordinate)
    if coordinate_evidence is None:
        return records
    if coordinate_evidence["notice_status"] not in NOTICE_STATUSES:
        fail(f"unrecognized NOTICE status for {coordinate}")
    if coordinate_evidence["copyright_status"] not in COPYRIGHT_STATUSES:
        fail(f"unrecognized copyright status for {coordinate}")

    for file_key in coordinate_evidence["files"]:
        file_specification = AUTHENTICATED_SUPPLEMENT_FILES.get(file_key)
        if file_specification is None:
            fail(f"unknown authenticated legal supplement {file_key!r} for {coordinate}")
        if coordinate not in file_specification["coordinates"]:
            fail(f"authenticated legal supplement {file_key!r} is not bound to {coordinate}")
        local_path = legal_root / file_specification["file"]
        provenance_path = legal_root / file_specification["provenance"]
        if not local_path.is_file() or not provenance_path.is_file():
            fail(f"authenticated legal supplement is missing for {coordinate}")
        content = local_path.read_bytes()
        local_sha256 = hashlib.sha256(content).hexdigest()
        if local_sha256 != file_specification["local_sha256"]:
            fail(f"authenticated legal supplement hash mismatch for {coordinate}")
        try:
            provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            fail(f"invalid legal supplement provenance for {coordinate}: {error}")
        if not isinstance(provenance, dict):
            fail(f"invalid legal supplement provenance for {coordinate}: expected object")
        source = provenance.get("source")
        expected_repository_path = file_specification["source_repository"].removeprefix(
            "https://github.com/"
        )
        expected_raw_url = (
            f"https://raw.githubusercontent.com/{expected_repository_path}/"
            f"{file_specification['source_commit']}/{file_specification['source_path']}"
        )
        if (
            provenance.get("coordinates") != file_specification["coordinates"]
            or provenance.get("local_path") != f"legal/{file_specification['file']}"
            or provenance.get("local_sha256") != local_sha256
            or provenance.get("normalization")
            != "None; the upstream bytes are preserved exactly."
            or provenance.get("license_expression")
            != file_specification["license_expression"]
            or provenance.get("notice_status") != coordinate_evidence["notice_status"]
            or provenance.get("copyright_status")
            != coordinate_evidence["copyright_status"]
            or not isinstance(source, dict)
            or source.get("commit") != file_specification["source_commit"]
            or source.get("blob") != file_specification["source_blob"]
            or source.get("path") != file_specification["source_path"]
            or source.get("repository") != file_specification["source_repository"]
            or source.get("tag") != file_specification["source_tag"]
            or source.get("sha256") != file_specification["source_sha256"]
            or local_sha256 != file_specification["source_sha256"]
            or source.get("raw_url") != expected_raw_url
        ):
            fail(f"legal supplement provenance does not authenticate {coordinate}")
        records.append(
            {
                "path": f"authenticated-upstream/{local_path.name}",
                "sha256": local_sha256,
                "text": decode_legal_text(content, local_path, local_path.name),
                "source_commit": source["commit"],
                "source_blob": source["blob"],
                "source_path": source["path"],
                "source_sha256": source["sha256"],
                "source_url": source["raw_url"],
                "license_expression": file_specification["license_expression"],
                "role": file_specification["role"],
            }
        )
    return records


def markdown_fence(text: str) -> str:
    longest = max((len(run) for run in re.findall(r"`+", text)), default=0)
    return "`" * max(3, longest + 1)


def complete_apache_license() -> dict[str, str]:
    path = Path(__file__).resolve().parent.parent / "LICENSE"
    if not path.is_file():
        fail(f"complete Apache-2.0 license text is missing: {path}")
    content = path.read_bytes()
    text = decode_legal_text(content, path, path.name)
    if (
        "Apache License" not in text
        or "Version 2.0, January 2004" not in text
        or "END OF TERMS AND CONDITIONS" not in text
    ):
        fail(f"{path} is not the complete Apache License 2.0 text")
    return {
        "path": "LICENSE",
        "sha256": hashlib.sha256(content).hexdigest(),
        "text": text,
    }


def has_complete_license_text(
    licenses: list[dict[str, str]],
    embedded_files: list[dict[str, str]],
) -> bool:
    if licenses and all(
        "apache" in item["name"].lower() and ("2" in item["name"] or "2.0" in item["name"])
        for item in licenses
    ):
        return True
    return any(
        Path(item["path"]).name.lower().startswith(("license", "licence", "copying"))
        or Path(item["path"]).name.lower() == "about.html"
        for item in embedded_files
    )


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def main() -> None:
    if len(sys.argv) != 3:
        fail("usage: generate-android-jvm-evidence.py INPUTS.json OUTPUT_DIRECTORY")
    input_path = Path(sys.argv[1])
    output = Path(sys.argv[2])
    try:
        inputs = json.loads(input_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {input_path}: {error}")
    if not isinstance(inputs, list) or not inputs:
        fail("resolved artifact input must be a non-empty JSON array")
    apache_license = complete_apache_license()

    records = []
    seen_coordinates: set[str] = set()
    for raw in inputs:
        if not isinstance(raw, dict):
            fail("resolved artifact entry is not an object")
        coordinate = raw.get("coordinate")
        artifact = Path(str(raw.get("artifact", "")))
        pom = Path(str(raw.get("pom", "")))
        fields = coordinate.split(":") if isinstance(coordinate, str) else []
        if len(fields) != 3 or any(not field for field in fields):
            fail(f"invalid Maven coordinate: {coordinate!r}")
        if coordinate in seen_coordinates:
            fail(f"duplicate Maven coordinate: {coordinate}")
        seen_coordinates.add(coordinate)
        if not artifact.is_file():
            fail(f"artifact is missing for {coordinate}: {artifact}")
        if not pom.is_file():
            fail(f"Maven POM is missing for {coordinate}: {pom}")
        project_url, licenses, license_evidence, pom_evidence_chain = resolved_pom_metadata(
            coordinate,
            pom,
        )
        records.append(
            {
                "coordinate": coordinate,
                "group": fields[0],
                "name": fields[1],
                "version": fields[2],
                "artifact": artifact,
                "artifact_sha256": sha256(artifact),
                "pom": pom,
                "pom_sha256": sha256(pom),
                "pom_evidence_chain": pom_evidence_chain,
                "project_url": project_url,
                "licenses": licenses,
                "license_evidence": license_evidence,
                "embedded_legal_files": embedded_legal_files(artifact),
                "supplemental_legal_files": supplemental_legal_files(coordinate),
            }
        )
    records.sort(key=lambda item: item["coordinate"])

    components = []
    for record in records:
        licenses = record["licenses"]
        license_evidence = record["license_evidence"]
        pom_evidence_chain = ";".join(
            f"{item['coordinate']}@{item['sha256']}"
            for item in record["pom_evidence_chain"]
        )
        embedded_count = len(record["embedded_legal_files"])
        complete_license_text = has_complete_license_text(
            licenses,
            record["embedded_legal_files"] + record["supplemental_legal_files"],
        )
        component = {
            "type": "library",
            "bom-ref": (
                f"pkg:maven/{quote(record['group'], safe='')}/"
                f"{quote(record['name'], safe='')}@{quote(record['version'], safe='')}"
            ),
            "group": record["group"],
            "name": record["name"],
            "version": record["version"],
            "hashes": [{"alg": "SHA-256", "content": record["artifact_sha256"]}],
            "licenses": [
                {"license": license_entry}
                for license_entry in (licenses or [{"name": "NOASSERTION"}])
            ],
            "properties": [
                {"name": "phonecode:artifact-file", "value": record["artifact"].name},
                {"name": "phonecode:maven-pom-sha256", "value": record["pom_sha256"]},
                {"name": "phonecode:license-evidence", "value": license_evidence},
                {"name": "phonecode:pom-evidence-chain", "value": pom_evidence_chain},
                {
                    "name": "phonecode:embedded-legal-files-included",
                    "value": str(embedded_count),
                },
                {
                    "name": "phonecode:supplemental-legal-files-included",
                    "value": str(len(record["supplemental_legal_files"])),
                },
                {
                    "name": "phonecode:complete-license-text-included",
                    "value": str(complete_license_text).lower(),
                },
            ],
        }
        coordinate_evidence = COORDINATE_SUPPLEMENTS.get(record["coordinate"])
        if coordinate_evidence is not None:
            authenticated_files = [
                item
                for item in record["supplemental_legal_files"]
                if "source_blob" in item
            ]
            component["properties"].extend(
                [
                    {
                        "name": "phonecode:copyright-status",
                        "value": coordinate_evidence["copyright_status"],
                    },
                    {
                        "name": "phonecode:notice-status",
                        "value": coordinate_evidence["notice_status"],
                    },
                    {
                        "name": "phonecode:supplement-license-expression",
                        "value": coordinate_evidence["license_expression"],
                    },
                    {
                        "name": "phonecode:supplement-review-scope",
                        "value": "coordinate-keyed-authenticated-supplement-only",
                    },
                    {
                        "name": "phonecode:supplement-source-sha256",
                        "value": ";".join(
                            f"{item['source_path']}@{item['source_sha256']}"
                            for item in authenticated_files
                        ),
                    },
                    {
                        "name": "phonecode:supplement-source-commit",
                        "value": ";".join(
                            f"{item['source_commit']}:{item['source_blob']}"
                            for item in authenticated_files
                        ),
                    },
                ]
            )
            if "third_party_license_expression" in coordinate_evidence:
                component["properties"].extend(
                    [
                        {
                            "name": "phonecode:third-party-license-expression",
                            "value": coordinate_evidence[
                                "third_party_license_expression"
                            ],
                        },
                        {
                            "name": "phonecode:third-party-origin",
                            "value": coordinate_evidence["third_party_origin"],
                        },
                    ]
                )
        if record["project_url"]:
            component["externalReferences"] = [
                {"type": "website", "url": record["project_url"]}
            ]
        components.append(component)

    sbom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": "pkg:generic/phonecode-android-runtime@0.5.1",
                "name": "PhoneCode Android/JVM release runtime graph",
                "version": "0.5.1",
            },
            "properties": [
                {"name": "phonecode:configuration", "value": "releaseRuntimeClasspath"},
                {
                    "name": "phonecode:license-scope",
                    "value": "upstream-pom-declarations-only",
                },
                {
                    "name": "phonecode:shared-apache-2.0-text-sha256",
                    "value": apache_license["sha256"],
                },
            ],
        },
        "components": components,
    }
    sbom_content = json.dumps(sbom, indent=2, sort_keys=True) + "\n"

    resolved_license_count = sum(bool(record["licenses"]) for record in records)
    embedded_legal_count = sum(bool(record["embedded_legal_files"]) for record in records)
    complete_license_text_count = sum(
        has_complete_license_text(
            record["licenses"],
            record["embedded_legal_files"] + record["supplemental_legal_files"],
        )
        for record in records
    )
    notice_lines = [
        "# Android and JVM release dependency coordinates",
        "",
        (
            "This file authenticates every resolved `releaseRuntimeClasspath` artifact and records "
            "license declarations from its Maven POM."
        ),
        "",
        (
            "**This evidence includes legal files embedded in the resolved artifacts but does not "
            "establish complete copyright or license-text coverage and is not a "
            "license-compliance approval.** Every declaration, inherited declaration, embedded "
            "file, and missing file must be independently validated before release."
        ),
        "",
        f"Resolved external artifact count: **{len(records)}**",
        f"Upstream license declarations resolved: **{resolved_license_count}/{len(records)}**",
        (
            "Artifacts with embedded legal files included: "
            f"**{embedded_legal_count}/{len(records)}**"
        ),
        (
            "Components with a complete license text included: "
            f"**{complete_license_text_count}/{len(records)}**"
        ),
        "",
        "## Shared complete license texts",
        "",
        "### Apache License 2.0",
        "",
        f"- Source: `{apache_license['path']}`",
        f"- SHA-256: `{apache_license['sha256']}`",
        "",
        f"{markdown_fence(apache_license['text'])}text",
        apache_license["text"].rstrip("\n"),
        markdown_fence(apache_license["text"]),
        "",
    ]
    for record in records:
        notice_lines.extend(
            [
                f"## `{record['coordinate']}`",
                "",
                f"- Artifact: `{record['artifact'].name}`",
                f"- Artifact SHA-256: `{record['artifact_sha256']}`",
                f"- Maven POM SHA-256: `{record['pom_sha256']}`",
                (
                    f"- Upstream project: {record['project_url']}"
                    if record["project_url"]
                    else "- Upstream project: not declared in Maven POM"
                ),
                "- Upstream-declared licenses:",
            ]
        )
        if record["licenses"]:
            for license_entry in record["licenses"]:
                suffix = f" — {license_entry['url']}" if license_entry.get("url") else ""
                notice_lines.append(f"  - {license_entry['name']}{suffix}")
        else:
            notice_lines.append("  - NOASSERTION")
        notice_lines.extend(
            [
                f"- License evidence: {record['license_evidence']}",
                (
                    "- Maven POM evidence chain: "
                    + "; ".join(
                        f"`{item['coordinate']}@{item['sha256']}`"
                        for item in record["pom_evidence_chain"]
                    )
                ),
                (
                    "- Embedded legal files included: "
                    f"{len(record['embedded_legal_files'])}"
                ),
                (
                    "- Authenticated upstream legal supplements included: "
                    f"{len(record['supplemental_legal_files'])}"
                ),
                "",
            ]
        )
        coordinate_evidence = COORDINATE_SUPPLEMENTS.get(record["coordinate"])
        if coordinate_evidence is not None:
            notice_lines.extend(
                [
                    (
                        "- Authenticated supplement license expression: "
                        f"`{coordinate_evidence['license_expression']}`"
                    ),
                    (
                        "- Authenticated copyright status: "
                        f"`{coordinate_evidence['copyright_status']}`"
                    ),
                    (
                        "- Authenticated NOTICE status: "
                        f"`{coordinate_evidence['notice_status']}`"
                    ),
                ]
            )
            if "third_party_license_expression" in coordinate_evidence:
                notice_lines.extend(
                    [
                        (
                            "- Nested third-party license expression: "
                            f"`{coordinate_evidence['third_party_license_expression']}`"
                        ),
                        (
                            "- Nested third-party origin: "
                            f"{coordinate_evidence['third_party_origin']}"
                        ),
                    ]
                )
            notice_lines.append("")
        for legal_file in record["embedded_legal_files"]:
            fence = markdown_fence(legal_file["text"])
            notice_lines.extend(
                [
                    f"### Embedded `{legal_file['path']}`",
                    "",
                    f"- SHA-256: `{legal_file['sha256']}`",
                    "",
                    f"{fence}text",
                    legal_file["text"].rstrip("\n"),
                    fence,
                    "",
                ]
            )
        for legal_file in record["supplemental_legal_files"]:
            fence = markdown_fence(legal_file["text"])
            legal_file_lines = [
                f"### Authenticated upstream `{legal_file['path']}`",
                "",
                f"- Evidence role: `{legal_file['role']}`",
                f"- Local SHA-256: `{legal_file['sha256']}`",
                f"- Upstream commit: `{legal_file['source_commit']}`",
            ]
            if "source_blob" in legal_file:
                legal_file_lines.extend(
                    [
                        f"- Upstream Git blob: `{legal_file['source_blob']}`",
                        f"- Upstream path: `{legal_file['source_path']}`",
                        (
                            "- Supplement license expression: "
                            f"`{legal_file['license_expression']}`"
                        ),
                    ]
                )
            legal_file_lines.extend(
                [
                    f"- Upstream byte SHA-256: `{legal_file['source_sha256']}`",
                    f"- Source: {legal_file['source_url']}",
                    "",
                    f"{fence}text",
                    legal_file["text"].rstrip("\n"),
                    fence,
                    "",
                ]
            )
            notice_lines.extend(legal_file_lines)
    notices_content = "\n".join(notice_lines).rstrip() + "\n"

    atomic_write(output / "android-jvm-SBOM.cdx.json", sbom_content)
    atomic_write(output / "android-jvm-NOTICES.md", notices_content)
    print(f"android JVM evidence: PASS ({len(records)} artifacts, {output})")


if __name__ == "__main__":
    main()
