# Automatic collection milestone — 2026-10-15

Implemented opt-in service telemetry for the local payment lab and measured HTTP requests. The durable inbox deduplicates event delivery, correlates order/message or request IDs, detects supported failures and atomically creates and queues investigations. A process supervisor reports the injected payment-worker crash only after observing exit code 86. Healthy/reused-charge controls do not trigger incidents. See [the telemetry guide](automatic-telemetry.md).

The new demo uses the independent `rcaproof-telemetry` Compose project and ports 18081–18085, 14566 and 15433. CI now runs its vulnerable and fixed paths and retains reports. Certification signing additionally requires the telemetry sender test suite. New development builds do not inherit prior certification; stale generated source claims were backed up under ignored `artifacts/telemetry/provenance-backup/` and removed before compiling.

Previous certification release work is complete for commit `bcb92ade2d32b857409989d036387c6d72aa54c0`: both approved payment and HTTP retry signing runs succeeded, their downloaded archive/signatures were checked, and the exact archive was validated locally. The older notes below describe earlier milestones and their remaining work at those dates. The automatic collection changes require a new reviewed commit and CI certification.

Local regression results: **106 Java tests passed**, zero failures/errors/skips in the latest reports, and both Python certification-tool tests passed. The first full reactor run was interrupted by an approximately one-hour environment clock jump during an existing verification test; all 26 API integration tests passed on rerun. The initial reports are preserved under `artifacts/telemetry/interrupted-test-run/`; the final suite inventory is `artifacts/telemetry/java-tests.json`. PowerShell, workflow YAML and merged telemetry Compose syntax checks passed. CI configuration is updated; remote CI has not run for these uncommitted changes.

Live validation completed October 16 using the newly compiled archives, mounted in an isolated non-root runtime matching the Dockerfile user. The original overnight-interrupted demo was discarded. A subsequent transient status-read timeout prompted bounded polling retries; a targeted PowerShell check verified timeout recovery and rejection of inconclusive proofs.

- Vulnerable payment: actual worker exit **86**, **two distinct charges**, seven collected events, automatic incident `aa8f1c01-030c-421d-92b9-15242e9343a3`, and **PROVEN** after 16 trials. The minimal cause was message redelivery plus missing idempotency.
- HTTP amplification: six measured HTTP 500 attempts, automatic incident `9ec3b9cb-86ad-4c9b-8841-08ead48abd7c`, and **PROVEN** after 16 trials. The minimal cause was persistent dependency HTTP 500 responses plus excessive retries.
- Fixed-payment control: actual crash and replay, **one charge**, seven collected events and **no incident**. Healthy HTTP requests likewise created no incident. A further HTTP amplification run also reached PROVEN.
- Saved reports: `artifacts/telemetry/demo-telemetry-4e65886219d94157816f1c3dcfc60f50.json` and `artifacts/telemetry/demo-telemetry-4ecd7b3347f3460d8f630f952d2b44f9.json`. Full proofs and timelines are `artifacts/telemetry/proof-*.json` and `timeline-*.json`.

These are scoped local-lab results. No manual incident creation, incident evidence submission or investigation request was used by the live demo. The earlier certified runtime and artifacts remain separate from this development build.

# Development status — 2026-10-12

## Local demo completion checks — October 12

The Windows `scripts/verify.ps1` command now runs the Python certification-tool tests before the full Maven suite. `-UnitOnly` retains its Java unit-test behavior. A failing tooling test stops verification before Maven starts. The README documents the combined command.

Measured local results:

