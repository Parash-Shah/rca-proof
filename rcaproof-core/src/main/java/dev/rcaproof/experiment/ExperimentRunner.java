package dev.rcaproof.experiment;

import java.util.Set;

@FunctionalInterface
public interface ExperimentRunner {
    /** Each invocation must provision isolated state and bound its observation window. */
    Observation run(Set<String> enabledFactors, int repetition) throws Exception;
}
