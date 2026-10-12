CREATE TABLE change_requests (
    id uuid PRIMARY KEY,
    verification_id uuid NOT NULL UNIQUE REFERENCES verification_runs(id),
    certificate_id uuid NOT NULL REFERENCES remediation_certificates(id),
    repository varchar(200) NOT NULL,
    base_branch varchar(200) NOT NULL,
    branch varchar(200) NOT NULL,
    head_sha varchar(40) NOT NULL,
    base_sha varchar(40) NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    status varchar(30) NOT NULL,
    pull_number integer,
    pull_url text,
    detail text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(repository, branch)
);
