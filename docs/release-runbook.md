# Local release runbook

This is a source-package and demo readiness check. It does not issue a remediation certificate or claim production incident accuracy. All incident evidence used by the smoke test is explicitly synthetic; the payment interventions execute against the local sandbox.

## One-command demo

Requirements: Docker with Compose v2 and Linux containers, available localhost ports listed below, and access to pull the declared images on first run. No host Java, Maven, Python or AWS account is required. Allow several minutes for the first build; a busy Docker Desktop host can take substantially longer.

From the repository root on Windows:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-demo.ps1
```

On Linux/macOS:

```sh
sh scripts/start-demo.sh
```

These commands build/start the API, PostgreSQL, LocalStack, WireMock, Prometheus and Grafana, then run the containerized release smoke test. They do not start the standalone order worker or alter other Compose projects. Windows users can add `-SkipBuild` only when the running image already matches their source.

| Service | Default URL / port | Override |
| --- | --- | --- |
| Incident API | http://localhost:8084 | `RCAPROOF_API_PORT` |
| Grafana dashboard | http://localhost:3001/d/rcaproof | `RCAPROOF_GRAFANA_PORT` |
| Prometheus | http://localhost:9091 | `RCAPROOF_PROMETHEUS_PORT` |
| PostgreSQL | 5433 | `RCAPROOF_DB_PORT` |
| WireMock | 8085 | `RCAPROOF_HTTP_LAB_PORT` |
| LocalStack | 4566 | `RCAPROOF_AWS_PORT` |

Ports bind to loopback. Grafana allows anonymous **Viewer** access for this synthetic lab. It is not a public deployment configuration. The checker uses internal Compose addresses, so host port overrides do not change its behavior. See the existing README for the separate order/provider process-crash demo.

## What a passing check means

The checker creates new uniquely identified incidents on every run; it does not clear existing evidence:

1. Duplicate payment: scoped `PROVEN`, `ARCHITECTURE_DEFECT`, idempotency remediation recommended.
2. Synthetic access denial: scoped `PROVEN`, `EXPECTED_BEHAVIOR`, no code change or candidate.
3. Incomplete timeout evidence: `INCONCLUSIVE`, `UNKNOWN`, request more evidence and no candidate.

It checks the increase in durable incident, experiment and classification counts. It waits for Prometheus to scrape values matching the API and requires the RCAProof scrape target to be up. It then loads the provisioned Grafana dashboard and evaluates every panel expression **through Grafana's datasource proxy**, requiring nonempty finite data. This validates configuration and data delivery; it is not a browser screenshot or a claim that every visual interaction was inspected.

The resulting `artifacts/release-check-<uuid>.json` records outcomes, before/after metrics, scraped values, panel queries, timestamps and `PASS` or `FAIL`. Failed runs exit nonzero and preserve the available diagnostics. A completed failure is not retried as a success; rerunning creates a separately recorded attempt. Timing is readiness polling, not an availability benchmark.

Rerun against an already built lab:

```sh
docker compose --profile observability run --rm release-check
```

Stop services while preserving PostgreSQL evidence:

```sh
docker compose --profile observability down
```

Do not add `-v` unless you intentionally want to remove the project's database volume. Grafana configuration is provisioned from source; Prometheus history in this demo is ephemeral. Incident and certificate history lives in PostgreSQL.

## Source package and fresh start

With Python 3 available, run `python3 scripts/package-release.py`. Without host Python:

```powershell
docker run --rm --entrypoint python3 -v "${PWD}:/workspace" -w /workspace localstack/localstack:4.7.0 -B scripts/package-release.py
```

The exporter creates `artifacts/releases/<uuid>/source`, `rcaproof-source.tar.gz`, a file-hash manifest and an archive SHA-256. It uses an explicit source allowlist, omits compiled output, local environment files, generated source/patch claims and private-key files, and rejects symlinks. Shell scripts receive LF line endings and executable permissions. Review package contents before publishing; the script does not publish or tag anything.

While this working tree is uncommitted, the package is labeled a **working-tree snapshot**, not a GitHub release or certified archive. Validate it from a new source directory and an independent Compose project, with unused host ports and a fresh database volume. For example, from the exported `source` directory on PowerShell:

```powershell
$env:COMPOSE_PROJECT_NAME = 'rcaproof-release-check'
$env:RCAPROOF_API_PORT = '18084'
$env:RCAPROOF_DB_PORT = '15433'
$env:RCAPROOF_HTTP_LAB_PORT = '18085'
$env:RCAPROOF_AWS_PORT = '14566'
$env:RCAPROOF_PROMETHEUS_PORT = '19091'
$env:RCAPROOF_GRAFANA_PORT = '13001'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-demo.ps1
docker compose --profile observability down
```

Use a dedicated shell for these overrides. Once changes are committed, repeat the same startup command in a new Git clone before tagging a release. No generated certification provenance is copied into the source package: signed remediation releases must follow [the separate certification contract](remediation-certification.md).

## Troubleshooting

- **Port already allocated:** choose unused overrides; do not stop unrelated projects. `docker compose ps` shows this project's published ports.
- **Docker unavailable:** start Docker Desktop with Linux containers and confirm `docker info` succeeds.
- **LocalStack unhealthy in a Unix checkout:** run `chmod +x infra/localstack/init.sh`; check its LF line endings and `docker compose logs localstack`.
- **API 404 or connection refused:** use the incident API port (8084 by default), then inspect `docker compose logs rcaproof-api`. Startup must finish before requests work.
- **Grafana empty or Prometheus target down:** inspect `docker compose logs prometheus grafana`, Prometheus `/targets`, and the saved smoke artifact. Keep the internal datasource URL `http://prometheus:9090`; localhost inside Grafana is not Prometheus.
- **Evidence or experiment failure:** inspect the artifact and incident proof. Do not loosen invariant or certification gates to turn a failed run green.
- **Certificate remains `NOT_CERTIFIED`:** expected for an uncommitted local build without matching signed CI evidence. Demo readiness does not grant certification.

## Release checklist

- [ ] Commit reviewed source, including scripts and dashboard provisioning.
- [ ] Pass `mvn verify` and Python certification-tool tests; retain actual reports.
- [ ] Pass the three-outcome smoke check against provisioned Grafana/Prometheus.
- [ ] Build and pass the same check from a fresh source directory and database volume.
- [ ] Record actual test totals, scenario scope, failed attempts and known limitations in development status.
- [ ] Review archive contents, hash manifest, licensing and README before external publication.
- [ ] For signed remediation artifacts, configure the protected signing environment and validate the exact downloaded archive separately.
- [ ] Tag/publish only after review; no automatic merge or deployment.

The checker uses the documented [Prometheus query API](https://prometheus.io/docs/prometheus/3.5/querying/api/) and Grafana's provisioned dashboard/datasource API. Dashboard counts are durable local outcomes, not an accuracy percentage or production SLO.
