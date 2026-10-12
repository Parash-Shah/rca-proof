package dev.rcaproof.classification;

import dev.rcaproof.causal.CounterfactualEngine;
import java.util.*;

public final class FaultClassifier {
    public enum Category { APPLICATION_DEFECT, ARCHITECTURE_DEFECT, INFRASTRUCTURE_CONFIGURATION,
        CAPACITY_FAILURE, DEPENDENCY_FAILURE, SECURITY_POLICY, USER_CONFIGURATION, INVALID_REQUEST,
        EXPECTED_BEHAVIOR, TRANSIENT_FAILURE, UNKNOWN }
    public enum Action { PLAN_REMEDIATION, NO_CODE_CHANGE, COLLECT_MORE_EVIDENCE }
    public record Decision(Category category, Action action, String reason, String customerMessage) {}
    public Decision classify(String signature, CounterfactualEngine.Report report) {
        if (report.state()==CounterfactualEngine.ProofState.PROVEN) {
            if (signature.equals("RETRY_STORM") && report.scope().equals(dev.rcaproof.experiment.RetryContract.SCOPE)
                    && report.minimalCausalSets().equals(List.of(Set.of("DEPENDENCY_HTTP_500","EXCESSIVE_RETRIES"))))
                return new Decision(Category.APPLICATION_DEFECT,Action.PLAN_REMEDIATION,
                        "Persistent dependency HTTP 500 responses interact with excessive client retries to amplify one GET into six dependency calls",
                        "Repeated attempts amplify the dependency failure. Bound retries while retaining recovery from transient failures and reporting persistent unavailability.");
            if (report.scope().equals(dev.rcaproof.experiment.ScenarioLab.SCOPE) && dev.rcaproof.experiment.ScenarioLab.find(signature).isPresent()) {
                Category category=switch(signature) {
                    case "RETRY_STORM","POISON_MESSAGE","TIMEOUT_PROPAGATION" -> Category.APPLICATION_DEFECT;
                    case "DEPENDENCY_OUTAGE" -> Category.DEPENDENCY_FAILURE;
                    case "INVALID_REQUEST" -> Category.INVALID_REQUEST;
                    case "BAD_CONFIGURATION" -> Category.USER_CONFIGURATION;
                    case "CAPACITY_FAILURE" -> Category.CAPACITY_FAILURE;
                    case "ACCESS_DENIED" -> Category.EXPECTED_BEHAVIOR;
                    default -> Category.UNKNOWN;
                };
                boolean expected=category==Category.INVALID_REQUEST || category==Category.EXPECTED_BEHAVIOR || category==Category.USER_CONFIGURATION;
                return new Decision(category,expected?Action.NO_CODE_CHANGE:Action.COLLECT_MORE_EVIDENCE,
                        "Counterfactuals establish this outcome only in the declared behavioral model; production-compatible remediation is not implemented for this class",
                        expected?"The request or configuration does not satisfy the demonstrated requirements. Check the supplied values, access, and service configuration.":"The local model reproduced the reported failure. Collect matching service telemetry before proposing a production change.");
            }
            if (signature.equals("DUPLICATE_PAYMENT") && report.minimalCausalSets().equals(
                    List.of(Set.of("MESSAGE_REDELIVERY","MISSING_IDEMPOTENCY"))))
                return new Decision(Category.ARCHITECTURE_DEFECT,Action.PLAN_REMEDIATION,
                        "At-least-once delivery exposes a non-idempotent payment side effect; worker crash is the trigger",
                        "Payment processing can repeat a charge after message redelivery. A fix requires verification before review.");
            if (signature.equals("ACCESS_DENIED") && report.minimalCausalSets().equals(List.of(Set.of("MISSING_PERMISSION"))))
                return new Decision(Category.EXPECTED_BEHAVIOR,Action.NO_CODE_CHANGE,
                        "The supported synthetic policy denies without permission and allows with permission; deployment control does not alter the decision",
                        "The service is operating normally, but the identity used for this request does not have access to the requested resource. Verify that the caller has the required permission and that the resource is included in its access policy.");
        }
        return new Decision(Category.UNKNOWN,Action.COLLECT_MORE_EVIDENCE,
                "The completed investigation does not establish a supported fault classification",
                "There is insufficient evidence to establish the cause. Collect correlated request traces and dependency telemetry before making changes.");
    }
}
