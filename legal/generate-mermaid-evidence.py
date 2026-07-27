#!/usr/bin/env python3
"""Generate and verify exact, deliberately incomplete Mermaid release evidence."""

from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import sys
import tarfile
import tempfile


VERSION = "10.9.6"
TARBALL_URL = "https://registry.npmjs.org/mermaid/-/mermaid-10.9.6.tgz"
TARBALL_INTEGRITY = (
    "sha512-XRjjRaI4aPCAMpVaOhxIwLYdx3U4Cb6mN0M268ggFAfFRqsvyFW8zxWbEZazN/"
    "mPkqsVWThb0oa1UawWK+XMNg=="
)
TARBALL_SHA512 = (
    "5d18e345a23868f08032955a3a1c48c0b61dc7753809bea6374336ebc8201407"
    "c546ab2fc855bccf159b1196b337f98f92ab1559385bd286b551ac162be5cc36"
)
TARBALL_SHA256 = "6e9f31fbb7e174339f44199726b2b5f118dc985f49b7f00f96b3843ca22e27d1"
TARBALL_SIZE = 5_117_308
PACKAGE_JSON_MEMBER = "package/package.json"
PACKAGE_JSON_SHA256 = "f963194e44252af0bf21918ab7708743b20046ccd7db2f4f30c6ea53f6c58f7a"
ASSET_MEMBER = "package/dist/mermaid.min.js"
ASSET_SHA256 = "eda3a0ad572bbe69a318c1be0163e8233dd824f3f12939e5168feba207767151"
ASSET_SIZE = 3_337_508
OUTPUT_NAMES = (
    "mermaid-PROVENANCE.json",
    "mermaid-declared-dependencies.json",
    "mermaid-SBOM.cdx.json",
    "mermaid-NOTICES.md",
)


def fail(message: str) -> None:
    raise SystemExit(f"mermaid evidence: {message}")


def digest(data: bytes, algorithm: str) -> str:
    return hashlib.new(algorithm, data).hexdigest()


def read_bytes(path: Path, description: str) -> bytes:
    try:
        return path.read_bytes()
    except OSError as error:
        fail(f"cannot read {description} {path}: {error}")


def atomic_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException:
        Path(temporary).unlink(missing_ok=True)
        raise


def json_bytes(document: object) -> bytes:
    return (json.dumps(document, indent=2, sort_keys=True) + "\n").encode()


def validate_package(package_bytes: bytes) -> dict[str, object]:
    actual_hash = digest(package_bytes, "sha256")
    if actual_hash != PACKAGE_JSON_SHA256:
        fail(
            "package.json SHA-256 mismatch: "
            f"expected {PACKAGE_JSON_SHA256}, got {actual_hash}"
        )
    try:
        package = json.loads(package_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"authenticated package.json is invalid: {error}")
    expected_fields = {
        "name": "mermaid",
        "version": VERSION,
        "author": "Knut Sveidqvist",
        "license": "MIT",
    }
    for field, expected in expected_fields.items():
        if package.get(field) != expected:
            fail(f"package.json has unexpected {field}: {package.get(field)!r}")
    repository = package.get("repository")
    if not isinstance(repository, dict) or repository.get("url") != "https://github.com/mermaid-js/mermaid":
        fail("package.json has an unexpected repository")
    dependencies = package.get("dependencies")
    if not isinstance(dependencies, dict) or not dependencies:
        fail("package.json has no declared runtime dependencies")
    if not all(
        isinstance(name, str)
        and name
        and isinstance(declared_range, str)
        and declared_range
        for name, declared_range in dependencies.items()
    ):
        fail("package.json has an invalid declared runtime dependency")
    return package


def validate_asset(asset_bytes: bytes) -> None:
    actual_hash = digest(asset_bytes, "sha256")
    if actual_hash != ASSET_SHA256:
        fail(
            "bundled asset SHA-256 mismatch: "
            f"expected {ASSET_SHA256}, got {actual_hash}"
        )
    if len(asset_bytes) != ASSET_SIZE:
        fail(
            f"bundled asset size mismatch: expected {ASSET_SIZE}, got {len(asset_bytes)}"
        )