- A complete `mvn -B -ntp verify` run passed **100 Java tests**, with zero failures, errors or skips, in 11 minutes 44 seconds. Counts and report paths are saved in `artifacts/completion-java-tests-20261012.json`; original JUnit XML remains in each module's target reports.
- Both Python certification-tool tests passed with disposable repositories and ephemeral test keys. PowerShell parsing and command routing/failure handling passed.
- The main lab passed all three incident outcomes and all seven dashboard queries: `artifacts/release-check-5a812df3-16a5-4c30-b3d3-6d299894f70c.json`.
- The live payment fix passed all 22 checks: `artifacts/verification-4f0550f5-f97c-4d98-a1a8-85f407bcf06c.json`.
- The live HTTP retry fix passed all 16 checks: `artifacts/http-retry-a433cf2b-8466-4a1c-8cfe-908fd0e52e7c.json`.
- Both live verifications returned `VERIFIED_IN_SANDBOX` and remained `NOT_CERTIFIED`, as expected without matching committed provenance and trusted CI evidence.
- A 121-file source package passed archive and per-file SHA-256 checks. A separate Compose project, `rcaproof-completion-20261012`, built that source using available Docker caches and passed the three-outcome/seven-panel check with a new database. Counts started at zero and ended at three incidents, two scoped proofs, one inconclusive outcome and 24 experiments. Evidence: `artifacts/release-check-fresh-76090afe.json`.
- The temporary package-validation stack was stopped, preserving its database volume. The main demo remains running on the standard localhost ports.

Package: `artifacts/releases/8cf2d311-867c-446c-9122-1f62cbf01b71/rcaproof-source.tar.gz`. SHA-256: `967a5996e0f35048a2b58b957372eb4e556e97224ff3bfb403e51aaf1d397afe`. This working-tree snapshot includes the verification-command change but predates this validation note.

These checks establish local demo readiness, not completion of the full production roadmap. Remaining release work includes a reviewed source commit, protected CI signing configuration, an actual candidate-certification run, and validation of the exact downloaded signed archive. Automatic telemetry collection, broader failure scenarios and production evaluation remain separate implementation milestones. No commit, push, external publication or real certificate was issued in this validation.

## Observability and source-package readiness — September 21–22

Completed one-command startup and a containerized release smoke check, plus a source exporter and [release runbook](release-runbook.md). Windows startup is `scripts/start-demo.ps1`; Linux/macOS startup is `sh scripts/start-demo.sh`. The smoke check validates three incident decisions, measured API/Prometheus agreement, and every provisioned Grafana panel query through its datasource proxy. CI now includes that smoke check and retains its report; the updated remote workflow has not been executed here.

Measured results:

- The existing lab passed all three outcomes and seven dashboard queries on September 21 (`artifacts/release-check-3fddf048-5bba-406f-94c4-2f1564c4e265.json`).
- A separate source-package build and fresh database under Compose project `rcaproof-release-20260922` passed the same check on September 22. Startup used the exported source directory, its own image tag, distinct host ports and an independent database volume; existing Docker dependency/build caches were available.
- Fresh counts changed from zero to **3 incidents, 2 scoped PROVEN results, 1 INCONCLUSIVE result and 24 experiments**. The two proofs include the expected-denial case; they are not two software defects. Classification counts were one architecture defect, one expected behavior and one unknown. No certificate or review was issued.
- All **7 Grafana panel expressions** returned finite, nonempty data through Grafana. Prometheus matched the API's measured values and reported its target up. This validates provisioning and data delivery, not visual browser interactions or production accuracy.
- The source package contains **120 files**. The archive SHA-256 and every manifest file hash were verified; the LocalStack shell initializer was executable. The archive excludes compiled output, local environment files and generated certification source/patch claims.
- PowerShell and POSIX shell startup syntax, workflow/Compose YAML, and `git diff --check` passed. No Java implementation changed in this milestone; the prior full 100-test Maven result remains the latest Java regression run.

Package: `artifacts/releases/190e963b-805d-4a75-a99d-b0d08d47d5e3/rcaproof-source.tar.gz`

SHA-256: `c388fc6a42dbf4dca478380a63b30f5d4320a0e98c9d0143fe8b35d1b5473f33`

