import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "play" / "verify_submission_evidence.py"
FIXTURES = Path(__file__).resolve().parent / "fixtures"
MANIFEST = ROOT / "play" / "0.4.0" / "submission-evidence.json"


class SubmissionEvidenceValidatorTest(unittest.TestCase):
    def run_validator(self, path: Path, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(SCRIPT), *args, str(path)],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def write_mutation(self, mutate) -> Path:
        document = json.loads((FIXTURES / "ready.json").read_text())
        mutate(document)
        temporary = tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False)
        with temporary:
            json.dump(document, temporary)
        self.addCleanup(Path(temporary.name).unlink)
        return Path(temporary.name)

    def test_ready_fixture_passes(self):
        result = self.run_validator(FIXTURES / "ready.json")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("READY: submission evidence is complete and AAB-bound.\n", result.stdout)

    def test_blocked_fixture_fails_closed_but_is_structurally_valid(self):
        blocked = self.run_validator(FIXTURES / "blocked.json")
        schema_only = self.run_validator(FIXTURES / "blocked.json", "--schema-only")

        self.assertEqual(1, blocked.returncode)
        self.assertIn("BLOCKED: data-safety", blocked.stdout)
        self.assertIn("BLOCKED: pre-launch-report", blocked.stdout)
        self.assertEqual(0, schema_only.returncode, schema_only.stderr)
        self.assertEqual("VALID: submission evidence schema is valid.\n", schema_only.stdout)

    def test_missing_requirement_is_invalid(self):
        path = self.write_mutation(lambda document: document["requirements"].pop())

        result = self.run_validator(path)

        self.assertEqual(2, result.returncode)
        self.assertIn("missing required requirement: pre-launch-report", result.stderr)

    def test_passed_requirement_must_match_release_aab(self):
        path = self.write_mutation(
            lambda document: document["requirements"][0].update(
                candidateAabSha256="cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            ),
        )

        result = self.run_validator(path)

        self.assertEqual(2, result.returncode)
        self.assertIn("data-safety candidateAabSha256 does not match release.aabSha256", result.stderr)

    def test_passed_requirement_needs_hashed_evidence(self):
        path = self.write_mutation(lambda document: document["requirements"][0].update(evidence=[]))

        result = self.run_validator(path)

        self.assertEqual(2, result.returncode)
        self.assertIn("data-safety PASS requires at least one evidence record", result.stderr)

    def test_unknown_fields_and_credential_like_content_are_rejected(self):
        def mutate(document):
            document["reviewerPassword"] = "not-allowed"
            document["requirements"][0]["blockers"] = ["Bearer abcdefghijklmnopqrstuvwxyz"]

        result = self.run_validator(self.write_mutation(mutate))

        self.assertEqual(2, result.returncode)
        self.assertIn("document has unknown fields: reviewerPassword", result.stderr)
        self.assertIn("credential-like content is forbidden", result.stderr)

    def test_reference_rejects_query_fragment_and_userinfo(self):
        def mutate(document):
            document["requirements"][0]["evidence"][0]["reference"] = (
                "https://user:password@example.com/export?token=value#secret"
            )

        result = self.run_validator(self.write_mutation(mutate))

        self.assertEqual(2, result.returncode)
        self.assertIn("reference must not contain userinfo, query, or fragment", result.stderr)

    def test_errors_are_deterministic(self):
        path = self.write_mutation(lambda document: document.update(extra="value"))

        first = self.run_validator(path)
        second = self.run_validator(path)

        self.assertEqual(first.returncode, second.returncode)
        self.assertEqual(first.stdout, second.stdout)
        self.assertEqual(first.stderr, second.stderr)

    def test_aab_binding_checks_the_exact_candidate_bytes(self):
        aab = Path(self.addCleanupPath("candidate.aab"))
        aab.write_bytes(b"exact signed candidate")
        aab_sha = hashlib.sha256(aab.read_bytes()).hexdigest()

        def bind(document):
            document["release"]["aabSha256"] = aab_sha
            for requirement in document["requirements"]:
                requirement["candidateAabSha256"] = aab_sha

        manifest = self.write_mutation(bind)
        matching = self.run_validator(manifest, "--aab", str(aab))
        aab.write_bytes(b"different candidate")
        mismatched = self.run_validator(manifest, "--aab", str(aab))

        self.assertEqual(0, matching.returncode, matching.stderr)
        self.assertEqual(2, mismatched.returncode)
        self.assertIn(
            "release.aabSha256 does not match the exact release AAB",
            mismatched.stderr,
        )

    def test_repository_manifest_is_valid_and_truthfully_blocked(self):
        schema_only = self.run_validator(MANIFEST, "--schema-only")
        readiness = self.run_validator(MANIFEST)

        self.assertEqual(0, schema_only.returncode, schema_only.stderr)
        self.assertEqual(1, readiness.returncode)
        self.assertIn("BLOCKED: data-safety", readiness.stdout)
        self.assertIn("BLOCKED: pre-launch-report", readiness.stdout)

    def addCleanupPath(self, name: str) -> str:
        directory = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: directory.rmdir())
        path = directory / name
        self.addCleanup(path.unlink)
        return str(path)


if __name__ == "__main__":
    unittest.main()
