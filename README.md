# RCAProof

Counterfactual root-cause verification and remediation certification for distributed systems.

**Don't trust an RCA because an explanation sounds plausible. Change the suspected causal conditions and measure whether the failure survives.**

RCAProof's goal is to collect incident evidence, experimentally establish minimal causal sets, distinguish defects from expected behavior, evaluate organization-compatible fixes, and verify remediation before opening a human-reviewed code change.

## Current milestone

Automatic collection is available for the real payment and HTTP retry labs. Run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-telemetry.ps1` to collect service events, detect failures and queue investigations without manually supplying incident evidence. See [automatic telemetry](docs/automatic-telemetry.md) for the isolated demo, fixed-mode control and delivery limits.

For a complete local demo with Prometheus, Grafana and automated checks of all three incident outcomes:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-demo.ps1
```

Linux/macOS: `sh scripts/start-demo.sh`. The API is at http://localhost:8084, Grafana at http://localhost:3001/d/rcaproof, and Prometheus at http://localhost:9091. See the [release runbook](docs/release-runbook.md) for prerequisites, source packaging, fresh-start validation and troubleshooting. The checker saves actual results under `artifacts/release-check-*.json`; it does not grant remediation certification.

The persistent Incident & Evidence API includes deterministic classification, organization-aware planning, sandbox verification, gated remediation certification and draft PR integration. Three synthetic demos produce an architecture-defect plan, expected behavior with no code change, and an inconclusive result with no patch. PostgreSQL/Flyway preserves evidence, certificates and review records. See the [planning contract](docs/classification-and-planning.md), [certification contract](docs/remediation-certification.md), [review workflow](docs/review-integration.md) and [roadmap](docs/roadmap.md).

Start the API and submit a synthetic incident:

```powershell
docker compose up --build -d rcaproof-api
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-investigation.ps1
```

For all three classification outcomes and a ranked remediation plan, run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-classification.ps1`.

The [HTTP retry-storm lab](docs/http-retry-lab.md) uses a separate WireMock container and correlated client/provider request counts. Run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-http-retry.ps1` to reproduce amplification, discover its causal interaction, and verify a bounded-retry candidate with 16 checks, including latency, caller deadlines, remaining-budget propagation and broken connections. Its separate signed certification contract binds retry-specific evidence to the exact candidate and incident signature.

Verify the implemented ledger/provider-key candidate with `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-verification.ps1`. The 22-check suite includes replay, nearby faults, concurrent deliveries, bounded load, durable-provider restart/expiry/reconciliation and guarded migration/rollback. A passing result is `VERIFIED_IN_SANDBOX`. [Remediation certification](docs/remediation-certification.md) additionally requires a clean committed build and matching signed CI evidence from an operator-trusted authority.

The API runs at `http://localhost:8084`. Evidence freezes at investigation start, completed trials survive restarts, and incomplete evidence or failed controls produce `INCONCLUSIVE`. See the [API contract and recovery rules](docs/incident-api.md). On Linux/macOS, make `infra/localstack/init.sh` executable before starting Compose.

For a certified verification, `scripts/review-remediation.ps1 -VerificationId <id>` saves a review preview. Add `-Publish` to create or reconcile a draft PR after configuring GitHub access and the local publication key. Candidate certification uses a separate approved workflow before merge. No merge or deployment is automated.

An additional 32-case evaluation covers eight explicitly scoped Java behavioral models. It complements the real LocalStack/HTTP payment lab; it is not a dataset of 32 reproduced production incidents. Results are generated under `rcaproof-core/target/scenario-evaluation.*`. Operational counts are available from `/v1/metrics`, recent records from `/v1/overview`, and scenario definitions from `/v1/scenarios`.

```text
POST /orders -> DynamoDB order + SQS -> Payment worker -> HTTP payment provider
                                              |                 |
                                         payment result    charge side effect
                                              |
                                         DynamoDB -> ACK
```

The worker can halt immediately after the provider charges, before persisting the result or acknowledging the message. Redelivery then creates a second charge in the vulnerable implementation. The fixed mode sends a stable provider idempotency key and reuses the original charge result.

## Verify

With Java 21, Maven 3.9+ and Docker running:

