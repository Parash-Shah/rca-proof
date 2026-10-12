package dev.rcaproof.experiment;

import java.util.Objects;

/** An observed invariant outcome, never a language-model judgment. */
public record Observation(Outcome outcome, String detail) {
    public enum Outcome { FAILURE, HEALTHY, INCONCLUSIVE }
    public Observation { Objects.requireNonNull(outcome); Objects.requireNonNull(detail); }
    public static Observation duplicatePayment(int orders, int effects) {
        if (orders != 1 || effects < 1) {
            return new Observation(Outcome.INCONCLUSIVE, "Expected one order and at least one charge; observed " + orders + "/" + effects);
        }
        return new Observation(effects > 1 ? Outcome.FAILURE : Outcome.HEALTHY,
                "orders=" + orders + ", successfulPaymentEffects=" + effects);
    }
}
