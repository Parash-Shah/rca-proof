package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rcaproof.classification.FaultClassifier;
import dev.rcaproof.remediation.RemediationPlanner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class DecisionService {
    public record Classification(UUID incidentId, UUID investigationId, Instant createdAt, String ruleVersion,
                                 String proofScope, FaultClassifier.Decision decision) {}
    public record Remediations(UUID incidentId, UUID investigationId, Instant createdAt, String catalogVersion,
                               OrganizationProfile.Snapshot organizationProfile, RemediationPlanner.Plan plan) {}
    private final IncidentStore incidents;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final OrganizationProfile organization;
    public DecisionService(IncidentStore incidents,JdbcTemplate db,ObjectMapper json,OrganizationProfile organization) {
        this.incidents=incidents; this.db=db; this.json=json; this.organization=organization;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void lock(UUID id) {
        if (db.query("SELECT id FROM incidents WHERE id=? FOR UPDATE",(r,n)->r.getObject(1),id).isEmpty())
            throw new ResponseStatusException(NOT_FOUND,"Incident not found");
    }
    /** Cache a deterministic classification of an immutable terminal proof. Never triggers experiments. */
    @Transactional public Classification classification(UUID id) {
        lock(id);
        var cached=db.query("SELECT payload::text FROM classifications WHERE incident_id=?",(r,n)->decode(r.getString(1),Classification.class),id);
        if (!cached.isEmpty()) return cached.getFirst();
        var incident=incidents.incident(id);
        var proof=incidents.proof(id); // 409 until investigation completes
        var result=new Classification(id,proof.investigationId(),Instant.now(),"classification-v1",proof.report().scope(),
                new FaultClassifier().classify(incident.failureSignature(),proof.report()));
        db.update("INSERT INTO classifications(incident_id,investigation_id,payload) VALUES(?,?,?::jsonb)",id,proof.investigationId(),encode(result));
        return result;
    }
    @Transactional public Remediations plan(UUID id) {
        lock(id);
        var existing=findPlan(id);
        if (!existing.isEmpty()) return existing.getFirst();
        var classification=classification(id);
        var result=new Remediations(id,classification.investigationId(),Instant.now(),"remediation-catalog-v1",organization.snapshot(),
                new RemediationPlanner().plan(classification.decision(),organization.snapshot().organization()));
        db.update("INSERT INTO remediation_plans(incident_id,payload) VALUES(?,?::jsonb)",id,encode(result));
        return result;
    }
    private List<Remediations> findPlan(UUID id) {
        return db.query("SELECT payload::text FROM remediation_plans WHERE incident_id=?",(r,n)->decode(r.getString(1),Remediations.class),id);
    }
    public Remediations existingPlan(UUID id) {
        incidents.incident(id);
        return findPlan(id).stream().findFirst().orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Remediation plan has not been requested"));
    }
}