Fresh evidence: `artifacts-release-fresh.log` and `artifacts/release-check-fresh-d102e364.json`. The archive is an immutable, uncommitted working-tree snapshot taken before this validation note; it is not a published GitHub release or a certified remediation artifact. A real fresh Git clone cannot contain these changes until they are committed and pushed.

The temporary validation stack was stopped after testing, preserving its database volume and evidence. The main lab remains available at API `http://localhost:8084`, Grafana `http://localhost:3001/d/rcaproof` and Prometheus `http://localhost:9091`. Other Compose projects were left untouched.

Remaining toward the full project vision: broader distributed incident ground truth, independent transport-incident investigations, configured real CI signing/publication, and a reviewed source commit/tag. No commit, push, signing-authority setup or external release publication was performed.

## Multi-scenario certification — September 21

Completed separate certification contracts for payment (`IDEMPOTENCY_LEDGER`, `payment-verification-v2`, 22 checks) and HTTP retry (`BOUNDED_RETRY`, `http-retry-verification-v2`, 16 checks). Signed evidence now binds the candidate and incident signature as well as source, patch, archive, policy and suite. The issuer checks the actual persisted incident and uses the candidate-specific implementation fingerprint. Cross-scenario evidence, incomplete coverage, stale evidence and legacy payloads without explicit identity cannot certify.

The approved CI workflow accepts `payment` or `http-retry`, exports separate measured subjects, hashes them for artifact transfer, and signs only the selected contract. Retry reviews now describe retry amplification, caller deadlines and the appropriate rollback constraints. `demo-http-retry.ps1 -AttestationFile <file>` can submit matching evidence and save a prepared review without publishing it.

Validation:

- A full `mvn verify` run passed **100 Java tests**, with zero failures, errors or skips, including the order/worker suites, 29 API integration tests and eight certification-gate tests.
- Two Python tooling tests passed, exercising signed payment/retry subjects, rejection of cross-contract subjects, altered transferred subject/report content, missing coverage and incorrect workflow/source bindings.
- The retry integration flow reproduces the incident, measures all 16 checks, then tests signed issuance, idempotency, reload, review preparation and key revocation using an **ephemeral test authority and explicitly mocked committed-build binding**. That fixture is not exported as CI evidence and does not certify this development checkout.
- Workflow YAML and PowerShell demo syntax parsed; `git diff --check` passed. The new image built, API health returned `UP`, and the landing page returned HTTP 200 on port 8084.
- Live incident `a1b59afd-98ab-4672-8a18-a900d045470e` reached `PROVEN`; verification `05f29f8c-8625-4d5c-9bd8-223f657233b4` passed all 16 checks. Certification remained `NOT_CERTIFIED` for missing committed provenance and signed CI evidence, with no unsupported-retry blocker. Review remained unprepared. The first reproduction attempt returned 503 before creating an incident; a subsequent full demo passed. Its initial failure's exact cause was not established.

Evidence (ignored by Git): `artifacts-multiscenario-resumed.log`, `artifacts-multiscenario-tools.log`, `artifacts-multiscenario-build.log`, `artifacts-multiscenario-live-retry.log`, `artifacts/multiscenario-live-certificate.json`, and `artifacts/http-retry-a1b59afd-98ab-4672-8a18-a900d045470e.json`. The earlier interrupted run in `artifacts-multiscenario-tests.log` is not counted as a completed run.

No real signing authority, remote Actions run or GitHub PR was configured or executed. Changes remain local and uncommitted; nothing was pushed. Real issuance still requires the clean committed CI archive, protected signing environment and trusted public key described in [certification setup](remediation-certification.md).

Next milestone: validate the observability demo and release packaging. Broader distributed ground truth and independent transport-incident causal investigations remain additional work toward the full project vision.

## Latency, deadlines and connection faults — September 21

Completed the transport-verification extension under `http-retry-verification-v2`. The bounded retry candidate now shares a caller deadline across attempts, propagates the remaining relative budget, and surfaces connection failures without application retries. The gate requires 16 checks; legacy nine-check coverage cannot pass. Both attestation gates require `HttpTransportSandboxIT`.

