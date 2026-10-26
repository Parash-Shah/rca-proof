CREATE TABLE telemetry_streams (
    stream_key varchar(220) PRIMARY KEY,
    signature varchar(40) NOT NULL,
    order_snapshot jsonb,
    detected_at timestamptz,
    incident_id uuid REFERENCES incidents(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE telemetry_events (
    sequence bigserial PRIMARY KEY,
    stream_key varchar(220) NOT NULL REFERENCES telemetry_streams(stream_key),
    event_id uuid NOT NULL UNIQUE,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    payload jsonb NOT NULL
);
CREATE INDEX telemetry_events_stream ON telemetry_events(stream_key, sequence);
CREATE INDEX telemetry_pending ON telemetry_streams(detected_at) WHERE incident_id IS NULL AND detected_at IS NOT NULL;
