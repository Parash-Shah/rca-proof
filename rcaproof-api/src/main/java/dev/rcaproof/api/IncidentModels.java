package dev.rcaproof.api;

import dev.rcaproof.demo.contract.Order;
import dev.rcaproof.causal.CounterfactualEngine;
import dev.rcaproof.experiment.Observation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class IncidentModels {
    private IncidentModels() {}
    public enum Status { DETECTED, EVIDENCE_COLLECTED, REPRODUCED, INVESTIGATING, PROVEN, INCONCLUSIVE }
    public enum EventType { ORDER_CREATED, PAYMENT_SUCCEEDED, WORKER_CRASHED, MESSAGE_RECEIVED, MESSAGE_ACKNOWLEDGED, TELEMETRY_GAP, ACCESS_DENIED, REQUEST_TIMEOUT, LAB_OBSERVATION, HTTP_DEPENDENCY_ATTEMPT }
    public record CreateIncident(@NotBlank @Size(max=200) String title,
            @NotNull Instant startedAt,
            @NotNull @Pattern(regexp="DUPLICATE_PAYMENT|ACCESS_DENIED|INTERMITTENT_TIMEOUT|RETRY_STORM|POISON_MESSAGE|TIMEOUT_PROPAGATION|DEPENDENCY_OUTAGE|INVALID_REQUEST|BAD_CONFIGURATION|CAPACITY_FAILURE") String failureSignature,
            @Valid Order order) {
        @AssertTrue(message="Duplicate-payment incidents require an order")
        public boolean isOrderValid() { return !"DUPLICATE_PAYMENT".equals(failureSignature) || order != null; }
    }
    public record Evidence(@NotNull UUID eventId, @NotNull Instant timestamp,
            @NotBlank @Size(max=100) String service, @NotNull EventType eventType,
            @Size(max=200) String orderId,
            @Size(max=200) String traceId, @Size(max=200) String spanId,
            @Size(max=200) String requestId, @Size(max=200) String messageId,
            @Size(max=200) String resource, @Size(max=100) String operation,
            @Size(max=100) String result,
            @NotNull @Size(max=32) Map<@Size(max=80) String, @NotNull @Size(max=1000) String> metadata) {
        public Evidence { if (metadata != null) metadata = Map.copyOf(metadata); }
    }
    public record EvidenceBatch(@NotEmpty @Size(max=100) List<@NotNull @Valid Evidence> events) {}
    public record Investigate(@Min(2) @Max(20) int repetitions) {}
    public record Incident(UUID id, String title, Instant startedAt, Instant createdAt,
                           String failureSignature, Order order, Status status) {}
    public record Transition(Status status, Instant changedAt) {}
    public record TimelineEvent(long sequence, Instant receivedAt, Evidence event) {}
    public record Timeline(UUID incidentId, List<TimelineEvent> events, List<String> warnings,
                           List<Transition> statusHistory) {}
    public record Job(UUID id, UUID incidentId, int repetitions, String status, int attempts,
                      UUID leaseToken, Instant leaseUntil, Instant deadline, String scenarioVersion, String reason) {}
    public record JobView(UUID id, UUID incidentId, int repetitions, String status, int attempts,
                          Instant deadline, String scenarioVersion, String reason, List<Run> runs) {}
    public record Run(long id, int factorMask, int repetition, int attempt, String status,
                      Instant startedAt, Instant completedAt, Observation observation, Map<String, Object> measurements) {}
    public record Proof(UUID incidentId, UUID investigationId, Instant createdAt, String reason,
                        CounterfactualEngine.Report report) {}
}
