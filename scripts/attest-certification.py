"""Sign independently measured CI results. Never accept caller-supplied PASS booleans."""
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import argparse
import re

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = {"dev.rcaproof.causal.CounterfactualEngineTest", "dev.rcaproof.remediation.RemediationPlannerTest",
            "dev.rcaproof.demo.order.OrderApplicationTest", "dev.rcaproof.demo.order.TelemetryClientTest", "dev.rcaproof.demo.worker.PaymentCounterfactualIT",
            "dev.rcaproof.demo.provider.ProviderApplicationTest", "dev.rcaproof.api.IncidentApiIT",
            "dev.rcaproof.api.CertificationGateTest", "dev.rcaproof.experiment.ScenarioEvaluationTest", "dev.rcaproof.api.GitHubReviewClientTest", "dev.rcaproof.api.HttpRetrySandboxIT", "dev.rcaproof.api.HttpTransportSandboxIT"}
REQUIRED_CHECKS = {"BASELINE_0", "BASELINE_1", "REPLAY_0", "REPLAY_1", "COUNTERFACTUAL_0", "COUNTERFACTUAL_1",
                   "COUNTERFACTUAL_2", "COUNTERFACTUAL_3", "DUPLICATES_10", "CRASH_TWICE", "PROVIDER_TIMEOUT",
                   "LEDGER_UNAVAILABLE", "DELAYED_ACK", "CONCURRENT_DUPLICATES", "BOUNDED_LOAD", "PROVIDER_RESTART",
                   "KEY_EXPIRY", "RETENTION_BOUNDARY", "LEDGER_RETENTION", "LEDGER_CONFLICT",
                   "MIGRATION_COMPATIBILITY", "ROLLBACK_COMPATIBILITY"}
RETRY_CHECKS = {"RETRY_BASELINE_0", "RETRY_BASELINE_1", "RETRY_PERSISTENT_500", "RETRY_HEALTHY", "RETRY_TRANSIENT_500",
                "RETRY_CLIENT_400", "RETRY_REJECT_DISABLED", "RETRY_REJECT_LONG_TIMEOUT", "RETRY_CONCURRENT",
                "HTTP_LATENCY_BASELINE", "HTTP_DEADLINE", "HTTP_BUDGET_PROPAGATION", "HTTP_TRANSPORT_HEALTHY",
                "HTTP_CONNECTION_LOSS", "HTTP_TRUNCATED_RESPONSE", "HTTP_EXPIRED_DEADLINE"}
CONTRACTS = {
    "payment": ("IDEMPOTENCY_LEDGER", "DUPLICATE_PAYMENT", "payment-verification-v2", REQUIRED_CHECKS, "certification-subject.json"),
    "http-retry": ("BOUNDED_RETRY", "RETRY_STORM", "http-retry-verification-v2", RETRY_CHECKS, "certification-subject-http-retry.json"),
}

def validate_subject(subject, contract):
    candidate, scenario, version, checks, _ = contract
    if (subject.get("candidateId") != candidate or subject.get("failureSignature") != scenario
            or subject.get("status") != "VERIFIED_IN_SANDBOX" or subject.get("suiteVersion") != version
            or len(subject.get("checks", [])) != len(checks) or {c["name"] for c in subject["checks"]} != checks
            or any(c["passed"] is not True for c in subject["checks"])):
        raise SystemExit("Runtime candidate/scenario contract or complete suite did not pass")


