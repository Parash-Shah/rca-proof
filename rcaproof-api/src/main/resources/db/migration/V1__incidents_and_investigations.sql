CREATE TABLE incidents (
    id uuid PRIMARY KEY,
    title varchar(200) NOT NULL,
    failure_signature varchar(80) NOT NULL CHECK (failure_signature = 'DUPLICATE_PAYMENT'),
    started_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    order_snapshot jsonb NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN ('DETECTED','EVIDENCE_COLLECTED','REPRODUCED','INVESTIGATING','PROVEN','INCONCLUSIVE'))
);
CREATE TABLE incident_status_history (
    sequence bigserial PRIMARY KEY,
    incident_id uuid NOT NULL REFERENCES incidents(id),
    status varchar(30) NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE evidence_events (
    sequence bigserial PRIMARY KEY,
    incident_id uuid NOT NULL REFERENCES incidents(id),
    event_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    payload jsonb NOT NULL,
    UNIQUE (incident_id, event_id)
);
CREATE INDEX evidence_timeline ON evidence_events(incident_id, occurred_at, sequence);
CREATE TABLE investigations (
    id uuid PRIMARY KEY,
    incident_id uuid NOT NULL UNIQUE REFERENCES incidents(id),
    scenario_version varchar(80) NOT NULL,
    repetitions integer NOT NULL CHECK (repetitions BETWEEN 2 AND 20),
    status varchar(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deadline timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '15 minutes'),
    lease_token uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    completed_at timestamptz,
    reason varchar(500)
);
CREATE INDEX investigation_queue ON investigations(status, lease_until, created_at);
CREATE TABLE experiment_runs (
    id bigserial PRIMARY KEY,
    investigation_id uuid NOT NULL REFERENCES investigations(id),
    factor_mask integer NOT NULL CHECK (factor_mask BETWEEN 0 AND 7),
    repetition integer NOT NULL,
    attempt integer NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','ABANDONED')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    observation jsonb,
    measurements jsonb,
    UNIQUE(investigation_id, factor_mask, repetition, attempt),
    CHECK ((status = 'COMPLETED') = (observation IS NOT NULL))
);
CREATE UNIQUE INDEX one_completed_trial ON experiment_runs(investigation_id, factor_mask, repetition) WHERE status = 'COMPLETED';
CREATE TABLE causal_proofs (
    investigation_id uuid PRIMARY KEY REFERENCES investigations(id),
    incident_id uuid NOT NULL UNIQUE REFERENCES incidents(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    report jsonb NOT NULL
);