- The Maven API reactor passed **94 Java tests**, with zero failures, errors or skips: 45 core, five provider, 15 API unit and 29 integration tests. This targeted reactor command did not rerun the unchanged order-service and payment-worker integration suites.
- Two Python attestation-tool tests passed. PowerShell demo syntax validation and `git diff --check` passed.
- Fresh live incident `8be746a3-2733-4a95-ba58-a1e3b830625d` retained the HTTP 500 amplification proof and passed all **16 verification checks** under version 2.
- The delayed baseline completed in **1,206ms**. Enforcing the same request's **600ms caller budget** returned `DEADLINE_EXCEEDED` in **602ms**. These are local measurements, not production latency guarantees.
- Two slow HTTP 500 attempts propagated remaining budgets of **1,999ms and 1,768ms**. Empty and truncated responses each returned `TRANSPORT_ERROR` with one observed provider request. An expired budget issued zero requests.
- Certification remained `NOT_CERTIFIED`. The API image built, health returned `UP`, the landing page returned HTTP 200 on port 8084, and scoped cleanup left no lab mappings in WireMock.

Evidence (ignored by Git): `artifacts-transport-tests.log`, `artifacts-transport-tools.log`, `artifacts-transport-build.log`, `artifacts-transport-live.log`, and `artifacts/http-retry-8be746a3-2733-4a95-ba58-a1e3b830625d.json`.

Scope: these are measured nearby-failure checks for the retry candidate. The original causal proof remains specific to HTTP 500 amplification. The relative budget is observed at one hop; downstream enforcement, multi-hop cancellation and independent transport-incident causal investigations remain future work. See [the HTTP lab contract](http-retry-lab.md).

Next milestone: extend certification to a versioned multi-scenario contract. Broader distributed incident ground truth, validated observability and release packaging remain outstanding. Changes are local and uncommitted; nothing was pushed.

## HTTP retry-storm milestone — September 21

The real HTTP retry adapter now connects a Java client to a separate WireMock dependency. It correlates client attempts with server receipts, searches three factors, persists the proof and evaluates bounded retries with a separate nine-check verification contract. Unexpected responses or missing receipts produce `INCONCLUSIVE` instead of a healthy control.

- Live incident `db1e9a58-6fd3-40a6-bff4-ef3765bf5d18` reached `PROVEN` with minimal causal set `{DEPENDENCY_HTTP_500, EXCESSIVE_RETRIES}`.
- Both baseline checks measured six dependency calls. Bounded retry reduced persistent failure to two calls while still surfacing HTTP 500; transient recovery returned `[500, 200]`.
- All nine live checks passed, including HTTP 400 without retries, healthy HTTP 200, four concurrent operations with eight total calls, and rejection of disabling retries or merely extending the timeout.
- Verification reached `VERIFIED_IN_SANDBOX`. Certification correctly remained `NOT_CERTIFIED`: the signed certificate and review contract is still payment-specific.
- The resumed Maven reactor run passed 91 Java tests with zero failures, errors or skips: 45 core, five provider, 15 API unit, four real HTTP sandbox and 22 API integration tests. The unchanged order and worker suites passed five additional tests in the earlier run; this is 96 passing tests across runs, not a claim of one clean 96-test run.
- Two Python certification-tool tests passed after adding the HTTP regression suite and measured WireMock image to the attestation requirements. The demo PowerShell script parsed successfully and `git diff --check` passed.
- The final API image built and is running on port 8084; health returned `UP` and the landing page returned HTTP 200. The first reproduction request returned 503; a subsequent measured reproduction and the full demo passed. The initial transient failure's exact cause was not established.

