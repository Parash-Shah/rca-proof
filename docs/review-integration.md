# Certified remediation review

RCAProof prepares a persisted review and creates a draft GitHub PR from an already committed, tested candidate. It preserves the exact certified commit; it does not generate a new patch after certification. No code path merges, deploys, force-pushes, overwrites a branch, or reopens a closed PR.

## Contract

- A current `CERTIFIED` result for the configured repository is required for preparation and publication. The build, organization policy, source and certificate must still match.
- The remote target branch must point to the certificate's base commit. The candidate must descend from that base, have the certified source tree, and already exist in the repository. An already merged or rebased patch needs new evidence.
- A deterministic branch is created at the certified candidate commit. An existing branch with a different SHA is rejected.
- The PR includes the incident, minimal causal set, selected remediation, alternatives, verification count, certificate and artifact identifiers, organization digest, risks and rollback. Payment and retry candidates have separate cause and rollback narratives, with their respective local lab scopes.
- The review record is unique per verification and retains branch, SHAs, certificate, narrative, PR number, URL and status. Publication locks this record to serialize requests. A lost reply is reconciled by querying the deterministic branch's PR history before another create request.
- PR history and returned head/base SHAs are checked. Closed PRs stay closed. Changed remote state returns `RETRY_REQUIRED` with a reason. No destructive repair is attempted.

`GET` returns the persisted remote status with local certificate revalidation. It does not poll GitHub. An authenticated repeat publication request refreshes remote status without recreating the PR. `MERGED_BY_HUMAN` reports an externally observed merge; RCAProof never performs it.

## Pre-review certification

The normal CI workflow tests changes and uploads unsigned evidence. To certify a candidate before merge, run `.github/workflows/certify.yml` from `main` with exact `candidate_commit` and `base_commit` SHAs and select contract `payment` or `http-retry`.

1. Commit and push a reviewable candidate based on the intended target branch. This adapter expects that immutable commit to exist; it does not upload arbitrary files or execute a caller-supplied patch.
2. The workflow tests the candidate in a job without signing secrets and captures the actual LocalStack/PostgreSQL/WireMock image inventory.
3. A separate job uses the main-branch signer and the `certification` GitHub environment. Configure that environment with required reviewers and a main-only deployment branch rule. Store `RCAPROOF_CI_SIGNING_KEY` **only as an environment secret**, not a repository-wide secret. The reviewer must assess the candidate and test evidence before approving the signing job.
4. The signer checks candidate/base/run identity, Git cleanliness, the exact patch/archive, the complete fault suite and fresh regression reports. Candidate code is not executed in the signing job. The signing key never reaches the candidate build job.
5. Download the `candidate-evidence` and `signed-candidate` artifacts. Keep the executable archive and attestation together.

Human-reviewed workflow integrity and the signing authority are trust assumptions. A signature cannot make deliberately falsified tests trustworthy. Protect the main branch and environment administration accordingly. GitHub environment configuration is an operator prerequisite; adding a YAML `environment` field alone does not create required reviewers.

## Local configuration and use

Configure the API with:

| Variable | Purpose |
| --- | --- |
| `RCAPROOF_CI_PUBLIC_KEY` | Public half of the approved CI signing key |
| `RCAPROOF_GITHUB_TOKEN` | Repository-scoped token with Contents and Pull requests write access |
| `RCAPROOF_GITHUB_REPOSITORY` | Fixed owner/repository; defaults to `Parash-Shah/rca-proof` |
| `RCAPROOF_GITHUB_BASE_BRANCH` | Review target; defaults to `main` |
| `RCAPROOF_REVIEW_KEY` | Separate local API publication key, supplied as `X-RCAProof-Review-Key` |
| `RCAPROOF_CERTIFIED_JAR` | Absolute path to the identical downloaded CI executable |

Keep credentials out of Git. The local API remains bound to loopback by Compose. Missing publication configuration returns 503, a wrong publication key returns 401, and missing/stale certification blocks review preparation with 409.

Run the exact downloaded artifact with:

```powershell
docker compose -f docker-compose.yml -f docker-compose.certified.yml up -d rcaproof-api
```

Create a fresh incident and verification using that artifact, submit the matching attestation with `scripts/submit-certificate.ps1`, then prepare the review:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/review-remediation.ps1 -VerificationId <id>
```

The default command saves a Markdown preview and performs no GitHub writes. Add `-Publish` to create or reconcile the draft PR. Publication uses the configured destination, never request-supplied repository URLs. A changed destination or signing authority makes the existing review stale.

## API

- `POST /v1/verifications/{id}/review`: prepare an idempotent persisted narrative; no remote calls.
- `GET /v1/verifications/{id}/review`: read the narrative and saved tracking state.
- `POST /v1/verifications/{id}/pull-request`: requires the publication key; creates or reconciles a draft PR.

Tests exercise an HTTP GitHub stub and PostgreSQL persistence with explicit certificate fixtures. Those fixtures never become CI evidence or real certificates. A live GitHub publication additionally requires the configured credentials, approved workflow and committed candidate; local passing tests do not claim that a remote PR was opened.

The adapter follows the GitHub REST contracts for [references](https://docs.github.com/en/rest/git/refs), [commit comparison](https://docs.github.com/en/rest/commits/commits#compare-two-commits), and [pull requests](https://docs.github.com/en/rest/pulls/pulls).
