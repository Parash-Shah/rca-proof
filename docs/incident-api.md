# Incident and evidence API

The API persists incidents, evidence, investigation jobs, trial checkpoints, lifecycle history and proofs in PostgreSQL using Flyway. It supports the synthetic duplicate-payment lab, a bounded access-policy model, and timeout abstention. See [classification and planning](classification-and-planning.md) for the additional scenarios and decision APIs. Automatic CloudWatch/OTel collectors and arbitrary production-code replay are future work.

For direct collection from the local payment services and measured HTTP requests, enable the [automatic telemetry inbox](automatic-telemetry.md). It creates and queues supported incidents without manually submitting the incident evidence below.

## Run

```powershell
docker compose up --build -d rcaproof-api
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-investigation.ps1
```

On Linux/macOS, first run `chmod +x infra/localstack/init.sh`. The API runs on `http://localhost:8084` (container port 8080). Set `RCAPROOF_API_PORT` and the script's `-BaseUrl` to use another host port. PostgreSQL and LocalStack start as dependencies. The script submits explicitly synthetic evidence, queues the investigation, polls its result, and saves the proof under `artifacts/`.

PostgreSQL uses the named `incident-data` volume: `docker compose down` preserves incidents; `docker compose down -v` deletes them. The local API is unauthenticated and bound to loopback through Compose. Use synthetic evidence only.

## Endpoints

| Request | Result |
| --- | --- |
| `POST /v1/incidents` | 201, incident and Location header |
| `GET /v1/incidents/{id}` | Incident and lifecycle status |
| `POST /v1/incidents/{id}/evidence` | Count of newly inserted events |
| `GET /v1/incidents/{id}/timeline` | Ordered events, receipt timestamps, uncertainty warnings and status history |
| `POST /v1/incidents/{id}/investigate` | 202, durable job and polling Location |
| `GET /v1/incidents/{id}/investigation` | Job, attempts and every trial, including abandoned trials |
| `GET /v1/incidents/{id}/proof` | Terminal PROVEN/INCONCLUSIVE artifact; 409 while pending |
| `GET /v1/incidents/{id}/classification` | Persisted deterministic decision from terminal proof; 409 while pending |
| `POST /v1/incidents/{id}/remediations` | Idempotent organization-aware plan or no-change outcome |
| `GET /v1/incidents/{id}/remediations` | Saved plan; 404 if not requested |

Unknown incidents return 404, invalid requests 400, and state or identity conflicts 409.

Create an incident:

```json
{
  "title": "Customer charged twice",
  "startedAt": "2026-01-01T00:00:00Z",
  "failureSignature": "DUPLICATE_PAYMENT",
  "order": {"orderId": "order-819", "customerId": "customer-29", "amount": 129.95}
}
```

Upload normalized evidence:

```json
{
  "events": [{
    "eventId": "e5fd5268-3f3b-465b-b685-5f136bda5e73",
    "timestamp": "2026-01-01T00:00:02Z",
    "service": "payment-provider",
    "eventType": "PAYMENT_SUCCEEDED",
    "orderId": "order-819",
    "traceId": "trace-819",
    "messageId": "message-281",
    "result": "SUCCESS",
    "metadata": {"chargeId": "charge-001"}
  }]
}
```

Batches contain 1–100 events; each incident supports at most 1000. All events must match the incident order. Repeating the same event ID and content is idempotent; changing its content rejects the entire batch.

Supported types: `ORDER_CREATED`, `PAYMENT_SUCCEEDED`, `WORKER_CRASHED`, `MESSAGE_RECEIVED`, `MESSAGE_ACKNOWLEDGED`, `TELEMETRY_GAP`.

Complete evidence needs one ORDER_CREATED event, at least two distinct successful charge IDs, repeated message receipt, and a worker crash correlated to the same message ID. Two copies of one charge log do not count as two charges. Missing correlation or explicit telemetry gaps cause abstention.

Queue the investigation with `{"repetitions": 10}`. The limit is 2–20 repetitions per factor combination. The response is immediate. Repeating this request returns the same job; a different repetition count returns 409. Evidence freezes atomically when the job is queued. Create a new incident to investigate a revised evidence snapshot.

## Recovery and proof gates

- PostgreSQL row locks with `SKIP LOCKED` select queued or expired work. Each claim receives a fresh fencing token.
- A worker renews its 90-second lease every ten seconds. Another worker can resume expired work after restart.
- Completed trials are durable checkpoints. Interrupted trials become ABANDONED and rerun with isolated resources. Old workers cannot write after their lease is lost.
- Jobs have a 15-minute budget from enqueue, including queue wait, and at most three execution attempts. The deadline is checked between trials and before proof commit; bounded in-flight SDK calls may finish after it. Exhausted jobs become INCONCLUSIVE when next claimed.
- Every full-factor baseline repetition must fail before REPRODUCED and INVESTIGATING enter the lifecycle history.
- Finalization checks the durable trial set and recomputes the causal result from stored observations before atomically saving a PROVEN status and proof.
- Database outages leave leases recoverable when PostgreSQL returns. Cleanup failures are recorded in the terminal reason; disposable LocalStack resources may remain and require cleanup.

The adapter uses isolated queues/tables and a loopback HTTP instance of the actual provider mock. It runs the actual PaymentProcessor with a controlled post-charge exception. The interactive demo worker's resources are not used. The separate crash demo exercises a real JVM halt.

## Interpretation and limitations

PROVEN is scoped to the named lab implementation, supplied order amount/customer, factors and bounded observation window. Incident evidence is caller supplied; its provenance is not independently authenticated. This is not a universal production RCA or a remediation certificate.

Each trial retains message IDs, charge IDs, generated order identity, effect counts, payment-result persistence and crash status. Safety is observed over two deliveries; liveness and provider key-retention guarantees are not certified.

The timeline sorts source timestamps, breaking ties by ingestion sequence, and explicitly warns that unsynchronized clocks cannot establish causal order. Missing trace IDs and declared telemetry gaps remain visible.

## Tests

`mvn verify` includes PostgreSQL and LocalStack Testcontainers tests for HTTP intake, migrations, atomic rollback, duplicate submissions, evidence freezing, background execution, proof retrieval after restart, checkpoint recovery, stale-worker rejection, budgets, infrastructure errors and failed negative controls.

API-focused verification: `mvn -pl rcaproof-api -am verify '-Dit.test=IncidentApiIT' '-Dfailsafe.failIfNoSpecifiedTests=false'`.
