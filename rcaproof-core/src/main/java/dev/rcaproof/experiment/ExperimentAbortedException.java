package dev.rcaproof.experiment;

/** Infrastructure orchestration must stop; this is not a measured experimental outcome. */
public final class ExperimentAbortedException extends RuntimeException {
    public ExperimentAbortedException(String message) { super(message); }
}