def main():
    output = ROOT / "artifacts/ci-attestation.json"
    output.unlink(missing_ok=True)
    source_file = ROOT / "build-evidence/source.json"
    source = json.loads(source_file.read_text(encoding="utf-8"))
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=ROOT)
    if git("status", "--porcelain", "--untracked-files=all").strip() or git("rev-parse", "HEAD").decode().strip() != source["commit"]:
        raise SystemExit("Source changed after manifest generation")
    if git("rev-parse", "HEAD^{tree}").decode().strip() != source["tree"]:
        raise SystemExit("Source tree does not match the manifest")
    patch = git("diff", "--binary", "--full-index", "--no-ext-diff", "--no-textconv", source["baseCommit"], source["commit"], "--")
    if not patch or hashlib.sha256(patch).hexdigest() != source["patchSha256"]:
        raise SystemExit("Reviewable patch does not match the source manifest")
    event = os.environ.get("GITHUB_EVENT_NAME")
    approved_candidate = event == "workflow_dispatch" and os.environ.get("GITHUB_WORKFLOW_REF", "") == os.environ.get("GITHUB_REPOSITORY", "") + "/.github/workflows/certify.yml@refs/heads/main"
    expected_commit = os.environ.get("RCAPROOF_ATTEST_COMMIT") if approved_candidate else os.environ.get("GITHUB_SHA")
    if expected_commit != source["commit"] or (event != "push" and not approved_candidate) or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise SystemExit("Only main-branch CI or the approved candidate-certification workflow may attest")
    if approved_candidate and os.environ.get("RCAPROOF_ATTEST_BASE") != source["baseCommit"]:
        raise SystemExit("Candidate base differs from the approved review base")
    selected = os.environ.get("RCAPROOF_CERTIFICATION_CONTRACT", "payment")
    if selected not in CONTRACTS:
        raise SystemExit("Unsupported certification contract")
    contract = CONTRACTS[selected]
    subject_file = ROOT / "rcaproof-api/target" / contract[4]
    subject = json.loads(subject_file.read_text(encoding="utf-8"))
    validate_subject(subject, contract)
    if subject["binding"]["source"] != source or subject["binding"]["problem"] is not None:
        raise SystemExit("Verification subject differs from the clean source manifest")
    archive = ROOT / "rcaproof-api/target/rcaproof-api-0.1.0-SNAPSHOT-exec.jar"
    if hashlib.sha256(archive.read_bytes()).hexdigest() != subject["binding"]["applicationSha256"]:
        raise SystemExit("Application archive changed after verification")
    files = sorted(list(ROOT.glob("**/target/surefire-reports/TEST-*.xml")) + list(ROOT.glob("**/target/failsafe-reports/TEST-*.xml")))
    measured = None
    if not approved_candidate and subject_file.stat().st_mtime < source_file.stat().st_mtime:
        raise SystemExit("Stale runtime verification subject")
    if approved_candidate:
        measured = json.loads((ROOT / "rcaproof-api/target/ci-environment.json").read_text())
        if measured.get("subjects", {}).get(subject_file.relative_to(ROOT).as_posix()) != hashlib.sha256(subject_file.read_bytes()).hexdigest():
            raise SystemExit("Transferred runtime subject differs from the measured build job")
        if (measured.get("runId") != os.environ.get("GITHUB_RUN_ID") or measured.get("commit") != source["commit"]
                or measured.get("sourceManifestSha256") != hashlib.sha256(source_file.read_bytes()).hexdigest()
                or set(measured.get("reports", {})) != {p.relative_to(ROOT).as_posix() for p in files}):
            raise SystemExit("Transferred evidence belongs to a different candidate run or report set")
    digest = hashlib.sha256()
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = set()
    for path in files:
        if not approved_candidate and path.stat().st_mtime < source_file.stat().st_mtime:
            raise SystemExit(f"Stale test report: {path.relative_to(ROOT)}")
        content = path.read_bytes()
        if approved_candidate and measured["reports"][path.relative_to(ROOT).as_posix()] != hashlib.sha256(content).hexdigest():
            raise SystemExit("Transferred report differs from the measured build job")
        suite = ET.fromstring(content)
        if suite.tag != "testsuite" or int(suite.attrib.get("tests", "0")) == 0:
            raise SystemExit("Empty or unsupported test report")
        name = suite.attrib["name"]
        if name in suites:
            raise SystemExit(f"Duplicate test suite: {name}")
        suites.add(name)
        for key in counts:
            counts[key] += int(suite.attrib.get(key, "0"))
        digest.update(path.relative_to(ROOT).as_posix().encode() + b"\0" + content + b"\0")
    if not REQUIRED <= suites or counts["tests"] == 0 or any(counts[k] for k in ("failures", "errors", "skipped")):
        raise SystemExit("Missing, failed or skipped regression tests prevent attestation")
    environment = {"runnerOS": platform.platform(), "python": platform.python_version()}
    images = ("localstack/localstack:4.7.0", "postgres:16-alpine", "wiremock/wiremock:3.13.1")
    if approved_candidate:
        environment = measured["environment"]
        if any(not isinstance(environment.get(image), str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", environment[image]) for image in images):
            raise SystemExit("Measured image inventory is incomplete")
    else:
        for image in images:
            environment[image] = subprocess.check_output(["docker", "image", "inspect", image, "--format", "{{.Id}}"], text=True).strip()
    payload = json.dumps(dict(source=source, applicationSha256=subject["binding"]["applicationSha256"],
        candidateDigest=subject["candidateDigest"], organizationDigest=subject["organizationDigest"], suiteVersion=subject["suiteVersion"],
        candidateId=subject["candidateId"], failureSignature=subject["failureSignature"],
        testReportsSha256=digest.hexdigest(), **counts, testSuites=sorted(suites), repository=os.environ["GITHUB_REPOSITORY"],
        workflow=".github/workflows/certify.yml" if approved_candidate else ".github/workflows/ci.yml", runId=os.environ["GITHUB_RUN_ID"],
        issuedAt=datetime.now(timezone.utc).isoformat(), environment=environment), sort_keys=True, separators=(",", ":"))
    private_key = os.environ.get("RCAPROOF_CI_SIGNING_KEY", "")
    if not private_key:
        raise SystemExit("CI signing key is not configured; no attestation can be issued")
    with tempfile.TemporaryDirectory(prefix="rcaproof-sign-") as directory:
        key = Path(directory) / "key.pem"
        key.write_text(private_key, encoding="utf-8")
        key.chmod(0o600)
        message = Path(directory) / "payload.json"
        message.write_bytes(payload.encode())
        signature = subprocess.check_output(["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(message)])
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({"payload": payload, "signature": base64.b64encode(signature).decode()}), encoding="utf-8")
    print("Saved signed CI attestation to artifacts/ci-attestation.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", type=Path, default=ROOT)
    ROOT = parser.parse_args().workspace.resolve()
    main()
