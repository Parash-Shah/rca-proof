# Classification and organization-aware planning

Run the three synthetic outcomes against the local API:

```powershell
docker compose up --build -d rcaproof-api
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-classification.ps1
```

The script asserts outcomes and saves proof/decision artifacts in `artifacts/`. It leaves the API running at http://localhost:8084.

| Incident | Investigation | Classification | Action |
| --- | --- | --- | --- |
| Duplicate payment | LocalStack + actual worker/provider experiments | ARCHITECTURE_DEFECT | Rank remediation candidates |
| Access denied | Synthetic local policy interventions | EXPECTED_BEHAVIOR | Explain permission requirement; no code change |
| Intermittent timeout | No distinguishing intervention; zero trials | UNKNOWN, proof INCONCLUSIVE | Request telemetry; no patch |

## API

After the existing evidence/intake/investigate workflow completes:

- `GET /v1/incidents/{id}/classification` returns (and caches) a deterministic classification of the immutable terminal proof. It includes investigation ID, rule version, scope, category, reason, action and customer message.
- `POST /v1/incidents/{id}/remediations` creates an idempotent persisted planning artifact. No request body is required.
- `GET /v1/incidents/{id}/remediations` retrieves that artifact; 404 if not requested.

Classification and planning return 409 before a terminal proof exists and 404 for unknown incidents. An inconclusive or unrecognized proof yields UNKNOWN and no candidates. Expected behavior yields NO_CODE_CHANGE and no candidates. Classification does not start experiments. Concurrent requests serialize on the incident; repeated planning returns the original artifact, including its exact organization snapshot.

Creation supports `DUPLICATE_PAYMENT`, `ACCESS_DENIED`, `INTERMITTENT_TIMEOUT`. Only duplicate payments require an `order`; other scenarios omit it. Non-payment evidence can omit `orderId` and is scoped by the incident URL.

Access-denied evidence must contain exactly one event with `eventType: ACCESS_DENIED`, `result: DENIED`, nonempty requestId/resource, `operation: GET`, and metadata:

```json
{"serviceHealthy":"true","resourceExists":"true","policyVersion":"demo-read-policy-v1","hasReadPermission":"false"}
```

This is an explicitly synthetic request/policy snapshot, not verified production IAM telemetry. The local evaluator returns 403 without read permission and 200 with it. The engine tests missing permission and a deployment-label control across all four combinations, with repeated trials. Both deployment labels use the same supported policy implementation. The proof only establishes behavior in this small model. Missing fields, unsupported snapshots, additional ambiguous events, and declared gaps prevent an expected-behavior classification. The denial is the reported failure signature; its reproduction does not imply a software defect.

Timeout scenarios currently always abstain because no distinguishing timeout sandbox is implemented. A REQUEST_TIMEOUT event is sufficient to demonstrate this outcome; it is not evidence that a particular dependency caused the timeout.

## Planning rules

`organization/demo-org.yaml` is packaged into the API. Rebuild/restart after edits. Startup rejects missing or malformed required planning fields. Every plan retains the YAML bytes as text, their SHA-256, parsed capabilities, investigation ID, creation time and catalog version. Changing the profile does not rewrite historical plans; create a new incident for a new evaluation.

The catalog evaluates three alternatives:

1. Visibility extension: rejected for incomplete causal coverage; a crash can still cause replay.
2. FIFO migration: rejected for incomplete causal coverage; queue deduplication does not make the external charge idempotent.
3. DynamoDB ledger **plus provider idempotency**: eligible for verification in the demo organization. The stable provider key must survive retries/restarts, retention must cover replay, and unknown outcomes must be reconcilable. A ledger alone cannot close the charge-before-record crash window.

Heuristic score maxima are causal coverage 40, organization fit 20, reliability 20, operational simplicity 10, and reversibility 10. Each candidate exposes its component scores, coverage, assumptions, risks, policy reasons and required validation scenarios. These are catalog judgments, not measured reliability or probabilities. Causal coverage and policy gates override scores. Missing DynamoDB requires new infrastructure; the demo policy requires architecture review, so that candidate cannot be recommended automatically. Unsupported Java/SQS capabilities or automatic merge/deploy policy also block candidates. Runtime/observability/IaC fields are preserved as context; no deployment compatibility is claimed from them.

Plans are immutable `PLANNED_NOT_VERIFIED` snapshots; their initial candidate verification is `NOT_RUN`. Subsequent [verification jobs](remediation-verification.md) record replay, nearby failures, safety, completion and bounded-load outcomes separately. [Certification](remediation-certification.md) is a separate gate requiring matching build provenance and trusted signed CI evidence. Planning itself generates no arbitrary code patch or certificate, and performs no PR, merge or deployment.
