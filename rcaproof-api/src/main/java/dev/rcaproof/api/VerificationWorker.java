package dev.rcaproof.api;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicReference;
import static dev.rcaproof.api.VerificationModels.*;

@Component
@ConditionalOnProperty(name="rcaproof.worker-enabled",havingValue="true",matchIfMissing=true)
public class VerificationWorker {
    private final VerificationStore store;
    private final IncidentStore incidents;
    private final DuplicatePaymentSandbox sandbox;
    private final HttpRetrySandbox retry;
    private final AtomicReference<Job> active=new AtomicReference<>();
    public VerificationWorker(VerificationStore store,IncidentStore incidents,DuplicatePaymentSandbox sandbox) {
        this(store,incidents,sandbox,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public VerificationWorker(VerificationStore store,IncidentStore incidents,DuplicatePaymentSandbox sandbox,HttpRetrySandbox retry) {
        this.store=store; this.incidents=incidents; this.sandbox=sandbox; this.retry=retry;
    }
    @Scheduled(fixedDelayString="${rcaproof.poll-ms:1000}") public void poll() {
        try { store.claim().ifPresent(this::execute); }
        catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Verification polling failed; persisted lease permits recovery",e); }
    }
    @Scheduled(fixedDelay=10000) public void heartbeat() {
        var job=active.get(); if (job!=null) store.heartbeat(job);
    }
    public void execute(Job job) {
        active.set(job);
        try {
            store.budget(job);
            if (job.attempts()>3) throw new IllegalStateException("Verification restart budget exhausted");
            if (!job.artifactDigest().equals(VerificationStore.artifactDigest(job.candidateId()))) throw new IllegalStateException("Candidate implementation changed since enqueue");
            if (job.candidateId().equals("BOUNDED_RETRY")) {
                if (retry==null) throw new IllegalStateException("HTTP retry adapter not configured");
                retry.verify(check->store.checkpoint(job,check),()->store.budget(job));
            } else sandbox.verify(job.id(),job.attempts(),incidents.incident(job.incidentId()).order(),
                    check->store.checkpoint(job,check),()->store.budget(job));
            store.complete(job,null);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return;
            LoggerFactory.getLogger(getClass()).warn("Verification {} could not complete",job.id(),e);
            store.complete(job,"Verification interrupted by "+e.getClass().getSimpleName()+": "+e.getMessage());
        } finally { active.compareAndSet(job,null); }
    }
}
