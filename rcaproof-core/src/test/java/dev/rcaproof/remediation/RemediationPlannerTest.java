package dev.rcaproof.remediation;

import dev.rcaproof.causal.CounterfactualEngine;
import dev.rcaproof.classification.FaultClassifier;
import dev.rcaproof.experiment.Observation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.rcaproof.remediation.RemediationPlanner.*;

class RemediationPlannerTest {
    final RemediationPlanner planner=new RemediationPlanner();
    final FaultClassifier classifier=new FaultClassifier();
    final FaultClassifier.Decision defect=new FaultClassifier.Decision(FaultClassifier.Category.ARCHITECTURE_DEFECT,
            FaultClassifier.Action.PLAN_REMEDIATION,"proven","test");
    Organization org(Set<String> databases,boolean review,boolean approval,boolean merge) {
        return new Organization("synthetic",Set.of("JAVA"),Set.of("SQS_STANDARD"),databases,review,true,approval,merge,false);
    }
    @Test void policyAndCausalCoverageOverrideRanking() {
        var plan=planner.plan(defect,org(Set.of("DYNAMODB"),true,true,false));
        assertEquals("IDEMPOTENCY_LEDGER",plan.recommendedCandidate());
        assertEquals(2,plan.candidates().stream().filter(c->c.eligibility()==Eligibility.REJECTED).count());
        assertTrue(plan.candidates().stream().allMatch(c->c.verificationStatus().equals("NOT_RUN")));
        var ledger=plan.candidates().getFirst();
        assertTrue(ledger.assumptions().stream().anyMatch(s->s.contains("Provider honors")));
        assertEquals(ledger.score(),ledger.scoreBreakdown().values().stream().mapToInt(Integer::intValue).sum());
    }
    @Test void newDatabaseRequiresArchitectureReviewAndCannotBeRecommended() {
        var plan=planner.plan(defect,org(Set.of("POSTGRESQL"),true,true,false));
        assertNull(plan.recommendedCandidate());
        assertEquals(Eligibility.REQUIRES_ARCHITECTURE_REVIEW,plan.candidates().getFirst().eligibility());
    }
    @Test void unsafeOrganizationCannotAuthorizeAutomaticChanges() {
        var plan=planner.plan(defect,org(Set.of("DYNAMODB"),true,false,true));
        assertNull(plan.recommendedCandidate());
        assertTrue(plan.candidates().stream().allMatch(c->c.eligibility()==Eligibility.REJECTED));
    }
    @Test void failingControlsCannotCreateDefectRecommendations() {
        var report=new CounterfactualEngine().investigate(List.of("MESSAGE_REDELIVERY","MISSING_IDEMPOTENCY"),2,"test",
                (f,r)->new Observation(Observation.Outcome.FAILURE,"bad negative control"));
        var decision=classifier.classify("DUPLICATE_PAYMENT",report);
        assertEquals(FaultClassifier.Category.UNKNOWN,decision.category());
        assertTrue(planner.plan(decision,org(Set.of("DYNAMODB"),true,true,false)).candidates().isEmpty());
    }
    @Test void wrongCausalSetCannotBecomeArchitectureDefect() {
        var report=new CounterfactualEngine().investigate(List.of("WORKER_CRASH"),2,"test",
                (f,r)->new Observation(f.isEmpty()?Observation.Outcome.HEALTHY:Observation.Outcome.FAILURE,"test"));
        assertEquals(FaultClassifier.Category.UNKNOWN,classifier.classify("DUPLICATE_PAYMENT",report).category());
    }
}
