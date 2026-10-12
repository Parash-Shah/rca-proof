package dev.rcaproof.api;

import java.util.*;
import static dev.rcaproof.api.VerificationModels.*;

final class VerificationGate {
    static final String VERSION="payment-verification-v2";
    static final Set<String> REQUIRED=Set.of("BASELINE_0","BASELINE_1","REPLAY_0","REPLAY_1",
            "COUNTERFACTUAL_0","COUNTERFACTUAL_1","COUNTERFACTUAL_2","COUNTERFACTUAL_3",
            "DUPLICATES_10","CRASH_TWICE","PROVIDER_TIMEOUT","LEDGER_UNAVAILABLE","DELAYED_ACK","CONCURRENT_DUPLICATES","BOUNDED_LOAD",
            "PROVIDER_RESTART","KEY_EXPIRY","RETENTION_BOUNDARY","LEDGER_RETENTION","LEDGER_CONFLICT","MIGRATION_COMPATIBILITY","ROLLBACK_COMPATIBILITY");
    static String status(List<Check> checks) {
        return status("IDEMPOTENCY_LEDGER",checks);
    }
    static final Set<String> RETRY_REQUIRED=Set.of("RETRY_BASELINE_0","RETRY_BASELINE_1","RETRY_PERSISTENT_500","RETRY_HEALTHY","RETRY_TRANSIENT_500","RETRY_CLIENT_400","RETRY_REJECT_DISABLED","RETRY_REJECT_LONG_TIMEOUT","RETRY_CONCURRENT");
    static String version(String candidate) { return candidate.equals("BOUNDED_RETRY")?HttpTransportSandbox.VERSION:VERSION; }
    static String status(String candidate,List<Check> checks) {
        var required=new HashSet<>(candidate.equals("BOUNDED_RETRY")?RETRY_REQUIRED:REQUIRED);
        if(candidate.equals("BOUNDED_RETRY")) required.addAll(HttpTransportSandbox.REQUIRED);
        if (checks.stream().anyMatch(c->!c.passed())) return "FAILED";
        if (checks.size()!=required.size() || !checks.stream().map(Check::name).collect(java.util.stream.Collectors.toSet()).equals(required))
            return "INCONCLUSIVE";
        return "VERIFIED_IN_SANDBOX";
    }
    static List<String> blockers() {
        return List.of("Certification requires a committed build manifest and matching trusted CI attestation; evaluate the certificate endpoint",
                "Scope is the versioned local-lab contract, not production payment provider or infrastructure certification");
    }
}
