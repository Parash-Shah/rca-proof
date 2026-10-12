# HTTP retry-storm experiments

This milestone adds real HTTP I/O to the retry investigation: a Java HTTP client calls a separate WireMock container. The lab injects HTTP 500 responses, records the client's response sequence, and independently queries the dependency's request journal. Matching request IDs and attempt ordinals are required before a trial can establish an outcome.

It remains a local, bounded GET experiment. It does not model a production fleet, real service capacity, an HTTP side effect, arbitrary network partitions, or AWS infrastructure. The version 2 verification suite adds controlled latency and broken-connection interventions.

## Reproduction and proof

```powershell
docker compose up --build -d rcaproof-api
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-http-retry.ps1
```

`POST /v1/labs/retry-storm` executes a six-attempt client against a faulting dependency, then persists the measured incident and six normalized evidence events. Event timestamps mark when the completed sample was observed, not invented per-attempt start times. Manual evidence intake remains supported but is caller-supplied; it is not independently authenticated production telemetry.

Investigation tests all eight combinations of:

- `DEPENDENCY_HTTP_500`: return 500 instead of 200.
- `EXCESSIVE_RETRIES`: allow six attempts instead of two.
- `ALTERNATE_DEPLOYMENT`: change the deployment header, a negative causal control.

The failure signature is **more than three dependency requests for one logical GET**. A persistent 500 returned after two attempts is a healthy result for this amplification invariant; it does not mean the downstream service recovered. A normal dependency must return 200 on the negative control. Missing or mismatched provider receipts prevent proof. Infrastructure exceptions produce `INCONCLUSIVE`.

The expected minimal causal set is `{DEPENDENCY_HTTP_500, EXCESSIVE_RETRIES}`. Changing deployment alone should not fix the issue. The scope/version is `http-retry-storm-v1`; old behavioral-model evidence is not accepted as evidence of this HTTP incident.

## Candidate verification

The planner recommends `BOUNDED_RETRY` when the synthetic organization allows Java and human-controlled changes. The candidate allows two attempts with a fixed 25ms delay and retries only 5xx responses. It shares a three-second caller budget across attempts; tighter budgets can be supplied for a caller with less time remaining. The original nine persisted checks cover:

1. Two baseline reproductions using the original six-attempt policy.
2. Persistent 500: two attempts and the final error is surfaced.
3. Healthy dependency: one request and HTTP 200.
4. Transient 500: two requests and eventual HTTP 200.
5. HTTP 400: one request without retry.
6. Disabling retries: experimentally rejected because transient recovery fails.
7. Increasing timeout: experimentally rejected because six immediate 500 responses still amplify requests.
8. Four concurrent operations: separate request identities and two attempts per operation.

Version 2 adds seven checks, for **16 required checks**:

- Delayed baseline: a 1,200ms response completes under the per-attempt timeout, exceeding the declared 600ms caller budget when that budget is not enforced.
- Deadline intervention: the same delayed dependency is interrupted at the shared 600ms deadline and reported as `DEADLINE_EXCEEDED`. The local timing assertion allows 400ms of scheduler tolerance; it is not a production latency SLO.
- Budget propagation: two delayed HTTP 500 responses receive decreasing `X-RCAProof-Remaining-Ms` values, correlated with the client records. The 25ms retry delay consumes the same budget.
- Healthy transport control: HTTP 200 completes in one request.
- Connection loss: WireMock closes without a response; the client surfaces `TRANSPORT_ERROR`.
- Truncated response: invalid response chunks and connection closure must produce `TRANSPORT_ERROR`, never a successful HTTP 200 merely because headers arrived.
- Expired deadline: zero remaining budget produces no dependency request.

The deadline uses `System.nanoTime()` locally and covers the response body as well as headers. The relative header demonstrates one-hop propagation; WireMock records it but does not implement a downstream service's deadline enforcement. It is not a synchronized multi-hop deadline or proof that remote work stopped after cancellation. Transport errors are not retried by the application; the JDK may replay an idempotent GET internally. Provider receipts expose those requests and the fault checks require at most two wire requests for one application attempt. More requests or missing correlation fail validation.

Use the existing remediation verification API with candidate `BOUNDED_RETRY`. Passing all 16 checks produces `VERIFIED_IN_SANDBOX` under `http-retry-verification-v2`. The old nine-check suite cannot satisfy the new gate. Missing coverage, infrastructure failures and expired worker leases cannot pass. The candidate implementation, saved policy and runtime binding are checked just as they are for payment verification. Existing persisted version 1 reports retain their original scope; create a fresh demo incident to run version 2.

Retry verification now has a separate [signed certification contract](remediation-certification.md). Select `http-retry` in the candidate-certification workflow, run the identical CI archive locally, submit its attestation and prepare a review. Payment evidence cannot satisfy this contract. An uncommitted development build without a trusted authority remains `NOT_CERTIFIED`.

Once the matching CI archive and public key are configured, the same demo can run through certification and save a review preview:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-http-retry.ps1 -AttestationFile artifacts/ci-attestation.json
```

This submits the supplied signed evidence and prepares a review only after issuance succeeds. It does not publish, merge or deploy. Without `-AttestationFile`, the development demo expects `NOT_CERTIFIED` and leaves review unprepared.

## Isolation and evaluation

Only fixed local WireMock hosts are allowed. Every trial gets a unique URL, correlation ID and explicit mapping UUIDs. Cleanup removes only those mappings and matching request journal entries; it never invokes global reset. Concurrent investigations cannot intentionally delete each other's evidence. The server request journal is bounded at 10,000 entries; eviction is detected as missing correlation rather than interpreted as successful remediation.

`HttpRetrySandboxIT` runs real container-backed counterfactuals, the combined fix suite and concurrent isolation checks. `HttpTransportSandboxIT` checks the transport measurements, expired budgets, interruption, unavailable fault control and rejection of legacy coverage. `IncidentApiIT` exercises measured reproduction through persisted proof, planning and verification. `RetryEvidenceTest` checks abstention for missing receipts, repeated ordinals and mixed request identities. The older 32-case Java model evaluation remains separately labeled and is not counted as additional real HTTP incidents. The transport regression suite is required by both the runtime attestation gate and `scripts/attest-certification.py`; this does not make retry remediation eligible for payment certification.

WireMock's documented [stateful stubs](https://wiremock.org/docs/stateful-behaviour/) model transient recovery, its [fault simulation API](https://wiremock.org/docs/simulating-faults/) supplies delayed and broken responses, and its [administration API](https://wiremock.org/docs/standalone/administration/) supports local fault setup and measurement.

Next: broader distributed ground truth, observability validation and release packaging. Full multi-hop deadline enforcement, exponential backoff, jitter, shared fleet retry budgets and production load targets remain separate work.
