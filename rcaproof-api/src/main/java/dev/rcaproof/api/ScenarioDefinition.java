package dev.rcaproof.api;

import dev.rcaproof.experiment.Observation;
import dev.rcaproof.experiment.ScenarioLab;
import dev.rcaproof.experiment.RetryContract;
import java.util.*;
import static dev.rcaproof.api.IncidentModels.*;

/** Versioned, bounded scenario adapters; unknown timeout causes deliberately abstain. */
record ScenarioDefinition(String version, List<String> factors, String scope) {
    static ScenarioDefinition forIncident(Incident incident) {
        if (incident.failureSignature().equals("RETRY_STORM")) return new ScenarioDefinition(RetryContract.VERSION,RetryContract.FACTORS,RetryContract.SCOPE);
        if (isBehavioral(incident)) {
            var definition=ScenarioLab.find(incident.failureSignature()).orElseThrow();
            return new ScenarioDefinition(ScenarioLab.VERSION,definition.factors(),ScenarioLab.SCOPE);
        }
        return switch (incident.failureSignature()) {
            case "DUPLICATE_PAYMENT" -> new ScenarioDefinition(DuplicatePaymentSandbox.VERSION,
                    DuplicatePaymentSandbox.FACTORS, DuplicatePaymentSandbox.SCOPE);
            case "ACCESS_DENIED" -> new ScenarioDefinition("access-denied-v1",
                    List.of("MISSING_PERMISSION", "ALTERNATE_DEPLOYMENT"),
                    "Synthetic local resource authorization evaluator; supplied policy snapshot; not AWS IAM verification; access-denied-v1");
            default -> new ScenarioDefinition("intermittent-timeout-v1", List.of("UNKNOWN_TIMEOUT_CAUSE"),
                    "No distinguishing timeout intervention available; insufficient telemetry; intermittent-timeout-v1");
        };
    }
    int fullMask() { return (1 << factors.size()) - 1; }
    int mask(Set<String> enabled) {
        int result=0;
        for (int i=0;i<factors.size();i++) if (enabled.contains(factors.get(i))) result |= 1 << i;
        return result;
    }
    Set<String> factors(int mask) {
        Set<String> enabled=new TreeSet<>();
        for (int i=0;i<factors.size();i++) if ((mask & (1 << i))!=0) enabled.add(factors.get(i));
        return Set.copyOf(enabled);
    }
    static String missing(Incident incident, List<TimelineEvent> timeline) {
        if (incident.failureSignature().equals("RETRY_STORM")) return retryEvidenceMissing(timeline);
        if (isBehavioral(incident)) {
            if (timeline.size()!=1) return "Need exactly one bounded behavioral-lab observation";
            var event=timeline.getFirst().event();
            if (event.eventType()!=EventType.LAB_OBSERVATION || !ScenarioLab.VERSION.equals(event.metadata().get("scenarioVersion"))
                    || !incident.failureSignature().equals(event.metadata().get("signature")) || !"FAILURE".equals(event.result())
                    || !Set.of("STANDARD","DISTRACTOR","CONTROL_FAILURE").contains(event.metadata().getOrDefault("variant","")))
                return "Need a complete matching lab observation; telemetry gaps cannot establish a cause";
            return null;
        }
        if (incident.failureSignature().equals("DUPLICATE_PAYMENT")) return EvidenceAssessment.missing(timeline);
        if (incident.failureSignature().equals("INTERMITTENT_TIMEOUT"))
            return "Dependency latency and network delay remain possible; collect correlated client/server spans and dependency timings before proposing a patch";
        if (timeline.stream().anyMatch(e -> e.event().eventType()==EventType.TELEMETRY_GAP))
            return "Telemetry gaps prevent confirming expected access denial";
        var events=timeline.stream().map(TimelineEvent::event).toList();
        // Exactly one bounded request/policy snapshot; contradictory or ambiguous snapshots abstain.
        if (events.size()!=1) return "Need exactly one synthetic access-denied request and policy snapshot";
        Evidence e=events.getFirst();
        if (e.eventType()!=EventType.ACCESS_DENIED || !"DENIED".equals(e.result())
                || e.requestId()==null || e.requestId().isBlank() || e.resource()==null || e.resource().isBlank()
                || !"GET".equals(e.operation()) || !"true".equals(e.metadata().get("serviceHealthy"))
                || !"true".equals(e.metadata().get("resourceExists")) || !"demo-read-policy-v1".equals(e.metadata().get("policyVersion"))
                || !"false".equals(e.metadata().get("hasReadPermission")))
            return "Need a correlated GET denial, healthy service, existing resource and supported policy snapshot with missing read permission";
        return null;
    }
    static SandboxFactory.Session authorizationSession(Incident incident, Evidence evidence) {
        return new SandboxFactory.Session() {
            public SandboxFactory.Result run(Set<String> enabled, int repetition) {
                boolean permission=!enabled.contains("MISSING_PERMISSION");
                int status=authorize(evidence.resource(), permission);
                return new SandboxFactory.Result(new Observation(status==403 ? Observation.Outcome.FAILURE : Observation.Outcome.HEALTHY,
                        "Observed HTTP-equivalent status "+status+"; FAILURE denotes the reported denial, not a system defect"),
                        Map.of("status",status,"hasReadPermission",permission,"resource",evidence.resource(),
                                "deployment",enabled.contains("ALTERNATE_DEPLOYMENT") ? "B" : "A",
                                "policyVersion","demo-read-policy-v1","synthetic",true));
            }
            public void close() {}
        };
    }
    static int authorize(String resource, boolean hasReadPermission) {
        if (resource==null || resource.isBlank()) return 404;
        return hasReadPermission ? 200 : 403;
    }
    static boolean isBehavioral(Incident incident) { return !Set.of("ACCESS_DENIED","RETRY_STORM").contains(incident.failureSignature()) && ScenarioLab.find(incident.failureSignature()).isPresent(); }
    static String retryEvidenceMissing(List<TimelineEvent> timeline) {
        if (timeline.size()!=6) return "Need six correlated HTTP 500 attempts and matching provider receipts for one GET";
        Set<String> requests=new HashSet<>(),ordinals=new HashSet<>(),resources=new HashSet<>();
        for (var item:timeline) {
            var e=item.event();
            if (e.eventType()!=EventType.HTTP_DEPENDENCY_ATTEMPT || !"GET".equals(e.operation()) || !"500".equals(e.result())
                    || e.requestId()==null || e.requestId().isBlank() || e.resource()==null || e.resource().isBlank() || !RetryContract.VERSION.equals(e.metadata().get("scenarioVersion"))
                    || !"6".equals(e.metadata().get("providerRequests")) || !"true".equals(e.metadata().get("correlated")))
                return "Missing correlated client/provider HTTP evidence; behavioral observations do not establish the HTTP incident";
            requests.add(e.requestId()); resources.add(e.resource()); ordinals.add(e.metadata().getOrDefault("attempt",""));
        }
        return requests.size()==1 && resources.size()==1 && ordinals.equals(Set.of("1","2","3","4","5","6"))?null:"HTTP attempts must have one request and resource identity and unique ordinals 1 through 6";
    }
    static SandboxFactory.Session behavioralSession(Incident incident,Evidence evidence) {
        return new SandboxFactory.Session() {
            public SandboxFactory.Result run(Set<String> factors,int repetition) {
                var result=ScenarioLab.run(incident.failureSignature(),factors,evidence.metadata().get("variant"),repetition);
                return new SandboxFactory.Result(result.observation(),result.values());
            }
            public void close() {}
        };
    }
}
