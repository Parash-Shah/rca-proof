"""Exercise Git binding and signing in disposable repositories with ephemeral test keys."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), SCRIPTS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CertificationToolsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="rcaproof-tool-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.prepare, self.signer = load("prepare-certification"), load("attest-certification")
        self.prepare.root = self.signer.ROOT = self.root
        self.git("init", "-q")
        self.git("config", "user.name", "RCAProof Test Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        (self.root / ".gitignore").write_text("target/\nartifacts/\nbuild-evidence/source.json\nbuild-evidence/candidate.patch\n")
        (self.root / "build-evidence").mkdir()
        (self.root / "app.txt").write_text("baseline\n")
        self.git("add", ".")
        self.git("commit", "-qm", "test baseline")
        self.base = self.git("rev-parse", "HEAD").decode().strip()
        (self.root / "app.txt").write_text("candidate\n")
        self.git("add", ".")
        self.git("commit", "-qm", "test candidate")
        with patch.object(sys, "argv", ["prepare", "--base", self.base]):
            self.prepare.main()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.PIPE)

    def test_exact_patch_binding_and_dirty_tree_rejection_remove_stale_claims(self):
        manifest = json.loads((self.root / "build-evidence/source.json").read_text())
        self.assertEqual(manifest["commit"], self.git("rev-parse", "HEAD").decode().strip())
        self.assertEqual(manifest["patchSha256"], hashlib.sha256((self.root / "build-evidence/candidate.patch").read_bytes()).hexdigest())
        (self.root / "untracked-source.java").write_text("changed")
        with patch.object(sys, "argv", ["prepare", "--base", self.base]), self.assertRaises(SystemExit):
            self.prepare.main()
        self.assertFalse((self.root / "build-evidence/source.json").exists())

    def test_signer_requires_complete_fresh_results_and_signature_verifies(self):
        source = json.loads((self.root / "build-evidence/source.json").read_text())
        target = self.root / "rcaproof-api/target"
        reports = target / "surefire-reports"
        reports.mkdir(parents=True)
        archive = target / "rcaproof-api-0.1.0-SNAPSHOT-exec.jar"
        archive.write_bytes(b"explicit test archive fixture")
        for name in self.signer.REQUIRED:
            (reports / ("TEST-" + name + ".xml")).write_text(f'<testsuite name="{name}" tests="1" failures="0" errors="0" skipped="0"/>')
        subject = dict(binding=dict(source=source, problem=None, applicationSha256=hashlib.sha256(archive.read_bytes()).hexdigest()),
                         status="VERIFIED_IN_SANDBOX", candidateId="IDEMPOTENCY_LEDGER", failureSignature="DUPLICATE_PAYMENT", suiteVersion="payment-verification-v2", candidateDigest="a" * 64,
                       organizationDigest="b" * 64, checks=[dict(name=name, passed=True) for name in self.signer.REQUIRED_CHECKS])
        subject_file = target / "certification-subject.json"
        subject_file.write_text(json.dumps(subject))
        private_key = subprocess.check_output(["openssl", "genpkey", "-algorithm", "ED25519"])
        environment = dict(GITHUB_SHA=source["commit"], GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main",
                           GITHUB_REPOSITORY="fixture/repository", GITHUB_RUN_ID="fixture", RCAPROOF_CI_SIGNING_KEY=private_key.decode())
        original_check_output = subprocess.check_output

        def command(args, **kwargs):
            if args[:3] == ["docker", "image", "inspect"]:
                return "sha256:" + "f" * 64
            return original_check_output(args, **kwargs)

        with patch.dict(os.environ, environment), patch.object(subprocess, "check_output", side_effect=command):
            self.signer.main()
            signed = json.loads((self.root / "artifacts/ci-attestation.json").read_text())
            retry = dict(subject, candidateId="BOUNDED_RETRY", failureSignature="RETRY_STORM", suiteVersion="http-retry-verification-v2",
                         checks=[dict(name=name, passed=True) for name in self.signer.RETRY_CHECKS])
            retry_file = target / "certification-subject-http-retry.json"
            retry_file.write_text(json.dumps(retry))
            with patch.dict(os.environ, {"RCAPROOF_CERTIFICATION_CONTRACT": "http-retry"}):
                self.signer.main()
                retry_payload = json.loads(json.loads((self.root / "artifacts/ci-attestation.json").read_text())["payload"])
                self.assertEqual(retry_payload["candidateId"], "BOUNDED_RETRY")
                self.assertEqual(retry_payload["failureSignature"], "RETRY_STORM")
                retry_file.write_text(json.dumps(subject))
                with self.assertRaises(SystemExit):
                    self.signer.main()
                retry_file.write_text(json.dumps(retry))
            with tempfile.TemporaryDirectory() as keys:
                key = Path(keys) / "private.pem"
                key.write_bytes(private_key)
                public = Path(keys) / "public.pem"
                public.write_bytes(original_check_output(["openssl", "pkey", "-in", str(key), "-pubout"]))
                message, signature = Path(keys) / "message", Path(keys) / "signature"
                message.write_bytes(signed["payload"].encode())
                signature.write_bytes(base64.b64decode(signed["signature"]))
                subprocess.check_call(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(public),
                                       "-in", str(message), "-sigfile", str(signature)], stdout=subprocess.DEVNULL)
            # Candidate builds run without secrets; the approved trusted job signs their exact subject.
            candidate_env = dict(GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_SHA="0" * 40,
                                 GITHUB_WORKFLOW_REF="fixture/repository/.github/workflows/certify.yml@refs/heads/main",
                                 RCAPROOF_ATTEST_COMMIT=source["commit"], RCAPROOF_ATTEST_BASE=source["baseCommit"])
            measured = dict(runId="fixture", commit=source["commit"], environment={image: "sha256:" + "f" * 64
                            for image in ("localstack/localstack:4.7.0", "postgres:16-alpine", "wiremock/wiremock:3.13.1")})
            measured["sourceManifestSha256"] = hashlib.sha256((self.root / "build-evidence/source.json").read_bytes()).hexdigest()
            measured["reports"] = {p.relative_to(self.root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in reports.glob("TEST-*.xml")}
            measured["subjects"] = {p.relative_to(self.root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in (subject_file, retry_file)}
            measured_file = target / "ci-environment.json"
            measured_file.write_text(json.dumps(measured))
            with patch.dict(os.environ, candidate_env):
                self.signer.main()
                payload = json.loads(json.loads((self.root / "artifacts/ci-attestation.json").read_text())["payload"])
                self.assertEqual(payload["workflow"], ".github/workflows/certify.yml")
                self.assertEqual(payload["source"]["commit"], source["commit"])
                original_subject = subject_file.read_text()
                subject_file.write_text(original_subject + " ")
                with self.assertRaises(SystemExit):
                    self.signer.main()
                subject_file.write_text(original_subject)
                # Artifact extraction may change mtimes; content/run bindings must survive the transfer.
                for report_file in reports.glob("TEST-*.xml"):
                    os.utime(report_file, (1, 1))
                self.signer.main()
                import time
                for report_file in reports.glob("TEST-*.xml"):
                    os.utime(report_file, (time.time(), time.time()))
                original_reports = measured["reports"].copy()
                measured["reports"][next(iter(measured["reports"]))] = "0" * 64
                measured_file.write_text(json.dumps(measured))
                with self.assertRaises(SystemExit):
                    self.signer.main()
                measured["reports"] = original_reports
                measured_file.write_text(json.dumps(measured))
                for override in ({"RCAPROOF_ATTEST_BASE": "0" * 40}, {"RCAPROOF_ATTEST_COMMIT": "0" * 40},
                                 {"GITHUB_WORKFLOW_REF": "fixture/repository/.github/workflows/certify.yml@refs/heads/untrusted"}):
                    with patch.dict(os.environ, override), self.assertRaises(SystemExit):
                        self.signer.main()
                measured["runId"] = "different-run"
                measured_file.write_text(json.dumps(measured))
                with self.assertRaises(SystemExit):
                    self.signer.main()
                self.assertFalse((self.root / "artifacts/ci-attestation.json").exists())
            subject["checks"].pop()
            subject_file.write_text(json.dumps(subject))
            with self.assertRaises(SystemExit):
                self.signer.main()
            self.assertFalse((self.root / "artifacts/ci-attestation.json").exists())
            subject["checks"] = [dict(name=name, passed=True) for name in self.signer.REQUIRED_CHECKS]
            subject_file.write_text(json.dumps(subject))
            bad_report = reports / ("TEST-" + sorted(self.signer.REQUIRED)[0] + ".xml")
            bad_report.write_text(bad_report.read_text().replace('failures="0"', 'failures="1"'))
            with self.assertRaises(SystemExit):
                self.signer.main()


if __name__ == "__main__":
    unittest.main()
