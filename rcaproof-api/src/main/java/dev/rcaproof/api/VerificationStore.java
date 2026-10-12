package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rcaproof.demo.worker.PaymentProcessor;
import dev.rcaproof.demo.provider.ProviderApplication;
import dev.rcaproof.remediation.RemediationPlanner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static dev.rcaproof.api.VerificationModels.*;
import static org.springframework.http.HttpStatus.*;

@Repository
public class VerificationStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final DecisionService decisions;
    private final OrganizationProfile organization;
    private final BuildProvenance provenance;
    public VerificationStore(JdbcTemplate db,ObjectMapper json,DecisionService decisions,OrganizationProfile organization,BuildProvenance provenance) {
        this.db=db; this.json=json; this.decisions=decisions; this.organization=organization;
        this.provenance=provenance;
    }
    static String artifactDigest() {
        return artifactDigest("IDEMPOTENCY_LEDGER");
    }
    static String artifactDigest(String candidate) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            var types=candidate.equals("BOUNDED_RETRY")?List.of(dev.rcaproof.experiment.RetryingHttpClient.class,dev.rcaproof.experiment.RetryingHttpClient.Result.class,
                    dev.rcaproof.experiment.RetryingHttpClient.Attempt.class,dev.rcaproof.experiment.RetryingHttpClient.TimedResult.class,
                    dev.rcaproof.experiment.RetryContract.class,HttpRetrySandbox.class,HttpRetrySandbox.Sample.class,HttpRetrySandbox.HttpSession.class,
                    HttpTransportSandbox.class,HttpTransportSandbox.Measurement.class,VerificationGate.class):List.of(PaymentProcessor.class,ProviderApplication.class,DuplicatePaymentSandbox.class,
                    Class.forName(DuplicatePaymentSandbox.class.getName()+"$LabSession"),
                    Class.forName(DuplicatePaymentSandbox.class.getName()+"$LabClock"),VerificationGate.class);
            for (Class<?> type:types) {
                try (var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                    if (stream==null) throw new IllegalStateException("Missing candidate bytecode");
                    digest.update(stream.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint candidate implementation",e); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private <T> T decode(String value,Class<T> type) {
        if (value==null) return null;
        try { return json.readValue(value,type); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Job map(ResultSet r,int row) throws SQLException {
        return new Job(r.getObject("id",UUID.class),r.getObject("incident_id",UUID.class),r.getString("candidate_id"),r.getString("status"),
                r.getTimestamp("created_at").toInstant(),r.getTimestamp("deadline").toInstant(),r.getObject("lease_token",UUID.class),r.getInt("attempts"),r.getString("artifact_digest"),decode(r.getString("binding"),BuildProvenance.Binding.class));
    }
    @Transactional public View enqueue(UUID incidentId,String candidateId) {
        var plan=decisions.plan(incidentId); // also locks incident for idempotency
        var existing=db.query("SELECT * FROM verification_runs WHERE incident_id=? AND candidate_id=?",this::map,incidentId,candidateId);
        if (!existing.isEmpty()) return view(existing.getFirst().id());
        var candidate=plan.plan().candidates().stream().filter(c->c.id().equals(candidateId)).findFirst()
                .orElseThrow(()->new ResponseStatusException(CONFLICT,"No remediation candidate for this incident"));
        if (candidate.eligibility()!=RemediationPlanner.Eligibility.ELIGIBLE_FOR_VERIFICATION || !Set.of("IDEMPOTENCY_LEDGER","BOUNDED_RETRY").contains(candidateId))
            throw new ResponseStatusException(CONFLICT,"Candidate is rejected, requires review, or has no implemented verification adapter");
        if (!plan.organizationProfile().sha256().equals(organization.snapshot().sha256()))
            throw new ResponseStatusException(CONFLICT,"Organization profile changed; create a new incident for a new evaluation");
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO verification_runs(id,incident_id,candidate_id,artifact_digest,binding,status) VALUES(?,?,?,?,?::jsonb,'QUEUED')",
                id,incidentId,candidateId,artifactDigest(candidateId),encode(provenance.binding()));
        return view(id);
    }
    Job job(UUID id) {
        return db.query("SELECT * FROM verification_runs WHERE id=?",this::map,id).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Verification not found"));
    }
    public List<Check> checks(Job job) {
        return db.query("SELECT payload::text FROM verification_checks WHERE run_id=? AND attempt=? ORDER BY name",
                (r,n)->decode(r.getString(1),Check.class),job.id(),job.attempts());
    }
    public View view(UUID id) {
        Job job=job(id);
        Report report=db.queryForObject("SELECT report::text FROM verification_runs WHERE id=?",(r,n)->decode(r.getString(1),Report.class),id);
        return new View(id,job.incidentId(),job.candidateId(),job.status(),job.attempts(),job.createdAt(),job.deadline(),checks(job),report);
    }
    @Transactional public Optional<Job> claim() {
        var candidates=db.query("SELECT * FROM verification_runs WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<clock_timestamp()) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",this::map);
        if (candidates.isEmpty()) return Optional.empty();
        UUID id=candidates.getFirst().id();
        db.update("UPDATE verification_runs SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=clock_timestamp()+interval '90 seconds' WHERE id=?",UUID.randomUUID(),id);
        return Optional.of(job(id));
    }
    public boolean heartbeat(Job job) {
        return db.update("UPDATE verification_runs SET lease_until=clock_timestamp()+interval '90 seconds' WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>clock_timestamp()",job.id(),job.leaseToken())==1;
    }
    private void owner(Job job) {
        if (db.query("SELECT id FROM verification_runs WHERE id=? AND lease_token=? AND status='RUNNING' AND lease_until>clock_timestamp() FOR UPDATE",
                (r,n)->r.getObject(1),job.id(),job.leaseToken()).isEmpty()) throw new IllegalStateException("Verification lease lost");
    }
    @Transactional public void checkpoint(Job job,Check check) {
        owner(job); budget(job);
        db.update("INSERT INTO verification_checks(run_id,attempt,name,payload) VALUES(?,?,?,?::jsonb)",job.id(),job.attempts(),check.name(),encode(check));
    }
    public void budget(Job job) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Verification interrupted");
        if (!Instant.now().isBefore(job.deadline())) throw new IllegalStateException("Verification time budget exhausted");
    }
    @Transactional public void complete(Job job,String error) {
        owner(job);
        var checks=checks(job);
        var plan=decisions.existingPlan(job.incidentId());
        String status=error!=null || !Instant.now().isBefore(job.deadline()) ? "INCONCLUSIVE" : VerificationGate.status(job.candidateId(),checks);
        if (!job.artifactDigest().equals(artifactDigest(job.candidateId())) || !plan.organizationProfile().sha256().equals(organization.snapshot().sha256())
                || !Objects.equals(job.binding(),provenance.binding())) status="INCONCLUSIVE";
        boolean retry=job.candidateId().equals("BOUNDED_RETRY");
        var report=new Report(status,VerificationGate.version(job.candidateId()),job.artifactDigest(),plan.organizationProfile().sha256(),
                retry?HttpTransportSandbox.SCOPE:"LOCAL_LAB: LocalStack SQS/DynamoDB; durable single-process HTTP provider mock; controlled worker exceptions; 24h cache and retained reconciliation journal; declared 20-order load; guarded legacy backfill; rollback preserves provider keys",
                checks,VerificationGate.blockers(),error!=null?error:"Deterministic safety, completion and suite-coverage gates evaluated; certification requires signed CI evidence",job.binding());
        db.update("UPDATE verification_runs SET status=?,report=?::jsonb,lease_token=NULL,lease_until=NULL WHERE id=?",status,encode(report),job.id());
    }
}
