package dev.rcaproof.remediation;

import dev.rcaproof.classification.FaultClassifier;
import java.util.*;

/** Scores are transparent planning heuristics, never experimental results or certification. */
public final class RemediationPlanner {
    public record Organization(String name, Set<String> languages, Set<String> messaging, Set<String> databases,
                               boolean newServiceReview, boolean requireIdempotency, boolean humanApproval,
                               boolean autoMerge, boolean autoDeploy) {
        public Organization { languages=Set.copyOf(languages); messaging=Set.copyOf(messaging); databases=Set.copyOf(databases); }
    }
    public enum Eligibility { ELIGIBLE_FOR_VERIFICATION, REQUIRES_ARCHITECTURE_REVIEW, REJECTED }
    public record Candidate(String id, String title, Eligibility eligibility, int score,
                            Map<String,Integer> scoreBreakdown, List<String> policyReasons,
                            String causalCoverage, List<String> assumptions, List<String> risks,
                            List<String> validationScenarios, String verificationStatus) {}
    public record Plan(String status, String recommendedCandidate, List<Candidate> candidates, String explanation) {}

    public Plan plan(FaultClassifier.Decision decision, Organization org) {
        if (decision.action()!=FaultClassifier.Action.PLAN_REMEDIATION)
            return new Plan(decision.action().name(),null,List.of(),decision.customerMessage());
        if (decision.category()==FaultClassifier.Category.APPLICATION_DEFECT) return retryPlan(org);
        List<Candidate> candidates=new ArrayList<>();
        candidates.add(candidate("VISIBILITY_EXTENSION","Increase visibility timeout",false,org,
                Set.of(),false,Map.of("causalCoverage",0,"organizationFit",20,"reliability",0,"operationalSimplicity",10,"reversibility",10),
                "May reduce overlapping work; does not prevent replay after a crash",
                List.of("Processing duration is bounded"),List.of("Duplicate charges remain possible; longer recovery delay")));
        candidates.add(candidate("FIFO_MIGRATION","Migrate to FIFO messaging",false,org,
                Set.of(),!org.messaging().contains("SQS_FIFO"),Map.of("causalCoverage",0,"organizationFit",10,"reliability",5,"operationalSimplicity",5,"reversibility",5),
                "Queue deduplication does not make an external payment side effect idempotent",
                List.of("Ordering groups and migration are defined"),List.of("Consumer retries can still repeat a charge; migration changes throughput")));
        candidates.add(candidate("IDEMPOTENCY_LEDGER","DynamoDB ledger plus provider idempotency",true,org,
                Set.of("DYNAMODB"),!org.databases().contains("DYNAMODB"),Map.of("causalCoverage",40,"organizationFit",org.databases().contains("DYNAMODB")?20:0,
                        "reliability",15,"operationalSimplicity",5,"reversibility",5),
                "Stable provider key protects the external side effect; conditional ledger writes record matching results and reject conflicts",
                List.of("Provider honors a stable per-order idempotency key across retries and restarts",
                        "Provider retention covers the replay horizon; unknown outcomes can be reconciled",
                        "Ledger retention and recovery preserve the same key"),
                List.of("A ledger alone cannot close the crash window after charging",
                        "Expired keys or premature ledger expiry can reintroduce duplicates; conflicting records require reconciliation")));
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparing(Candidate::id));
        String recommended=candidates.stream().filter(c -> c.eligibility()==Eligibility.ELIGIBLE_FOR_VERIFICATION)
                .map(Candidate::id).findFirst().orElse(null);
        return new Plan("PLANNED_NOT_VERIFIED",recommended,List.copyOf(candidates),
                "Catalog analysis only. Scores are 0-100 planning heuristics; policy gates override rank. No patch, certification, merge or deployment is authorized by this plan.");
    }
    private Plan retryPlan(Organization org) {
        boolean allowed=org.languages().contains("JAVA") && org.humanApproval() && !org.autoMerge() && !org.autoDeploy();
        var checks=List.of("Persistent HTTP 500 amplification", "Healthy GET", "Transient HTTP 500 recovery", "Non-retryable HTTP 400", "Concurrent request budgets", "Reject disabling retries", "Reject longer timeout", "Shared caller deadline", "Remaining budget propagation", "Connection loss and truncated response", "Expired deadline issues no request");
        var bounded=new Candidate("BOUNDED_RETRY","Bound GET retries to two attempts",allowed?Eligibility.ELIGIBLE_FOR_VERIFICATION:Eligibility.REJECTED,
                allowed?85:0,Map.of("causalCoverage",40,"organizationFit",allowed?20:0,"reliability",15,"operationalSimplicity",10),
                List.of(allowed?"Existing Java runtime; no new managed service; human review required":"Requires Java and human-controlled merge/deployment"),
                "Caps amplification while permitting recovery on a second attempt",List.of("GET is safe to retry", "Two attempts, fixed 25ms delay and a shared 3s caller budget are the retry lab policy; tighter caller budgets are supported"),
                List.of("Persistent failures still surface to callers", "A global concurrency budget, production backoff and jitter remain separate concerns"),checks,"NOT_RUN");
        var disabled=new Candidate("DISABLE_RETRIES","Disable retries",Eligibility.REJECTED,20,Map.of("causalCoverage",20),List.of("Must retain transient recovery"),
                "Bounds calls but loses recovery after one transient 500",List.of(),List.of("Availability regression"),checks,"NOT_RUN");
        var longer=new Candidate("LONGER_TIMEOUT","Increase HTTP timeout",Eligibility.REJECTED,10,Map.of("causalCoverage",0,"reversibility",10),List.of("Does not bound retries on HTTP 500"),
                "Longer request deadlines do not prevent retry amplification",List.of(),List.of("May increase resource occupancy"),checks,"NOT_RUN");
        return new Plan("PLANNED_NOT_VERIFIED",allowed?"BOUNDED_RETRY":null,List.of(bounded,disabled,longer),"HTTP retry catalog; the verification adapter experimentally checks safety and transient recovery. Certification requires the matching signed HTTP retry contract and committed build.");
    }
    private Candidate candidate(String id,String title,boolean covers,Organization org,Set<String> databases,boolean newInfra,
                                Map<String,Integer> scores,String coverage,List<String> assumptions,List<String> risks) {
        List<String> reasons=new ArrayList<>();
        boolean rejected=false;
        if (!covers) { reasons.add("Does not cover the proven duplicate-side-effect mechanism"); rejected=true; }
        if (org.requireIdempotency() && !covers) { reasons.add("Payment policy requires idempotent side effects"); rejected=true; }
        if (!org.languages().contains("JAVA") || !org.messaging().contains("SQS_STANDARD")) {
            reasons.add("Catalog implementation requires Java and the existing SQS Standard architecture"); rejected=true;
        }
        if (!org.humanApproval() || org.autoMerge() || org.autoDeploy()) {
            reasons.add("V1 requires human approval and forbids automatic merge/deploy"); rejected=true;
        }
        if (!org.databases().containsAll(databases)) reasons.add("Requires additional database capability: "+String.join(",",databases));
        boolean review=newInfra && org.newServiceReview();
        if (review) reasons.add("Organization requires architecture review for new infrastructure");
        if (!rejected && !review) reasons.add("Compatible with declared infrastructure and policy; assumptions still require verification");
        return new Candidate(id,title,rejected?Eligibility.REJECTED:review?Eligibility.REQUIRES_ARCHITECTURE_REVIEW:Eligibility.ELIGIBLE_FOR_VERIFICATION,
                scores.values().stream().mapToInt(Integer::intValue).sum(),Map.copyOf(scores),List.copyOf(reasons),coverage,assumptions,risks,
                List.of("Original incident replay","Duplicate deliveries x2 and x10","Crash after provider success before ledger completion",
                        "Concurrent duplicate messages","Provider timeout with unknown outcome","DynamoDB timeout","Key expiry and replay horizon",
                        "Recovery and eventual completion","Unit/integration/regression/load/policy gates"),"NOT_RUN");
    }
}
