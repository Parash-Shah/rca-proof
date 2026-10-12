# Architecture and proof boundaries

RCAProof separates proposed explanations from measured outcomes. Implemented milestones cover the duplicate-payment failure lab, bounded causal search, persistent incidents/evidence, deterministic classification, organization-aware planning, scoped verification, signed-evidence certification and certificate-gated draft PR integration. The review adapter preserves the exact certified commit; humans control merge and deployment.

## Executable components

- `rcaproof-core`: immutable evidence events, invariant observations, exhaustive counterfactual search and trial reports.
- `rcaproof-api`: PostgreSQL/Flyway persistence, HTTP evidence intake, timelines, incident lifecycle history, fenced job leases, resumable trials and persisted proofs. See [durability and proof boundaries](incident-api.md).
- `demo-system/payment-contract`: order contract and local-only AWS client configuration.
- `demo-system/order-service`: validates an order, conditionally stores it in DynamoDB, publishes it to SQS, and retrieves it.
- `demo-system/payment-worker`: receives a message, calls the HTTP provider, stores a payment result, then acknowledges SQS.
- `demo-system/external-payment-mock`: records independently observable charge effects; atomically deduplicates requests with a stable idempotency key.
- `PaymentCounterfactualIT`: isolated SQS queues and unique orders, real LocalStack clients, an in-process HTTP provider, and all eight combinations repeated ten times.

The provider mock is an external system from the worker's perspective. Its in-memory state deliberately survives worker restarts, but not provider restarts. This is not a durable payment service.

## Causal interpretation

The crash is a trigger. Redelivery is an expected transport mechanism. Missing payment idempotency is the remediable flaw. Forced redelivery without a crash can still cause a duplicate, so removing the crash does **not** establish that the defect is fixed.

The engine starts with the full-factor baseline, then the empty negative control, then all remaining interventions. It records every repetition. A minimal set fails consistently and all its proper subsets pass consistently. The engine does not assume monotonicity. Any incomplete or inconsistent cell prevents the overall report from becoming PROVEN. The supplied factor list is limited to three in V1, not just combinations of three out of an unlimited universe.

PROVEN means a reproducible causal relationship **within the named experimental scope**, not universal proof about production. These tests observe at most two deliveries per truth-table cell. Removing redelivery after a crash creates a deliberately incomplete execution: one charge exists, but the order may not complete. That observation addresses duplicate-payment safety only. It cannot certify availability or successful order processing.

The integration test uses a controlled exception at the crash boundary and reconstructs the worker processor. The Compose demo uses an actual JVM halt with exit code 86. Both leave the SQS message unacknowledged. In a no-crash intervention, the runner explicitly enqueues a second copy of the same order; in a crash intervention it makes the original message visible and verifies that its SQS message ID is unchanged.

## Why a ledger alone is insufficient

A conditional DynamoDB write cannot atomically commit a charge in an unrelated HTTP payment provider. A crash after charging but before persisting the charge result creates an ambiguous outcome. Marking a ledger complete first instead creates a lost-payment window.

The candidate therefore carries the stable key `payment:<orderId>` to a provider that deduplicates it atomically. DynamoDB records the returned charge result. The 22-check verification suite exercises provider retention, timeout reconciliation, concurrent delivery, ledger recovery, and liveness within the local mock's contract. [Certification](remediation-certification.md) requires matching source and archive bindings plus trusted signed CI evidence; its scope is the local lab.

## Local execution boundary

Demo AWS clients use dummy credentials and accept only loopback or the Compose `localstack` hostname over HTTP. Worker provider calls likewise accept only local mock hosts. Compose publishes APIs only on loopback. The application never uses the default AWS credential chain. Fault hooks belong to this synthetic lab.

## Known limitations

- Order persistence and SQS publication are a dual write. A publication failure can leave an order without a message; an outbox/reconciliation mechanism is future work.
- The ordinary demo provider is in memory. Verification uses an atomic single-process journal, 24-hour key cache and retained reconciliation records. This is a bounded mock contract, not a real provider durability guarantee.
- Evidence is ingested through the incident API as normalized caller-supplied records. Automatic production telemetry collectors, span graph reconstruction and independent evidence provenance verification are not implemented.
- The organization YAML is parsed and enforced for planning; scenario YAML remains documentation rather than a general executable scenario language.
- There is no LLM dependency, production fault adapter, arbitrary generated patch, PR creation, merge, or deployment. Certificates require committed patch evidence and a trusted CI authority; unbound local builds remain uncertified.
- The sample IntelliJ `src/Main.java` is preserved and excluded from the Maven modules.

## References

- [SQS at-least-once delivery](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues-at-least-once-delivery.html)
- [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