```sh
mvn -B -ntp verify
```

On Windows with Docker Desktop, no local Java/Maven/Python installation is required. This command runs the Python certification-tool tests followed by the full Java suite:

```powershell
.\scripts\verify.ps1
```

For unit tests alone: `mvn test` or `.\scripts\verify.ps1 -UnitOnly`. Integration tests require Docker and fail when it is unavailable; they are not silently skipped.

If Windows blocks local scripts, run a single invocation with `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify.ps1` (or the demo script path). This does not change the machine's execution policy.

Verification runs eight factor combinations, ten repetitions each, against LocalStack SQS/DynamoDB and a real HTTP test provider. It also tests recovery after a crash followed by ten additional copies, and concurrent idempotency behavior in the demo provider.

API integration tests additionally use PostgreSQL to verify HTTP intake, background investigations, persistent proofs, restart recovery, stale-worker rejection, evidence freezing and abstention gates.

The resulting artifact is:

```text
demo-system/payment-worker/target/counterfactual-proof.json
```

It records every trial and the observed minimal causal set. PROVEN is scoped to the specified sandbox and duplicate-payment invariant; it is not a remediation certificate or a production claim. Missing effects, failed experiments, inconsistent repetitions and unhealthy negative controls prevent proof.

## Run the actual process-crash demo

Docker Desktop must be running. The script builds the services, submits a unique order, waits for the worker to exit with code 86, restarts it without the crash hook, then checks the persisted payment and provider side effects.

```powershell
# Vulnerable: one order, two charges
.\scripts\demo-duplicate-payment.ps1

# Candidate fix: same crash and redelivery, one charge
.\scripts\demo-duplicate-payment.ps1 -Fixed

# Stop the lab when done
docker compose down
```

The scripts leave the lab running for inspection. The provider stores effects in memory, so restarting it resets its history. Run this dedicated Compose project without other clients submitting orders during the demo.

Manual healthy startup:

```sh
chmod +x infra/localstack/init.sh # Linux/macOS checkouts
docker compose up --build -d
curl -X POST http://localhost:8081/orders -H 'Content-Type: application/json' \
  -d '{"orderId":"order-819","customerId":"customer-29","amount":129.95}'
curl http://localhost:8081/orders/order-819
curl 'http://localhost:8082/effects?orderId=order-819'
```

Local ports: incident API `8084` (configurable through `RCAPROOF_API_PORT`), order API `8081`, provider `8082`, LocalStack `4566`, PostgreSQL `5433`. All published ports bind to loopback. Lab clients use synthetic credentials and local endpoints. The local API is unauthenticated and intended for synthetic evidence. PostgreSQL data survives `docker compose down` in a named volume.

## Repository

| Path | Purpose |
| --- | --- |
| `rcaproof-core/` | Evidence types, failure observations, exhaustive causal search |
| `rcaproof-api/` | Incident/evidence API, Flyway schema, durable job worker and sandbox adapter |
| `demo-system/` | Order API, worker, external provider mock and shared contracts |
| `scenarios/duplicate-payment/` | Documented scenario contract and causal roles |
| `organization/` | Synthetic organization profile for future policy evaluation |
| `infra/localstack/` | Local queue/table initialization |
| `scripts/` | Reproducible Windows verification and process-crash demo |
| `docs/` | Architecture, boundaries, known limitations and roadmap |

Import the root `pom.xml` in IntelliJ and select JDK 21. The original `src/Main.java` scratch file is preserved; Maven builds only the declared modules.

## Engineering boundaries

A crash is a trigger, redelivery is a transport mechanism, and missing idempotency is the latent defect. [SQS standard delivery is at least once](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html). Increasing visibility timeout does not establish that duplicate side effects are impossible.

A DynamoDB ledger by itself cannot atomically commit a remote provider charge. The implemented candidate assumes the provider atomically honors and retains idempotency keys. The [architecture notes](docs/architecture.md) explain that assumption, the deliberate order/SQS dual-write limitation, and why duplicate safety does not prove liveness.

AI may eventually propose explanations and patches. Code must decide experimental outcomes, policy permission and certification. V1 never auto-merges or deploys.
