#!/usr/bin/env python3

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlparse


REQUIRED_REQUIREMENTS = (
    "data-safety",
    "privacy-policy-url",
    "terms-url",
    "foreground-service-declaration",
    "foreground-service-video",
    "ai-safety",
    "reviewer-access",
    "content-rating",
    "listing-assets",
    "play-policy-status",
    "signed-device-testing",
    "pre-launch-report",
)
SHA256 = re.compile(r"[0-9a-f]{64}")
PRIVATE_REFERENCE_PATH = re.compile(r"/[A-Za-z0-9._/-]+")
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"\bBearer\s+\S+", re.IGNORECASE),
    re.compile(r"\b(?:password|secret|token|api[_-]?key)\s*[=:]\s*\S+", re.IGNORECASE),
    re.compile(r"\bghp_[A-Za-z0-9]{20,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}\b"),
    re.compile(r"\bAIza[A-Za-z0-9_-]{20,}\b"),
)


def unknown_fields(value: dict, allowed: set[str], label: str) -> list[str]:
    unexpected = sorted(set(value) - allowed)
    return [f"{label} has unknown fields: {', '.join(unexpected)}"] if unexpected else []


def valid_sha256(value) -> bool:
    return isinstance(value, str) and SHA256.fullmatch(value) is not None


def credential_errors(value, path: str = "document") -> list[str]:
    errors = []
    if isinstance(value, dict):
        for key in sorted(value):
            errors.extend(credential_errors(value[key], f"{path}.{key}"))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            errors.extend(credential_errors(item, f"{path}[{index}]"))
    elif isinstance(value, str) and any(pattern.search(value) for pattern in SECRET_PATTERNS):
        errors.append(f"credential-like content is forbidden at {path}")
    return errors


def reference_errors(reference, label: str) -> list[str]:
    if not isinstance(reference, str) or not reference:
        return [f"{label} reference must be a non-empty string"]
    parsed = urlparse(reference)
    if parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment:
        return [f"{label} reference must not contain userinfo, query, or fragment"]
    if parsed.scheme == "https":
        if not parsed.hostname:
            return [f"{label} HTTPS reference must have a host"]
        return []
    if parsed.scheme == "evidence":
        if parsed.netloc != "private" or not PRIVATE_REFERENCE_PATH.fullmatch(parsed.path):
            return [f"{label} private reference must use evidence://private/<opaque-id>"]
        if ".." in parsed.path.split("/"):
            return [f"{label} private reference must not traverse paths"]
        return []
    return [f"{label} reference must use https:// or evidence://private/"]


