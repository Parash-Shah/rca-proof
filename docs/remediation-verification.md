# Remediation verification

The selected `IDEMPOTENCY_LEDGER` implementation combines a stable provider key with a conditional DynamoDB result write. It is compiled into the lab and explicitly enabled only in the verification sandbox. The normal vulnerable worker remains available for reproducing incidents. This is a concrete hand-written candidate adapter, not arbitrary AI patch generation.

The write accepts a new result or the same charge/customer/amount; a conflicting result fails without acknowledging the message. Charging still precedes the ledger write. The provider's stable key closes that crash window under the mock's assumptions. A fresh PaymentProcessor instance handles retries, so correctness does not depend on worker memory.

## Run

```powershell
docker compose up --build -d rcaproof-api
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-classification.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-verification.ps1
```

Pass `-IncidentId <id>` to verify an existing proven duplicate-payment incident. The script saves the report to `artifacts/verification-<id>.json` and asserts the bounded verification status and the absence of a full certificate.

## API and durable jobs

- `POST /v1/incidents/{incidentId}/remediations/IDEMPOTENCY_LEDGER/verify`: 202 with verification ID and polling Location. Creates the plan if necessary, rejects unproven/no-change incidents and ineligible candidates (409), and is idempotent per incident/candidate.
- `GET /v1/verifications/{id}`: status, attempt, deadline, recorded checks and terminal report. Unknown IDs return 404.
- `GET /v1/verifications/{id}/certificate`: 409 while pending; afterward returns `NOT_CERTIFIED` with outstanding gates, `CERTIFIED` for valid signed evidence, or `STALE` when a previously issued certificate no longer satisfies the gates.

Jobs use a 90-second fenced lease, heartbeat, 15-minute total budget and at most three execution attempts. An interrupted attempt restarts in a new isolated namespace; old check rows remain in PostgreSQL but never satisfy the new attempt's coverage gates. The API exposes checks from the current attempt. A changed candidate bytecode fingerprint prevents queued work from silently verifying a different implementation. Changed organization policy prevents enqueue or successful finalization under a stale plan. Successful results survive API restarts.

## Required checks

| Check | Intervention and requirement |
| --- | --- |
| Baseline x2 | Original vulnerable implementation reproduces duplicate effects |
| Incident replay x2 | Candidate survives charge-before-record crash and redelivery |
| Four counterfactual combinations | Crash/redelivery factors removed/injected against candidate; no duplicate charge; completion where redelivery permits it |
| Duplicate deliveries x10 | One charge and one matching saved result |
| Worker crashes twice | Both fault hooks execute; subsequent retry completes |
| Provider timeout | HTTP provider commits a charge then delays its response past the client timeout; retry reconciles through the same provider key |
| Ledger unavailable | DynamoDB rejects the candidate's pre-charge read from an unavailable test table; retry against the restored table completes |
| Delayed ACK | Visibility expires while first worker is paused; another worker completes the same message |
| Concurrent duplicates | Ten messages for one order processed concurrently |
| Bounded load | Twenty orders, two copies each, concurrent processing; exactly one matching charge/result per order and drained queue within 120 seconds |

The broader checks require both safety and completion: one charge per order, a matching persisted payment, drained queue, expected fault-injection count and a completion bound. The no-redelivery crash control intentionally cannot complete: it tests safety only and is labelled accordingly. Baselines, interventions, outcomes, counts and durations are stored rather than inferred by an LLM.

All 22 named checks must exist exactly once and pass for `VERIFIED_IN_SANDBOX`. The seven added provider/retention/conflict/migration/rollback cases are described in the [certification contract](remediation-certification.md). A failed invariant yields `FAILED`; infrastructure exceptions, missing coverage, time-budget expiry or candidate/profile changes yield `INCONCLUSIVE`. These outcomes never authorize a PR, merge or deployment. The Maven regression suite tests both the original lab and candidate workflow independently of runtime verification.

## Certification boundary

Reports include the suite version, candidate digest, saved organization digest, measurements and scope. When source evidence is packaged, they also retain the committed patch binding, full application archive digest, dependency inventory and runtime contract. [Certification](remediation-certification.md) additionally verifies a trusted signed CI attestation for the identical subject.

The provider verification mock now persists its journal and supports expiry/reconciliation. Ledger unavailability uses a missing test table (the candidate detects it before charging), not a network-level DynamoDB timeout; worker crashes use controlled exceptions, not OS termination. Bounded load remains a functional workload, not a production SLO claim. Certificates preserve these limits; they do not erase them.
