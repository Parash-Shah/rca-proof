# Remediation certification

Certification is a deterministic, persisted decision about an exact build and a named experimental contract. It never certifies arbitrary production infrastructure or authorizes merge/deploy. Certificates retain the explicit local payment or HTTP lab scope.

| Workflow contract | Candidate / incident | Suite | Required runtime checks |
| --- | --- | --- | --- |
| `payment` | `IDEMPOTENCY_LEDGER` / `DUPLICATE_PAYMENT` | `payment-verification-v2` | 22 |
| `http-retry` | `BOUNDED_RETRY` / `RETRY_STORM` | `http-retry-verification-v2` | 16 |

The signed payload includes `candidateId` and `failureSignature`. The issuer also checks the persisted incident signature, candidate fingerprint and exact suite. A payment attestation cannot certify a retry candidate, even if other supplied digests match. Legacy attestations without these identity fields must be reissued; existing records are preserved but become stale on revalidation.

## Issuance gates

1. The candidate's complete named suite completed with `VERIFIED_IN_SANDBOX`; the report must match the persisted check set. Unsupported candidates, old suite versions and incomplete coverage cannot certify.
2. The executable archive includes a clean Git commit, base commit, source tree and exact binary patch SHA-256. The patch is packaged with the artifact. Missing manifests and dirty development builds cannot certify.
3. SHA-256 of the complete executable archive, all nested dependency JARs, candidate implementation, organization policy, suite version and runtime contract bind the result. The report records Java runtime, OS/architecture, retention assumptions, workload and rollback constraints.
4. An operator-trusted Ed25519 authority signed independent CI results for the **same** source/patch, application archive, candidate, incident signature, policy and suite. The signed payload identifies repository, workflow, run, report digest, suite names, counts and CI environment/image IDs.
5. Required regression suites exist with no failures, errors or skips. Attestations expire seven days after issuance; more than five minutes of future clock skew is rejected.

The service verifies the exact signed payload bytes before parsing. It rejects unknown/duplicate JSON fields, wrong keys, tampered signatures, changed subjects, absent coverage and stale evidence. Clients cannot certify by posting a `passed: true` flag.

## Added operational experiments

The HTTP contract covers the [16 retry and transport checks](http-retry-lab.md): amplification, transient recovery, negative controls, concurrent budgets, caller deadlines, one-hop budget propagation and broken responses. Its proof remains limited to HTTP 500 amplification. Its certificate does not claim payment safety, production load targets or multi-hop cancellation. The following operational assumptions apply to the payment contract only.

The provider supports an optional atomic file journal and retains authoritative charge/key/intent records. Restarting the provider object reloads that journal. Its 24-hour request-key cache expires, but an expired key refuses a new charge; the worker reconciles against the retained durable charge and validates order/customer/amount. This explicitly models a provider with durable reconciliation, not every real payment API. Atomic journal replacement and file flush are tested; filesystem/power-loss guarantees and multi-process provider concurrency are outside this contract.

Seven additional checks cover provider restart after charging, key expiry after worker crash, the exact retention boundary, loss of the local ledger after cache expiry, conflicting ledger intent, legacy migration and rollback. The worker refuses conflicting or incomplete ledger records before charging or acknowledging. Migration tests require an authoritative backfill; they do not silently invent missing customer/amount data. Rollback is only to the provider-key-preserving worker within retention after draining in-flight work. Reverting to the original non-idempotent configuration is forbidden by the contract.

Load certification is the explicitly declared local target: 20 distinct orders, two copies each, one effect and matching result per order, drained queue and completion within 120 seconds. This is not a production throughput/SLO certificate. Configure and implement a separate contract before claiming a different workload.

## Build and CI

From a **clean committed checkout**:

```sh
python3 scripts/prepare-certification.py --base <base-commit>
mvn -B -ntp clean verify
```

The preparation script rejects tracked modifications and untracked source, verifies ancestry, and writes `build-evidence/source.json` and `candidate.patch`. Generated files are ignored by Git and packaged as resources. It removes stale generated claims before validation. Commit the actual work first; this tool never stages or commits files for you.

The normal GitHub workflow runs preparation, a clean Maven regression build and container checks without a signing secret. The separate [candidate-certification workflow](review-integration.md) tests an immutable candidate, then signs in a distinct job protected by the `certification` environment. Configure required reviewers, a main-only environment branch rule and the environment secret `RCAPROOF_CI_SIGNING_KEY`. Do not store this key as a repository-wide secret. The trusted main-branch signer reads fresh JUnit XML, requires named suites, checks the tested archive against the exported verification subject and rechecks clean Git identity before signing. Test fixture certificates are never exported as CI subjects.

Select `payment` or `http-retry` in the workflow's `contract` input. `RCAPROOF_CERTIFICATION_CONTRACT` passes that selection to the trusted signer; the default is `payment`. The real integration tests export separate `certification-subject.json` and `certification-subject-http-retry.json` reports. The transfer manifest hashes both reports, alongside the JUnit reports and source manifest. The signer checks the selected subject's content hash after artifact download and rejects a mismatched candidate, signature, version or check set. Both contracts require the complete shared regression inventory, including the HTTP transport suite.

Set `RCAPROOF_CI_PUBLIC_KEY` on the API to the corresponding X.509 public key (PEM or base64 DER). Public keys are configuration, not accepted from API requests. Changing/removing the trusted key makes previously issued certificates stale on read. No default development private key or automatic trust bootstrap is supplied.

CI uploads the executable JAR, source manifest, patch, test reports, verification subject and signed envelope. Deploy the **same CI-built executable archive**: independently rebuilding can legitimately change its digest. A runtime verification must use this identical artifact/policy and the named lab contract. The certificate records runtime environment separately from the signed CI runner environment. The local API is unauthenticated and intended for synthetic lab data; this does not turn it into a production authority.

## API

- `POST /v1/verifications/{id}/certificate` accepts `{"payload":"<exact signed JSON string>","signature":"<base64 Ed25519 signature>"}`. It returns `CERTIFIED` with an immutable certificate ID only if every gate passes. Otherwise it returns `NOT_CERTIFIED` with blockers and writes no certificate. Pending verification returns 409; unknown IDs return 404; malformed envelopes return 400.
- `GET /v1/verifications/{id}/certificate` returns the latest certificate or current blockers. Every read revalidates signature, freshness, build, policy and report binding. An issued certificate whose assumptions no longer match returns `STALE`; its original record is preserved for audit.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/submit-certificate.ps1 -VerificationId <id> -AttestationFile artifacts/ci-attestation.json
```

Successful issuance is idempotent for the same verification/evidence digest. Certificates retain the verification-report digest, signed-evidence digest, exact build/dependency/environment binding, CI claims, issuance/expiry times and scope. New signed evidence may produce another immutable certificate; a rejected submission does not replace a valid one. Database persistence protects against restarts, not an administrator who rewrites the database and holds the signing key.

A dirty local checkout with no CI authority still runs the selected verification suite. It should return `NOT_CERTIFIED`. That is the expected demonstration of the trust boundary, not a reason to fabricate a signature or mark unrun gates as passing.