def authenticated_tarball_members(path: Path) -> tuple[bytes, bytes]:
    tarball = read_bytes(path, "npm tarball")
    if len(tarball) != TARBALL_SIZE:
        fail(f"npm tarball size mismatch: expected {TARBALL_SIZE}, got {len(tarball)}")
    actual_sha256 = digest(tarball, "sha256")
    if actual_sha256 != TARBALL_SHA256:
        fail(f"npm tarball SHA-256 mismatch: expected {TARBALL_SHA256}, got {actual_sha256}")
    actual_sha512 = digest(tarball, "sha512")
    if actual_sha512 != TARBALL_SHA512:
        fail(f"npm tarball SHA-512 mismatch: expected {TARBALL_SHA512}, got {actual_sha512}")
    integrity_digest = base64.b64decode(TARBALL_INTEGRITY.removeprefix("sha512-"), validate=True).hex()
    if integrity_digest != actual_sha512:
        fail("pinned npm integrity does not match the authenticated tarball")

    try:
        with tarfile.open(fileobj=__import__("io").BytesIO(tarball), mode="r:gz") as archive:
            members = archive.getmembers()
            for member in members:
                member_path = PurePosixPath(member.name)
                if (
                    member_path.is_absolute()
                    or ".." in member_path.parts
                    or member.issym()
                    or member.islnk()
                    or member.isdev()
                ):
                    fail(f"npm tarball contains unsafe member {member.name!r}")
            selected: dict[str, bytes] = {}
            for name in (PACKAGE_JSON_MEMBER, ASSET_MEMBER):
                matching = [member for member in members if member.name == name]
                if len(matching) != 1 or not matching[0].isfile():
                    fail(f"npm tarball does not contain exactly one regular {name}")
                stream = archive.extractfile(matching[0])
                if stream is None:
                    fail(f"cannot read {name} from npm tarball")
                selected[name] = stream.read()
    except (tarfile.TarError, OSError) as error:
        fail(f"cannot inspect npm tarball: {error}")
    return selected[PACKAGE_JSON_MEMBER], selected[ASSET_MEMBER]


def render_outputs(package: dict[str, object]) -> dict[str, bytes]:
    dependencies = [
        {"name": name, "declaredRange": declared_range}
        for name, declared_range in sorted(package["dependencies"].items())
    ]
    provenance = {
        "schemaVersion": 1,
        "component": {
            "name": "mermaid",
            "version": VERSION,
            "licenseDeclaredByPackage": package["license"],
            "authorDeclaredByPackage": package["author"],
            "repositoryDeclaredByPackage": package["repository"]["url"],
        },
        "npmPackage": {
            "registryTarball": TARBALL_URL,
            "npmIntegrity": TARBALL_INTEGRITY,
            "sha256": TARBALL_SHA256,
            "sha512": TARBALL_SHA512,
            "size": TARBALL_SIZE,
            "packageJsonMember": PACKAGE_JSON_MEMBER,
            "packageJsonSha256": PACKAGE_JSON_SHA256,
        },
        "bundledAsset": {
            "path": "app/src/main/assets/mermaid.min.js",
            "packageMember": ASSET_MEMBER,
            "sha256": ASSET_SHA256,
            "size": ASSET_SIZE,
        },
        "evidenceScope": {
            "authenticatedPackageArtifact": True,
            "authenticatedPackageMetadataSnapshot": True,
            "bundledAssetByteIdenticalToPackageMember": True,
            "resolvedDependencyVersions": False,
            "bundledDependencyClosure": False,
            "completeThirdPartyNotices": False,
        },
        "caveats": [
            "The npm package contains no dependency lock or source map.",
            "Declared dependency ranges do not identify the versions resolved into the bundle.",
            "This evidence does not identify the complete bundled transitive dependency closure.",
            "This evidence does not contain complete third-party license and copyright notices.",
        ],
    }
    declared = {
        "schemaVersion": 1,
        "component": {"name": "mermaid", "version": VERSION},
        "sourcePackageJson": {
            "member": PACKAGE_JSON_MEMBER,
            "sha256": PACKAGE_JSON_SHA256,
        },
        "dependencyKind": "declared-runtime-dependency-ranges",
        "resolvedVersions": False,
        "completeBundledDependencyClosure": False,
        "dependencies": dependencies,
    }
    sbom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "bom-ref": "pkg:generic/phonecode-mermaid-asset@0.5.1",
                "name": "PhoneCode Mermaid web asset",
                "version": "0.5.1",
            },
            "properties": [
                {
                    "name": "phonecode:evidence-scope",
                    "value": "authenticated-package-and-byte-identical-dist-only",
                },
                {
                    "name": "phonecode:resolved-dependency-closure-complete",
                    "value": "false",
                },
                {
                    "name": "phonecode:third-party-notices-complete",
                    "value": "false",
                },
            ],
        },
        "components": [
            {
                "type": "library",
                "bom-ref": f"pkg:npm/mermaid@{VERSION}",
                "name": "mermaid",
                "version": VERSION,
                "hashes": [{"alg": "SHA-256", "content": ASSET_SHA256}],
                "licenses": [{"license": {"id": "MIT"}}],
                "externalReferences": [
                    {"type": "distribution", "url": TARBALL_URL},
                    {"type": "vcs", "url": package["repository"]["url"]},
                ],
                "properties": [
                    {"name": "phonecode:hash-subject", "value": ASSET_MEMBER},
                    {
                        "name": "phonecode:source-package-json-sha256",
                        "value": PACKAGE_JSON_SHA256,
                    },
                    {
                        "name": "phonecode:dependency-inventory-status",
                        "value": "unresolved-and-incomplete",
                    },
                ],
            }
        ],
    }

    notice_lines = [
        "# Mermaid release evidence (incomplete notices)",
        "",
        "**INCOMPLETE THIRD-PARTY NOTICE EVIDENCE — NOT A RELEASE APPROVAL.**",
        "",
        (
            "This file records only facts authenticated by the pinned `mermaid@10.9.6` npm "
            "package and the byte-identical bundled `dist/mermaid.min.js`."
        ),
        (
            "It does not prove resolved dependency versions, the complete bundled transitive "
            "dependency closure, or complete license and copyright notices."
        ),
        "It therefore does not clear the Mermaid release blocker.",
        "",
        "## Authenticated component",
        "",
        f"- Package: `mermaid@{VERSION}`",
        f"- Package-declared author: `{package['author']}`",
        f"- Package-declared license identifier: `{package['license']}`",
        f"- Registry tarball: {TARBALL_URL}",
        f"- npm integrity: `{TARBALL_INTEGRITY}`",
        f"- Tarball SHA-256: `{TARBALL_SHA256}`",
        f"- Bundled asset SHA-256: `{ASSET_SHA256}`",
        f"- Bundled asset size: `{ASSET_SIZE}` bytes",
        "",
        "The published npm tarball does not contain a `LICENSE`, third-party notice file, "
        "dependency lock, or source map. A package-level MIT declaration is not a substitute "
        "for the missing complete notice inventory.",
        "",
        "## Package-declared direct runtime dependency ranges",
        "",
        "| Package | Declared range | Resolved version | License evidence |",
        "| --- | --- | --- | --- |",
    ]
    notice_lines.extend(
        f"| `{item['name']}` | `{item['declaredRange']}` | Not proven | Not collected |"
        for item in dependencies
    )
    notice_lines.extend(
        [
            "",
            "These rows reproduce the authenticated package metadata. They do not assert that "
            "every declared package is present in the bundle, that undeclared/transitive code is "
            "absent, or that any particular version was bundled.",
            "",
            "## Evidence still required before release",
            "",
            "- Derive or independently audit the exact bundled dependency closure.",
            "- Bind every embedded component to an exact resolved version and authenticated source.",
            "- Collect and review every required copyright notice and complete license text.",
            "- Regenerate a complete SBOM and notice bundle from that audited closure.",
            "",
        ]
    )
    notices = "\n".join(notice_lines)
    return {
        "mermaid-PROVENANCE.json": json_bytes(provenance),
        "mermaid-declared-dependencies.json": json_bytes(declared),
        "mermaid-SBOM.cdx.json": json_bytes(sbom),
        "mermaid-NOTICES.md": notices.encode(),
    }


