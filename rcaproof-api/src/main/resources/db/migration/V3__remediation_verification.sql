CREATE TABLE verification_runs (
    id uuid PRIMARY KEY,
    incident_id uuid NOT NULL REFERENCES remediation_plans(incident_id),
    candidate_id varchar(80) NOT NULL,
    artifact_digest varchar(64) NOT NULL,
    status varchar(30) NOT NULL CHECK(status IN ('QUEUED','RUNNING','VERIFIED_IN_SANDBOX','FAILED','INCONCLUSIVE')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deadline timestamptz NOT NULL DEFAULT(clock_timestamp()+interval '15 minutes'),
    lease_token uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    report jsonb,
    UNIQUE(incident_id,candidate_id)
);
CREATE TABLE verification_checks (
    run_id uuid NOT NULL REFERENCES verification_runs(id),
    attempt integer NOT NULL,
    name varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    PRIMARY KEY(run_id,attempt,name)
);