Evidence: `artifacts-http-retry-resumed.log`, `artifacts-http-retry-tools.log`, `artifacts-http-retry-final-build.log`, `artifacts-http-retry-live.log` and `artifacts/http-retry-db1e9a58-6fd3-40a6-bff4-ef3765bf5d18.json` (ignored by Git). The live proof contains 16 counterfactual trials, and scoped cleanup left no lab mappings in WireMock. See [the HTTP lab contract and demo](http-retry-lab.md).

Changes remain uncommitted on local `main`; nothing was pushed. No signing authority or live GitHub publication was configured.

This milestone covers local GET retry amplification. Next: latency/deadline and connection-loss experiments, then a versioned certification contract for multiple scenarios. Broader distributed ground truth, observability-demo validation and release packaging remain outstanding.

The certification and GitHub review integration adapters are implemented for the declared `LOCAL_LAB` contract. Reviews preserve the certified commit and create draft PRs. Merge and deployment remain human actions. See [review setup and trust boundaries](review-integration.md).

## Review integration validation — September 20

- A complete `mvn verify` run passed all 88 Java tests with no failures, errors or skips, including 21 API integration tests and four HTTP GitHub adapter tests.
- The PostgreSQL-backed review test covers idempotent preparation, lost remote creation replies, retry reconciliation without a duplicate PR, record reload, and signing-key revocation.
- Two Python tooling tests passed with candidate/base/workflow/run binding, cryptographic signature verification, transferred report integrity and incomplete-evidence rejection checks.
- The 32-case behavioral evaluation measured 32 correct outcomes, 16 abstentions and zero false PROVEN results across eight local models. This is not production-incident accuracy.
- Workflow YAML and Compose configuration parsed. The review PowerShell script passed syntax validation. The API image built and the updated local service returned health UP and landing-page HTTP 200.
- Live gate checks returned 409 for uncertified review preparation and 503 for unconfigured publication. The operations overview and review metric were available.

Evidence: `artifacts-review-validation.log`, `artifacts-review-tools-tests.log`, `artifacts-review-build.log`, and `rcaproof-core/target/scenario-evaluation.*` (all ignored by Git).

No live GitHub PR or remote Actions run was performed. Those require committed source, the configured protected signing environment, its public key, a matching certified candidate and GitHub publication credentials. The working tree remains uncommitted on `main`; nothing was pushed.

Next work: real distributed fault adapters and broader incident ground truth, followed by validation of the observability demo and release packaging. The original broad project vision is not yet complete.

## Earlier certification validation — September 18–19

- 51 Java tests have passing results across the September 18–19 validation runs. The resumed 20-test API suite passed 19 tests; the remaining test initially lost its lease because its manually constructed worker had no scheduled heartbeat. The test now runs the production heartbeat cadence, and its targeted rerun passed with Maven `BUILD SUCCESS`. This is not a claim of a single clean CI run.
- Two Python certification-tool tests passed, including dirty-source rejection, signature verification, and rejection of incomplete or failed evidence.
- PowerShell scripts parsed successfully; the API image built and Flyway applied migration V4.
- A fresh live incident produced `VERIFIED_IN_SANDBOX` with 22 passing checks and zero failed checks. Its certificate remained `NOT_CERTIFIED`; an invalid submission issued no certificate.
- The landing page and health endpoint returned HTTP 200. Compose services were left running on port 8084.

Local evidence (ignored by Git):

```text
artifacts-certification-resumed.log
artifacts/certification-api-before-heartbeat.xml
artifacts-certification-heartbeat-test.log
artifacts-certification-tools-tests.log
artifacts-live-certification.log
artifacts/verification-91316a75-a50b-4bb7-9cc4-531a7bd00e0d.json
```

The working tree remains uncommitted on local `main`; nothing was pushed. No CI signing authority was configured. Issuing a real certificate still requires a clean committed build, its matching signed CI attestation and the configured public key, as described in [the certification contract](remediation-certification.md). Passing test fixtures do not certify this development checkout.

Review integration now consumes these persisted proofs, remediation plans and certificates. Human approval remains the merge/deployment boundary.
