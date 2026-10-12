package dev.rcaproof.api;

import dev.rcaproof.causal.CounterfactualEngine;
import dev.rcaproof.experiment.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static dev.rcaproof.api.IncidentModels.*;

@Component
@ConditionalOnProperty(name="rcaproof.worker-enabled",havingValue="true",matchIfMissing=true)
public class InvestigationWorker {
    private final IncidentStore store;
    private final SandboxFactory sandbox;
    private final AtomicReference<Job> active=new AtomicReference<>();
    public InvestigationWorker(IncidentStore store,SandboxFactory sandbox) { this.store=store; this.sandbox=sandbox; }

    @Scheduled(fixedDelayString="${rcaproof.poll-ms:1000}")
    public void poll() {
        try { store.claim().ifPresent(this::execute); }
        catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Investigation polling failed; durable lease enables recovery",e); }
    }
    @Scheduled(fixedDelay=10000)
    public void heartbeat() {
        Job job=active.get();
        if (job!=null) {
            try { store.heartbeat(job); }
            catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Could not renew investigation lease {}",job.id()); }
        }
    }
    public void execute(Job job) {
        Incident incident=store.incident(job.incidentId());
        var scenario=ScenarioDefinition.forIncident(incident);
        active.set(job);
        try {
            if (!scenario.version().equals(job.scenarioVersion())) throw new ExperimentAbortedException("Scenario version changed; create a new incident");
            if (job.attempts()>3) throw new ExperimentAbortedException("Investigation restart budget exhausted");
            if (!Instant.now().isBefore(job.deadline())) throw new ExperimentAbortedException("Investigation time budget exhausted");
            var timeline=store.timeline(job.incidentId()).events();
            String missing=ScenarioDefinition.missing(incident,timeline);
            if (missing!=null) throw new ExperimentAbortedException(missing);
            CounterfactualEngine.Report report;
            try (var session=incident.failureSignature().equals("ACCESS_DENIED")
                    ? ScenarioDefinition.authorizationSession(incident,timeline.getFirst().event())
                    : ScenarioDefinition.isBehavioral(incident)?ScenarioDefinition.behavioralSession(incident,timeline.getFirst().event()):sandbox.open(job,incident)) {
                report=new CounterfactualEngine().investigate(scenario.factors(),job.repetitions(),scenario.scope(),(factors,repetition) -> {
                    try {
                    int mask=scenario.mask(factors);
                    if (mask!=scenario.fullMask()) store.baselineReproduced(job);
                    var cached=store.beginTrial(job,mask,repetition);
                    if (cached.isPresent()) return cached.get();
                    SandboxFactory.Result result;
                    try { result=session.run(factors,repetition); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ExperimentAbortedException("Investigation interrupted"); }
                    catch (Exception e) {
                        result=new SandboxFactory.Result(new Observation(Observation.Outcome.INCONCLUSIVE,"Sandbox operation failed: "+e.getClass().getSimpleName()),
                                Map.of("errorType",e.getClass().getSimpleName()));
                    }
                    store.finishTrial(job,mask,repetition,result.observation(),result.measurements());
                    return result.observation();
                    } catch (ExperimentAbortedException e) { throw e; }
                    catch (RuntimeException e) {
                        LoggerFactory.getLogger(getClass()).warn("Trial checkpoint failed for {}",job.id(),e);
                        throw new ExperimentAbortedException("Trial checkpoint could not be persisted");
                    }
                });
            }
            if (!Instant.now().isBefore(job.deadline())) throw new ExperimentAbortedException("Investigation time budget exhausted");
            store.complete(job,report,report.state()==CounterfactualEngine.ProofState.PROVEN
                    ? "Causal relationship established within the recorded sandbox scope, linked to supplied incident evidence"
                    : "Counterfactual controls or repeated outcomes did not support a complete proof");
        } catch (Exception e) {
            LoggerFactory.getLogger(getClass()).warn("Investigation {} ended without proof: {}",job.id(),e.toString());
            // Shutdown is not experimental evidence. Keep the lease/checkpoints recoverable.
            if (Thread.currentThread().isInterrupted()) return;
            try {
                String reason=e instanceof ExperimentAbortedException ? e.getMessage() : "Investigation infrastructure failed: "+e.getClass().getSimpleName();
                try { if (incident.failureSignature().equals("DUPLICATE_PAYMENT")) sandbox.cleanup(job); }
                catch (Exception cleanup) {
                    reason += "; sandbox cleanup incomplete (local resources may remain)";
                    LoggerFactory.getLogger(getClass()).warn("Sandbox cleanup failed for {}",job.id(),cleanup);
                }
                var trials=store.runs(job.id()).stream().filter(r -> "COMPLETED".equals(r.status()))
                        .map(r -> new CounterfactualEngine.Trial(scenario.factors(r.factorMask()),r.repetition(),r.observation())).toList();
                store.complete(job,new CounterfactualEngine.Report(CounterfactualEngine.ProofState.INCONCLUSIVE,List.of(),trials,job.repetitions(),scenario.scope()),reason);
            } catch (Exception recovery) {
                LoggerFactory.getLogger(getClass()).warn("Could not finalize investigation {}; lease will permit retry",job.id(),recovery);
            }
        } finally { active.compareAndSet(job,null); }
    }
}
