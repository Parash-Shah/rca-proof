package dev.rcaproof.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rcaproof.causal.CounterfactualEngine;
import dev.rcaproof.demo.contract.Order;
import dev.rcaproof.experiment.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static dev.rcaproof.api.IncidentModels.*;
import static org.springframework.http.HttpStatus.*;

@Repository
public class IncidentStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public IncidentStore(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize stored data", e); }
    }
    private <T> T decode(String value, Class<T> type) {
        if (value == null) return null;
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored data", e); }
    }
    private Map<String,Object> measurements(String value) {
        if (value == null) return Map.of();
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored measurements", e); }
    }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        Timestamp value = r.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private Incident mapIncident(ResultSet r, int row) throws SQLException {
        return new Incident(r.getObject("id", UUID.class), r.getString("title"), instant(r,"started_at"),
                instant(r,"created_at"), r.getString("failure_signature"), decode(r.getString("order_snapshot"), Order.class), Status.valueOf(r.getString("status")));
    }
    private Job mapJob(ResultSet r, int row) throws SQLException {
        return new Job(r.getObject("id",UUID.class), r.getObject("incident_id",UUID.class), r.getInt("repetitions"),
                r.getString("status"), r.getInt("attempts"), r.getObject("lease_token",UUID.class), instant(r,"lease_until"),
                instant(r,"deadline"), r.getString("scenario_version"), r.getString("reason"));
    }
    private Run mapRun(ResultSet r, int row) throws SQLException {
        return new Run(r.getLong("id"), r.getInt("factor_mask"), r.getInt("repetition"), r.getInt("attempt"), r.getString("status"),
                instant(r,"started_at"), instant(r,"completed_at"), decode(r.getString("observation"), Observation.class), measurements(r.getString("measurements")));
    }
    public Incident incident(UUID id) {
        return db.query("SELECT * FROM incidents WHERE id=?", this::mapIncident, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Incident not found"));
    }
    private Incident lockIncident(UUID id) {
        return db.query("SELECT * FROM incidents WHERE id=? FOR UPDATE", this::mapIncident, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Incident not found"));
    }
    @Transactional public Incident create(CreateIncident request) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO incidents(id,title,failure_signature,started_at,order_snapshot,status) VALUES(?,?,?,?,?::jsonb,'DETECTED')",
                id, request.title(), request.failureSignature(), Timestamp.from(request.startedAt()), encode(request.order()));
        history(id, Status.DETECTED);
        return incident(id);
    }
    private void history(UUID id, Status status) {
        db.update("INSERT INTO incident_status_history(incident_id,status) VALUES(?,?)", id, status.name());
    }
    private void transition(UUID id, Status target) {
        Status current = lockIncident(id).status();
        if (current == target) return;
        boolean allowed = switch (current) {
            case DETECTED -> target == Status.EVIDENCE_COLLECTED || target == Status.INCONCLUSIVE;
            case EVIDENCE_COLLECTED -> target == Status.REPRODUCED || target == Status.INCONCLUSIVE;
            case REPRODUCED -> target == Status.INVESTIGATING || target == Status.INCONCLUSIVE;
            case INVESTIGATING -> target == Status.PROVEN || target == Status.INCONCLUSIVE;
            case PROVEN, INCONCLUSIVE -> false;
        };
        if (!allowed) throw new IllegalStateException("Invalid incident transition: " + current + " -> " + target);
        db.update("UPDATE incidents SET status=? WHERE id=?", target.name(), id);
        history(id, target);
    }
    @Transactional public int addEvidence(UUID id, List<Evidence> events) {
        Incident incident = lockIncident(id);
        if (db.queryForObject("SELECT count(*) FROM investigations WHERE incident_id=?", Integer.class, id) != 0)
            throw new ResponseStatusException(CONFLICT, "Evidence is frozen after investigation is queued");
        int inserted = 0;
        for (Evidence event : events) {
            if (incident.order()!=null && !incident.order().orderId().equals(event.orderId())) throw new ResponseStatusException(BAD_REQUEST, "Evidence orderId does not match incident");
            var existing = db.query("SELECT payload::text FROM evidence_events WHERE incident_id=? AND event_id=?",
                    (r,n) -> decode(r.getString(1), Evidence.class), id, event.eventId());
            if (!existing.isEmpty()) {
                if (!existing.getFirst().equals(event)) throw new ResponseStatusException(CONFLICT, "Event ID already contains different evidence");
                continue;
            }
            db.update("INSERT INTO evidence_events(incident_id,event_id,occurred_at,payload) VALUES(?,?,?,?::jsonb)",
                    id, event.eventId(), Timestamp.from(event.timestamp()), encode(event));
            inserted++;
        }
        if (db.queryForObject("SELECT count(*) FROM evidence_events WHERE incident_id=?", Integer.class, id) > 1000)
            throw new ResponseStatusException(BAD_REQUEST, "An incident supports at most 1000 evidence events");
        if (inserted > 0) transition(id, Status.EVIDENCE_COLLECTED);
        return inserted;
    }
    public Timeline timeline(UUID id) {
        incident(id);
        List<TimelineEvent> events = db.query("SELECT * FROM evidence_events WHERE incident_id=? ORDER BY occurred_at,sequence",
                (r,n) -> new TimelineEvent(r.getLong("sequence"), instant(r,"received_at"), decode(r.getString("payload"), Evidence.class)), id);
        List<String> warnings = new ArrayList<>();
        warnings.add("Source timestamp order is not proof of causal order; clock synchronization is unverified.");
        if (events.stream().anyMatch(e -> e.event().eventType() == EventType.TELEMETRY_GAP)) warnings.add("Explicit telemetry gaps are present.");
        if (events.stream().anyMatch(e -> e.event().traceId() == null || e.event().traceId().isBlank())) warnings.add("Some events have no trace ID; correlation relies on supplied order/message IDs.");
        return new Timeline(id, events, warnings, db.query("SELECT * FROM incident_status_history WHERE incident_id=? ORDER BY sequence",
                (r,n) -> new Transition(Status.valueOf(r.getString("status")), instant(r,"changed_at")), id));
    }
    @Transactional public Job enqueue(UUID incidentId, int repetitions) {
        Incident incident = lockIncident(incidentId);
        var existing = db.query("SELECT * FROM investigations WHERE incident_id=?", this::mapJob, incidentId);
        if (!existing.isEmpty()) {
            if (existing.getFirst().repetitions() != repetitions) throw new ResponseStatusException(CONFLICT, "Investigation already exists with different repetitions");
            return existing.getFirst();
        }
        if (incident.status() != Status.EVIDENCE_COLLECTED) throw new ResponseStatusException(CONFLICT, "Collect evidence before investigating");
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO investigations(id,incident_id,scenario_version,repetitions,status) VALUES(?,?,?,?,'QUEUED')",
                id, incidentId, ScenarioDefinition.forIncident(incident).version(), repetitions);
        return job(id);
    }
    public Job job(UUID id) {
        return db.query("SELECT * FROM investigations WHERE id=?", this::mapJob, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Investigation not found"));
    }
    public JobView investigation(UUID incidentId) {
        incident(incidentId);
        Job job = db.query("SELECT * FROM investigations WHERE incident_id=?", this::mapJob, incidentId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Investigation not yet queued"));
        return new JobView(job.id(), job.incidentId(), job.repetitions(), job.status(), job.attempts(), job.deadline(), job.scenarioVersion(), job.reason(), runs(job.id()));
    }
    public List<Run> runs(UUID jobId) {
        return db.query("SELECT * FROM experiment_runs WHERE investigation_id=? ORDER BY id", this::mapRun, jobId);
    }
    @Transactional public Optional<Job> claim() {
        var candidates = db.query("""
                SELECT * FROM investigations WHERE status='QUEUED' OR (status='RUNNING' AND lease_until < clock_timestamp())
                ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                """, this::mapJob);
        if (candidates.isEmpty()) return Optional.empty();
        Job job = candidates.getFirst();
        db.update("UPDATE experiment_runs SET status='ABANDONED',completed_at=clock_timestamp() WHERE investigation_id=? AND status='RUNNING'", job.id());
        db.update("UPDATE investigations SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=clock_timestamp()+interval '90 seconds' WHERE id=?", UUID.randomUUID(), job.id());
        return Optional.of(job(job.id()));
    }
    public boolean heartbeat(Job job) {
        return db.update("UPDATE investigations SET lease_until=clock_timestamp()+interval '90 seconds' WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>clock_timestamp()", job.id(), job.leaseToken()) == 1;
    }
    private void requireOwner(Job job) {
        var owned = db.query("SELECT id FROM investigations WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>clock_timestamp() FOR UPDATE",
                (r,n) -> r.getObject(1), job.id(), job.leaseToken());
        if (owned.isEmpty()) throw new ExperimentAbortedException("Investigation lease lost");
    }
    @Transactional public void baselineReproduced(Job job) {
        requireOwner(job);
        if (incident(job.incidentId()).status() == Status.INVESTIGATING) return;
        int full=ScenarioDefinition.forIncident(incident(job.incidentId())).fullMask();
        long failures = runs(job.id()).stream().filter(r -> r.factorMask()==full && "COMPLETED".equals(r.status())
                && r.observation().outcome()==Observation.Outcome.FAILURE).count();
        if (failures != job.repetitions()) throw new ExperimentAbortedException("Baseline did not reproduce in every trial");
        transition(job.incidentId(), Status.REPRODUCED);
        transition(job.incidentId(), Status.INVESTIGATING);
    }
    @Transactional public Optional<Observation> beginTrial(Job job, int mask, int repetition) {
        requireOwner(job);
        if (!Instant.now().isBefore(job.deadline())) throw new ExperimentAbortedException("Investigation time budget exhausted");
        var cached = db.query("SELECT observation::text FROM experiment_runs WHERE investigation_id=? AND factor_mask=? AND repetition=? AND status='COMPLETED'",
                (r,n) -> decode(r.getString(1), Observation.class), job.id(), mask, repetition);
        if (!cached.isEmpty()) return Optional.of(cached.getFirst());
        db.update("INSERT INTO experiment_runs(investigation_id,factor_mask,repetition,attempt,status) VALUES(?,?,?,?,'RUNNING')", job.id(), mask, repetition, job.attempts());
        return Optional.empty();
    }
    @Transactional public void finishTrial(Job job, int mask, int repetition, Observation observation, Map<String,Object> measurements) {
        requireOwner(job);
        int updated = db.update("UPDATE experiment_runs SET status='COMPLETED',completed_at=clock_timestamp(),observation=?::jsonb,measurements=?::jsonb WHERE investigation_id=? AND factor_mask=? AND repetition=? AND attempt=? AND status='RUNNING'",
                encode(observation), encode(measurements), job.id(), mask, repetition, job.attempts());
        if (updated != 1) throw new ExperimentAbortedException("Trial was not owned by this attempt");
    }
    @Transactional public void complete(Job job, CounterfactualEngine.Report report, String reason) {
        requireOwner(job);
        var scenario=ScenarioDefinition.forIncident(incident(job.incidentId()));
        List<Run> completed = runs(job.id()).stream().filter(r -> "COMPLETED".equals(r.status())).toList();
        if (report.state() == CounterfactualEngine.ProofState.PROVEN) {
            if (!Boolean.TRUE.equals(db.queryForObject("SELECT deadline>clock_timestamp() FROM investigations WHERE id=?", Boolean.class, job.id())))
                throw new ExperimentAbortedException("Investigation time budget exhausted before proof commit");
            if (!scenario.version().equals(job.scenarioVersion()) || ScenarioDefinition.missing(incident(job.incidentId()),timeline(job.incidentId()).events()) != null)
                throw new ExperimentAbortedException("Incident evidence is insufficient for proof");
            if (completed.size() != (scenario.fullMask()+1) * job.repetitions() || report.trials().size() != completed.size())
                throw new ExperimentAbortedException("Proof is missing durable trials");
            for (var trial : report.trials()) {
                boolean exists = completed.stream().anyMatch(r -> r.factorMask()==scenario.mask(trial.factors())
                        && r.repetition()==trial.repetition() && r.observation().equals(trial.observation()));
                if (!exists) throw new ExperimentAbortedException("Proof does not match persisted observations");
            }
            var recomputed = new CounterfactualEngine().investigate(scenario.factors(), job.repetitions(), scenario.scope(),
                    (factors, repetition) -> completed.stream().filter(r -> r.factorMask()==scenario.mask(factors) && r.repetition()==repetition)
                            .findFirst().orElseThrow().observation());
            if (recomputed.state()!=CounterfactualEngine.ProofState.PROVEN || !recomputed.minimalCausalSets().equals(report.minimalCausalSets()))
                throw new ExperimentAbortedException("Persisted experimental outcomes do not establish this causal proof");
            report = recomputed;
        }
        Proof proof = new Proof(job.incidentId(), job.id(), Instant.now(), reason, report);
        db.update("INSERT INTO causal_proofs(investigation_id,incident_id,report) VALUES(?,?,?::jsonb)", job.id(), job.incidentId(), encode(proof));
        transition(job.incidentId(), report.state()==CounterfactualEngine.ProofState.PROVEN ? Status.PROVEN : Status.INCONCLUSIVE);
        db.update("UPDATE experiment_runs SET status='ABANDONED',completed_at=clock_timestamp() WHERE investigation_id=? AND status='RUNNING'", job.id());
        db.update("UPDATE investigations SET status='COMPLETED',completed_at=clock_timestamp(),reason=?,lease_token=NULL,lease_until=NULL WHERE id=?", reason, job.id());
    }
    public Proof proof(UUID incidentId) {
        incident(incidentId);
        return db.query("SELECT report::text FROM causal_proofs WHERE incident_id=?", (r,n) -> decode(r.getString(1), Proof.class), incidentId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(CONFLICT, "Investigation has not produced a terminal proof"));
    }
}
