ALTER TABLE verification_runs ADD COLUMN binding jsonb;
CREATE TABLE remediation_certificates (
    id uuid PRIMARY KEY,
    verification_id uuid NOT NULL REFERENCES verification_runs(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    evidence_digest varchar(64) NOT NULL,
    envelope jsonb NOT NULL,
    certificate jsonb NOT NULL,
    UNIQUE(verification_id,evidence_digest)
);
