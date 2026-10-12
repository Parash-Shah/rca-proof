ALTER TABLE incidents DROP CONSTRAINT incidents_failure_signature_check;
ALTER TABLE incidents ADD CONSTRAINT incidents_failure_signature_check
    CHECK (failure_signature IN ('DUPLICATE_PAYMENT','ACCESS_DENIED','INTERMITTENT_TIMEOUT'));
CREATE TABLE classifications (
    incident_id uuid PRIMARY KEY REFERENCES incidents(id),
    investigation_id uuid NOT NULL REFERENCES investigations(id),
    payload jsonb NOT NULL
);
CREATE TABLE remediation_plans (
    incident_id uuid PRIMARY KEY REFERENCES classifications(incident_id),
    payload jsonb NOT NULL
);
