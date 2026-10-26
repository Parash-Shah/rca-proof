# Automatic evidence collection in the local lab

The order service and payment worker send observations to a persistent PostgreSQL inbox. Two distinct successful charge IDs for one order trigger detection; repeated receipts for the same provider charge do not. A separate supervisor observes the worker's real exit code before reporting the injected crash. The API correlates these observations, creates an incident and queues its existing counterfactual investigation automatically.

Run the complete flow in a separate Compose project with separate ports and a separate database:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-telemetry.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo-telemetry.ps1 -Fixed -SkipBuild
```

The script submits a real order, observes the worker crash, restarts it and checks provider effects and automatic investigation results. It also runs real HTTP requests against WireMock and checks that persistent six-attempt HTTP 500 amplification starts an investigation, while a healthy request does not. It never creates an incident, uploads an incident evidence batch or calls the investigation endpoint. Reports are saved under `artifacts/telemetry/`.

The project is `rcaproof-telemetry`; API port 18084, orders 18081, provider 18082, WireMock 18085, LocalStack 14566 and PostgreSQL 15433. It leaves the default certified lab separate. `-SkipBuild` reuses existing images; `-RuntimeOverride <path>` adds a Compose override for testing locally built archives. Stop it with `docker compose -p rcaproof-telemetry -f docker-compose.yml -f docker-compose.telemetry.yml stop`.

## API and processing rules

Telemetry endpoints are disabled by default. The `docker-compose.telemetry.yml` override enables `rcaproof.telemetry-enabled` and background processing. Outside Compose, set both `rcaproof.telemetry-enabled=true` and `rcaproof.worker-enabled=true`.

- `POST /v1/telemetry/events`: `{ "order": <order snapshot>, "event": <Evidence> }`. HTTP evidence has a null order. Accepted events return 202.
- `GET /v1/telemetry/payment/{orderId}` and `/v1/telemetry/retry/{requestId}` return the event count, optional incident ID and current status.
- `POST /v1/labs/http-requests`: `{ "mode": "HTTP_500", "attempts": 6 }` performs real lab requests and records measured client/provider evidence. Modes: `HEALTHY`, `HTTP_500`, `TRANSIENT_500`, `HTTP_400`; attempts 1–6.

Event IDs are globally unique. Retrying an identical event is harmless; changing its contents or correlation returns 409. Each stream accepts at most 1,000 events. Database row locks serialize detection and materialization, including concurrent collectors. Incident creation, evidence freezing and investigation enqueue commit together. Pending detected streams survive API restarts.

Complete evidence is queued on the next collector poll. Incomplete detected streams wait 30 seconds for delayed observations, then enter the normal investigation path, which returns `INCONCLUSIVE` when required evidence is missing. After enqueue, late events remain in the inbox but cannot change frozen incident evidence or start another investigation for the same stream. Inbox event counts can therefore exceed the incident's evidence count.

## Scope and limits

This is opt-in instrumentation for controlled local services, not a production telemetry platform. The unauthenticated endpoint accepts caller-supplied evidence and must remain on the local lab network. Source timestamps do not establish causal order. Payment events report successful provider responses; the supervisor reports only the explicit injected exit code 86 with its fresh order/message context, not arbitrary crashes or OOM kills.

Senders make at most two HTTP attempts, each with a two-second timeout, reusing the same event ID. Delivery failure logs a warning and does not stop payment processing. There is no durable sender outbox: missing delivery can prevent detection entirely, or make an investigation inconclusive. The inbox is durable only after acceptance. There is no automatic retention policy; long-running deployments need capacity limits, retention, authenticated intake and durable source delivery before production use.

HTTP detection is limited to the existing six-attempt retry contract. Healthy requests, two-attempt failures and transient recovery do not trigger it. Investigation proves the existing bounded sandbox hypothesis; telemetry does not grant remediation certification. These changed development builds require a new committed CI certification before they can replace an earlier certified artifact.
