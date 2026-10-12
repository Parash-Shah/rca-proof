package dev.rcaproof.causal;

import dev.rcaproof.experiment.ExperimentRunner;
import dev.rcaproof.experiment.Observation;
import java.util.*;
import static dev.rcaproof.experiment.Observation.Outcome.*;

/** Exhaustive, bounded intervention search. Proof is scoped to the supplied sandbox and invariant. */
public final class CounterfactualEngine {
    public enum ProofState { PROVEN, SUPPORTED, UNSUPPORTED, REFUTED, INCONCLUSIVE }
    public record Trial(Set<String> factors, int repetition, Observation observation) {
        public Trial { factors = Set.copyOf(factors); Objects.requireNonNull(observation); }
    }
    public record Report(ProofState state, List<Set<String>> minimalCausalSets,
                         List<Trial> trials, int repetitions, String scope) {
        public Report {
            minimalCausalSets = minimalCausalSets.stream().map(Set::copyOf).toList();
            trials = List.copyOf(trials);
        }
    }

    public Report investigate(List<String> factors, int repetitions, String scope, ExperimentRunner runner) {
        Objects.requireNonNull(scope); Objects.requireNonNull(runner);
        if (scope.isBlank()) throw new IllegalArgumentException("A concrete sandbox scope is required");
        if (factors.isEmpty() || factors.size() > 3 || new HashSet<>(factors).size() != factors.size()
                || factors.stream().anyMatch(f -> f == null || f.isBlank())) {
            throw new IllegalArgumentException("V1 requires 1-3 distinct named factors");
        }
        if (repetitions < 2 || repetitions > 100) throw new IllegalArgumentException("Repetitions must be 2-100");
        List<Trial> trials = new ArrayList<>();
        Map<Set<String>, Observation.Outcome> outcomes = new LinkedHashMap<>();
        // Full baseline first; then the negative control and every intervention.
        List<Integer> masks = new ArrayList<>();
        int full = (1 << factors.size()) - 1;
        masks.add(full);
        for (int mask = 0; mask < full; mask++) masks.add(mask);
        for (int mask : masks) {
            Set<String> enabled = new TreeSet<>();
            for (int bit = 0; bit < factors.size(); bit++) if ((mask & (1 << bit)) != 0) enabled.add(factors.get(bit));
            Set<String> immutable = Set.copyOf(enabled);
            Set<Observation.Outcome> seen = new HashSet<>();
            for (int run = 0; run < repetitions; run++) {
                Observation observation;
                try { observation = Objects.requireNonNull(runner.run(immutable, run)); }
                catch (dev.rcaproof.experiment.ExperimentAbortedException e) { throw e; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Investigation interrupted", e); }
                catch (Exception e) { observation = new Observation(INCONCLUSIVE, "Experiment failed: " + e.getClass().getSimpleName()); }
                trials.add(new Trial(immutable, run, observation));
                seen.add(observation.outcome());
            }
            outcomes.put(immutable, seen.size() == 1 ? seen.iterator().next() : INCONCLUSIVE);
        }
        List<Set<String>> minimal = new ArrayList<>();
        for (var entry : outcomes.entrySet()) {
            Set<String> candidate = entry.getKey();
            if (candidate.isEmpty() || entry.getValue() != FAILURE) continue;
            boolean allProperSubsetsHealthy = outcomes.entrySet().stream()
                    .filter(other -> !other.getKey().equals(candidate) && candidate.containsAll(other.getKey()))
                    .allMatch(other -> other.getValue() == HEALTHY);
            if (allProperSubsetsHealthy) minimal.add(candidate);
        }
        minimal.sort(Comparator.comparing(s -> String.join(",", new TreeSet<>(s))));
        boolean complete = !outcomes.containsValue(INCONCLUSIVE);
        boolean baselineReproduced = outcomes.get(Set.copyOf(factors)) == FAILURE;
        boolean negativeControlHealthy = outcomes.get(Set.of()) == HEALTHY;
        ProofState state = complete && baselineReproduced && negativeControlHealthy && !minimal.isEmpty()
                ? ProofState.PROVEN : ProofState.INCONCLUSIVE;
        return new Report(state, minimal, trials, repetitions, scope);
    }
}
