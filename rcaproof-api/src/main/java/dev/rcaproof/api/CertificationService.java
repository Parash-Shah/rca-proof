package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static dev.rcaproof.api.CertificationGate.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class CertificationService {
    public record Certificate(UUID id,UUID verificationId,String status,Instant issuedAt,Instant expiresAt,
                              String scope,String verificationSha256,String ciEvidenceSha256,BuildProvenance.Binding binding,
                              Attestation ci,List<String> blockers) {}
    private final VerificationStore verifications;
    private final BuildProvenance build;
    private final OrganizationProfile organization;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String publicKey,repository;
    public CertificationService(VerificationStore verifications,BuildProvenance build,OrganizationProfile organization,
            JdbcTemplate db,ObjectMapper json,@Value("${rcaproof.certification.public-key:}") String publicKey,
            @Value("${rcaproof.certification.repository:Parash-Shah/rca-proof}") String repository) {
        this.verifications=verifications; this.build=build; this.organization=organization; this.db=db; this.json=json;
        this.publicKey=publicKey; this.repository=repository;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Result evaluate(VerificationModels.View run,Envelope envelope) {
        var scenario=db.queryForObject("SELECT failure_signature FROM incidents WHERE id=?",String.class,run.incidentId());
        if (!CertificationGate.signature(run.candidateId()).equals(scenario))
            return new Result("NOT_CERTIFIED",List.of("Incident scenario does not match the candidate certification contract"),null);
        return new CertificationGate(json).evaluate(run,envelope,build.binding(),VerificationStore.artifactDigest(run.candidateId()),
                organization.snapshot().sha256(),publicKey,repository,Instant.now());
    }
    private Certificate assessed(VerificationModels.View run,Envelope envelope,Certificate stored) {
        if (run.report()==null) throw new ResponseStatusException(CONFLICT,"Verification has not completed");
        Result result=evaluate(run,envelope);
        String evidenceHash=envelope==null?null:BuildProvenance.sha256(encode(envelope).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String reportHash=BuildProvenance.sha256(encode(run.report()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (stored!=null && !stored.verificationSha256().equals(reportHash))
            return new Certificate(stored.id(),run.id(),"STALE",stored.issuedAt(),stored.expiresAt(),stored.scope(),stored.verificationSha256(),stored.ciEvidenceSha256(),stored.binding(),stored.ci(),List.of("Persisted verification report changed"));
        if (stored!=null && result.status().equals("CERTIFIED")) return stored;
        Instant issued=stored==null?Instant.now():stored.issuedAt();
        return new Certificate(stored==null?null:stored.id(),run.id(),stored!=null?"STALE":result.status(),issued,
                result.attestation()==null || result.attestation().issuedAt()==null?null:result.attestation().issuedAt().plus(Duration.ofDays(7)),
                run.report().scope(),reportHash,evidenceHash,run.report().binding(),result.attestation(),result.blockers());
    }
    @Transactional public Certificate issue(UUID id,Envelope envelope) {
        verifications.view(id); // consistent 404
        db.query("SELECT id FROM verification_runs WHERE id=? FOR UPDATE",(r,n)->r.getObject(1),id);
        var run=verifications.view(id);
        var result=assessed(run,envelope,null);
        if (!result.status().equals("CERTIFIED")) return result;
        var old=db.query("SELECT certificate::text FROM remediation_certificates WHERE verification_id=? AND evidence_digest=?",
                (r,n)->decode(r.getString(1),Certificate.class),id,result.ciEvidenceSha256());
        if (!old.isEmpty()) return assessed(run,envelope,old.getFirst());
        var certificate=new Certificate(UUID.randomUUID(),id,result.status(),result.issuedAt(),result.expiresAt(),result.scope(),
                result.verificationSha256(),result.ciEvidenceSha256(),result.binding(),result.ci(),result.blockers());
        db.update("INSERT INTO remediation_certificates(id,verification_id,evidence_digest,envelope,certificate) VALUES(?,?,?,?::jsonb,?::jsonb)",
                certificate.id(),id,certificate.ciEvidenceSha256(),encode(envelope),encode(certificate));
        return certificate;
    }
    @Transactional(readOnly=true) public Certificate certificate(UUID id) {
        var run=verifications.view(id);
        var saved=db.query("SELECT envelope::text,certificate::text FROM remediation_certificates WHERE verification_id=? ORDER BY created_at DESC LIMIT 1",
                (r,n)->assessed(run,decode(r.getString(1),Envelope.class),decode(r.getString(2),Certificate.class)),id);
        return saved.isEmpty()?assessed(run,null,null):saved.getFirst();
    }
}
