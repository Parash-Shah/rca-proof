package dev.rcaproof.causal;

import dev.rcaproof.experiment.Observation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.rcaproof.causal.CounterfactualEngine.ProofState.*;
import static dev.rcaproof.experiment.Observation.Outcome.FAILURE;
import static dev.rcaproof.experiment.Observation.Outcome.HEALTHY;
import static org.junit.jupiter.api.Assertions.*;

class CounterfactualEngineTest {
    private final CounterfactualEngine engine = new CounterfactualEngine();
    private Observation result(boolean failed) { return new Observation(failed ? FAILURE : HEALTHY, "synthetic truth table"); }

    @Test void findsInteractionWithoutBlamingUnnecessaryTrigger() {
        var report = engine.investigate(List.of("crash", "redelivery", "missing-idempotency"), 10, "unit truth table",
                (f, n) -> result(f.containsAll(Set.of("redelivery", "missing-idempotency"))));
        assertEquals(PROVEN, report.state());
        assertEquals(List.of(Set.of("redelivery", "missing-idempotency")), report.minimalCausalSets());
        assertEquals(80, report.trials().size());
    }
    @Test void refusesProofForNoisyEvidence() {
        var report = engine.investigate(List.of("A"), 10, "noisy sandbox", (f, n) -> result(!f.isEmpty() && n % 2 == 0));
        assertEquals(INCONCLUSIVE, report.state());
    }
    @Test void refusesProofWhenNegativeControlFails() {
        assertEquals(INCONCLUSIVE, engine.investigate(List.of("A"), 2, "broken control", (f, n) -> result(true)).state());
    }
    @Test void refusesProofOnInfrastructureFailure() {
        assertEquals(INCONCLUSIVE, engine.investigate(List.of("A"), 2, "broken runner", (f, n) -> { throw new IllegalStateException(); }).state());
    }
    @Test void doesNotAssumeMonotonicity() {
        var report = engine.investigate(List.of("A", "B", "C"), 2, "nonmonotonic truth table",
                (f, n) -> result(f.size() == 1 || f.size() == 3));
        assertEquals(PROVEN, report.state());
        assertEquals(3, report.minimalCausalSets().size());
        assertTrue(report.minimalCausalSets().stream().allMatch(s -> s.size() == 1));
    }
    @Test void healthyRunsDoNotManufactureCause() {
        assertEquals(INCONCLUSIVE, engine.investigate(List.of("A"), 2, "unreproduced", (f, n) -> result(false)).state());
    }
    @Test void boundsSearchAndRejectsDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> engine.investigate(List.of("A", "A"), 2, "test", (f,n) -> result(false)));
        assertThrows(IllegalArgumentException.class, () -> engine.investigate(List.of("A", "B", "C", "D"), 2, "test", (f,n) -> result(false)));
    }
    @Test void missingEffectsAreNotSuccessfulRemediation() {
        assertEquals(INCONCLUSIVE.name(), Observation.duplicatePayment(1, 0).outcome().name());
        assertEquals(FAILURE, Observation.duplicatePayment(1, 2).outcome());
    }
}