def validate_document(document) -> tuple[list[str], list[str]]:
    errors = []
    blocked = []
    if not isinstance(document, dict):
        return ["document must be a JSON object"], blocked

    errors.extend(unknown_fields(document, {"schemaVersion", "release", "requirements"}, "document"))
    errors.extend(credential_errors(document))
    if document.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")

    release = document.get("release")
    if not isinstance(release, dict):
        errors.append("release must be an object")
        release = {}
    else:
        errors.extend(
            unknown_fields(
                release,
                {"applicationId", "versionName", "versionCode", "aabSha256"},
                "release",
            ),
        )
    if release.get("applicationId") != "dev.phonecode":
        errors.append("release.applicationId must be dev.phonecode")
    if not isinstance(release.get("versionName"), str) or not release.get("versionName"):
        errors.append("release.versionName must be a non-empty string")
    if not isinstance(release.get("versionCode"), int) or release.get("versionCode", 0) <= 0:
        errors.append("release.versionCode must be a positive integer")
    release_sha = release.get("aabSha256")
    if release_sha is not None and not valid_sha256(release_sha):
        errors.append("release.aabSha256 must be null or a lowercase SHA-256")

    requirements = document.get("requirements")
    if not isinstance(requirements, list):
        errors.append("requirements must be an array")
        requirements = []

    indexed = {}
    for index, requirement in enumerate(requirements):
        label = f"requirements[{index}]"
        if not isinstance(requirement, dict):
            errors.append(f"{label} must be an object")
            continue
        requirement_id = requirement.get("id")
        if not isinstance(requirement_id, str) or not requirement_id:
            errors.append(f"{label}.id must be a non-empty string")
            continue
        if requirement_id in indexed:
            errors.append(f"duplicate requirement: {requirement_id}")
            continue
        indexed[requirement_id] = requirement

    for requirement_id in REQUIRED_REQUIREMENTS:
        if requirement_id not in indexed:
            errors.append(f"missing required requirement: {requirement_id}")
    for requirement_id in sorted(set(indexed) - set(REQUIRED_REQUIREMENTS)):
        errors.append(f"unknown requirement: {requirement_id}")
    if [item.get("id") for item in requirements if isinstance(item, dict)] != list(indexed):
        errors.append("requirements must not contain duplicate ids")
    if list(indexed) != [item for item in REQUIRED_REQUIREMENTS if item in indexed]:
        errors.append("requirements must use the canonical order")

    for requirement_id in REQUIRED_REQUIREMENTS:
        requirement = indexed.get(requirement_id)
        if requirement is None:
            continue
        errors.extend(
            unknown_fields(
                requirement,
                {"id", "status", "candidateAabSha256", "evidence", "blockers"},
                requirement_id,
            ),
        )
        status = requirement.get("status")
        if status not in {"BLOCKED", "PASS"}:
            errors.append(f"{requirement_id} status must be BLOCKED or PASS")

        candidate_sha = requirement.get("candidateAabSha256")
        if candidate_sha is not None and not valid_sha256(candidate_sha):
            errors.append(f"{requirement_id} candidateAabSha256 must be null or a lowercase SHA-256")
        if candidate_sha is not None and candidate_sha != release_sha:
            errors.append(f"{requirement_id} candidateAabSha256 does not match release.aabSha256")

        evidence = requirement.get("evidence")
        if not isinstance(evidence, list):
            errors.append(f"{requirement_id} evidence must be an array")
            evidence = []
        for evidence_index, record in enumerate(evidence):
            record_label = f"{requirement_id} evidence[{evidence_index}]"
            if not isinstance(record, dict):
                errors.append(f"{record_label} must be an object")
                continue
            errors.extend(unknown_fields(record, {"reference", "sha256"}, record_label))
            errors.extend(reference_errors(record.get("reference"), record_label))
            if not valid_sha256(record.get("sha256")):
                errors.append(f"{record_label} sha256 must be a lowercase SHA-256")
        if evidence and (release_sha is None or candidate_sha != release_sha):
            errors.append(f"{requirement_id} evidence is not bound to release.aabSha256")

        blockers = requirement.get("blockers")
        if not isinstance(blockers, list) or any(not isinstance(item, str) or not item.strip() for item in blockers):
            errors.append(f"{requirement_id} blockers must be an array of non-empty strings")
            blockers = []

        if status == "PASS":
            if release_sha is None:
                errors.append(f"{requirement_id} PASS requires release.aabSha256")
            if candidate_sha != release_sha:
                errors.append(f"{requirement_id} PASS must bind candidateAabSha256 to release.aabSha256")
            if not evidence:
                errors.append(f"{requirement_id} PASS requires at least one evidence record")
            if blockers:
                errors.append(f"{requirement_id} PASS must not have blockers")
        elif status == "BLOCKED":
            if not blockers:
                errors.append(f"{requirement_id} BLOCKED requires at least one blocker")
            blocked.append(f"BLOCKED: {requirement_id} — {'; '.join(blockers)}")

    return sorted(set(errors)), blocked


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate PhoneCode Play submission evidence. Readiness is fail-closed by default.",
    )
    parser.add_argument("--schema-only", action="store_true", help="Validate structure without requiring PASS status.")
    parser.add_argument("--aab", type=Path, help="Require release.aabSha256 to match this exact Android App Bundle.")
    parser.add_argument("manifest", type=Path)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    args = parse_args()
    try:
        document = json.loads(args.manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"INVALID: {error}", file=sys.stderr)
        return 2

    errors, blocked = validate_document(document)
    if errors:
        for error in errors:
            print(f"INVALID: {error}", file=sys.stderr)
        return 2
    if args.aab is not None:
        try:
            actual_aab_sha = sha256(args.aab)
        except OSError as error:
            print(f"INVALID: release AAB is unreadable: {error}", file=sys.stderr)
            return 2
        if document["release"]["aabSha256"] != actual_aab_sha:
            print(
                "INVALID: release.aabSha256 does not match the exact release AAB",
                file=sys.stderr,
            )
            return 2
    if args.schema_only:
        print("VALID: submission evidence schema is valid.")
        return 0
    if blocked:
        for item in blocked:
            print(item)
        return 1
    print("READY: submission evidence is complete and AAB-bound.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