def generate(tarball_path: Path, asset_path: Path, package_snapshot: Path, output: Path) -> None:
    package_bytes, packaged_asset = authenticated_tarball_members(tarball_path)
    package = validate_package(package_bytes)
    asset_bytes = read_bytes(asset_path, "bundled asset")
    validate_asset(asset_bytes)
    if packaged_asset != asset_bytes:
        fail("bundled asset is not byte-identical to package/dist/mermaid.min.js")
    atomic_write(package_snapshot, package_bytes)
    for name, content in render_outputs(package).items():
        atomic_write(output / name, content)
    print(f"mermaid evidence: GENERATED authenticated {VERSION} evidence in {output}")


def verify(asset_path: Path, package_snapshot: Path, output: Path) -> None:
    asset_bytes = read_bytes(asset_path, "bundled asset")
    validate_asset(asset_bytes)
    package_bytes = read_bytes(package_snapshot, "package.json snapshot")
    package = validate_package(package_bytes)
    expected = render_outputs(package)
    for name in OUTPUT_NAMES:
        actual = read_bytes(output / name, "evidence file")
        if actual != expected[name]:
            fail(f"{output / name} does not match deterministic output")
    print(
        "mermaid evidence: PASS "
        "(authenticated package metadata and bundled asset; dependency closure remains incomplete)"
    )


def main() -> None:
    args = sys.argv[1:]
    if len(args) == 5 and args[0] == "generate":
        generate(Path(args[1]), Path(args[2]), Path(args[3]), Path(args[4]))
    elif len(args) == 4 and args[0] == "verify":
        verify(Path(args[1]), Path(args[2]), Path(args[3]))
    else:
        fail(
            "usage: generate-mermaid-evidence.py "
            "generate TARBALL ASSET PACKAGE_SNAPSHOT OUTPUT_DIRECTORY\n"
            "       generate-mermaid-evidence.py "
            "verify ASSET PACKAGE_SNAPSHOT OUTPUT_DIRECTORY"
        )


if __name__ == "__main__":
    main()
