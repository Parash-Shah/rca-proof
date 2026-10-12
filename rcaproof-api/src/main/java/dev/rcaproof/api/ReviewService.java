package dev.rcaproof.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@Service
public class ReviewService {
    public record Review(UUID id,UUID verificationId,UUID certificateId,String repository,String baseBranch,String branch,
                         String headSha,String baseSha,String title,String body,String status,Integer pullNumber,String pullUrl,
                         String detail,Instant createdAt,Instant updatedAt) {}
    private final JdbcTemplate db;
    private final VerificationStore verifications;
    private final CertificationService certificates;
    private final IncidentStore incidents;
    private final DecisionService decisions;
    private final GitHubReviewClient github;
    private final String repository,baseBranch;
    public ReviewService(JdbcTemplate db,VerificationStore verifications,CertificationService certificates,IncidentStore incidents,
                         DecisionService decisions,GitHubReviewClient github,
                         @Value("${rcaproof.github.repository:Parash-Shah/rca-proof}") String repository,
                         @Value("${rcaproof.github.base-branch:main}") String baseBranch) {
        this.db=db; this.verifications=verifications; this.certificates=certificates; this.incidents=incidents;
        this.decisions=decisions; this.github=github; this.repository=repository; this.baseBranch=baseBranch;
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || !baseBranch.matches("[A-Za-z0-9_/-]+")) throw new IllegalArgumentException("Invalid review destination");
    }
    private CertificationService.Certificate certificate(UUID id) {
        var certificate=certificates.certificate(id);
        if (!"CERTIFIED".equals(certificate.status()) || certificate.id()==null || certificate.ci()==null
                || !repository.equals(certificate.ci().repository()))
            throw new ResponseStatusException(CONFLICT,"A current certificate for the configured review repository is required");
        return certificate;
    }
    @Transactional public Review prepare(UUID id) {
        var cert=certificate(id);
        db.query("SELECT id FROM verification_runs WHERE id=? FOR UPDATE",(r,n)->r.getObject(1),id);
        var existing=find(id);
        if (!existing.isEmpty()) return existing.getFirst();
        var run=verifications.view(id);
        var incident=incidents.incident(run.incidentId());
        var plan=decisions.existingPlan(run.incidentId());
        var chosen=plan.plan().candidates().stream().filter(c->c.id().equals(run.candidateId())).findFirst()
                .orElseThrow(()->new ResponseStatusException(CONFLICT,"Certified candidate has no remediation plan"));
        var source=cert.binding().source();
        String branch="rcaproof/"+run.incidentId()+"-"+id.toString().substring(0,8);
        boolean retry=run.candidateId().equals("BOUNDED_RETRY");
        String title=retry?"Bound HTTP retries and propagate caller deadlines":"Prevent duplicate payment effects during message redelivery";
        StringBuilder body=new StringBuilder("<!-- rcaproof-verification:"+id+" -->\n\n")
                .append("Incident `").append(run.incidentId()).append(retry?"` reported HTTP retry amplification. The verified candidate bounds GET attempts and shares the caller deadline across retries.\n\n":"` reported duplicate payment effects. The verified candidate carries a stable provider key and checks the payment ledger before processing redelivery.\n\n")
                .append("## Proven cause\n\n").append("Minimal causal sets: `").append(incidents.proof(run.incidentId()).report().minimalCausalSets()).append(retry?"`. Persistent HTTP 500 responses combined with excessive retries amplify dependency calls. Transport checks are nearby-failure validation, not additional causal claims.\n\n":"`. Worker crash is the trigger; redelivery exposes missing side-effect idempotency.\n\n")
                .append("## Remediation and alternatives\n\n").append(chosen.title()).append("\n\n");
        for (var c:plan.plan().candidates()) body.append("- ").append(c.id()).append(": ").append(c.eligibility()).append(" — ").append(c.causalCoverage()).append("\n");
        body.append("\n## Evidence\n\n- Certificate: `").append(cert.id()).append("`\n- Scope: ").append(cert.scope())
                .append("\n- Verification: `").append(id).append("`, ").append(run.checks().size()).append(" passing checks")
                .append("\n- CI regression tests: ").append(cert.ci().tests()).append("; failures/errors/skips: 0")
                .append("\n- Source: `").append(source.baseCommit()).append("` → `").append(source.commit()).append("`")
                .append("\n- Patch SHA-256: `").append(source.patchSha256()).append("`\n- Application SHA-256: `").append(cert.binding().applicationSha256()).append("`")
                .append("\n- Policy SHA-256: `").append(run.report().organizationDigest()).append("`\n\n## Risk and rollback\n\n")
                .append(String.join("; ",chosen.risks())).append(". This certificate covers the local lab only.\n\n")
                .append(retry?"Rollback must preserve bounded attempts and caller deadlines. Do not restore excessive retries; persistent dependency failure must remain visible. No multi-hop cancellation or production SLO is certified.\n\n":"Drain in-flight work before rollback. Preserve provider keys and the ledger; never restore the non-idempotent configuration. Conflicting or legacy records require authoritative reconciliation.\n\n")
                .append("Human review is required. RCAProof does not merge or deploy.\n");
        UUID review=UUID.randomUUID();
        db.update("INSERT INTO change_requests(id,verification_id,certificate_id,repository,base_branch,branch,head_sha,base_sha,title,body,status) VALUES(?,?,?,?,?,?,?,?,?,?,'PREPARED')",
                review,id,cert.id(),repository,baseBranch,branch,source.commit(),source.baseCommit(),title,body.toString());
        return find(id).getFirst();
    }
    private List<Review> find(UUID id) {
        return db.query("SELECT * FROM change_requests WHERE verification_id=?",(r,n)->new Review(r.getObject("id",UUID.class),id,r.getObject("certificate_id",UUID.class),
                r.getString("repository"),r.getString("base_branch"),r.getString("branch"),r.getString("head_sha"),r.getString("base_sha"),r.getString("title"),r.getString("body"),
                r.getString("status"),r.getObject("pull_number",Integer.class),r.getString("pull_url"),r.getString("detail"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant()),id);
    }
    public Review get(UUID id) {
        verifications.view(id);
        var review=find(id).stream().findFirst().orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Review has not been prepared"));
        try { if (!certificate(id).id().equals(review.certificateId()) || !repository.equals(review.repository()) || !baseBranch.equals(review.baseBranch())) throw new IllegalStateException(); }
        catch (RuntimeException e) { return new Review(review.id(),id,review.certificateId(),review.repository(),review.baseBranch(),review.branch(),review.headSha(),review.baseSha(),review.title(),review.body(),"STALE",review.pullNumber(),review.pullUrl(),"Certificate no longer matches; prepare a new verification before review",review.createdAt(),review.updatedAt()); }
        return review;
    }
    @Transactional public Review publish(UUID id) {
        db.query("SELECT id FROM change_requests WHERE verification_id=? FOR UPDATE",(r,n)->r.getObject(1),id);
        var review=get(id);
        if (review.status().equals("STALE")) throw new ResponseStatusException(CONFLICT,review.detail());
        if (!github.configured()) throw new ResponseStatusException(SERVICE_UNAVAILABLE,"GitHub credentials are not configured; the prepared review remains available");
        try {
            var source=certificate(id).binding().source();
            if (!review.headSha().equals(source.commit()) || !review.baseSha().equals(source.baseCommit())) throw new IllegalStateException("Certificate subject changed");
            var pull=review.pullNumber()==null?github.find(repository,review.branch(),baseBranch):github.pull(repository,review.pullNumber());
            if (pull==null) {
                github.validateSubject(repository,baseBranch,source);
                github.ensureBranch(repository,review.branch(),source.commit());
                certificate(id); // expiration/key changes cannot be bypassed by preparation
                pull=github.create(repository,review.branch(),baseBranch,review.title(),review.body());
            }
            if (!source.commit().equals(pull.head()) || !source.baseCommit().equals(pull.base()) || !pull.body().contains("<!-- rcaproof-verification:"+id+" -->"))
                throw new IllegalStateException("Remote PR differs from the certified subject; human reconciliation required");
            if (pull.number()<1 || !pull.url().equals("https://github.com/"+repository+"/pull/"+pull.number())) throw new IllegalStateException("Unexpected GitHub PR identity");
            String status=pull.merged()?"MERGED_BY_HUMAN":pull.state().equals("closed")?"CLOSED":"OPEN";
            db.update("UPDATE change_requests SET status=?,pull_number=?,pull_url=?,detail=NULL,updated_at=clock_timestamp() WHERE verification_id=?",status,pull.number(),pull.url(),id);
        } catch (Exception e) {
            db.update("UPDATE change_requests SET status='RETRY_REQUIRED',detail=?,updated_at=clock_timestamp() WHERE verification_id=?",
                    e instanceof IllegalStateException?e.getMessage():"Review operation did not complete; retry reconciles remote state",id);
        }
        return find(id).getFirst();
    }
}
