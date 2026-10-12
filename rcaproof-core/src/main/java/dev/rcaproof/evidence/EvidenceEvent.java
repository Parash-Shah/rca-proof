package dev.rcaproof.evidence;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record EvidenceEvent(Instant timestamp, String service, String eventType,
                            String traceId, String spanId, String requestId,
                            String messageId, String resource, String operation,
                            String result, Map<String, String> metadata) {
    public EvidenceEvent {
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(service);
        Objects.requireNonNull(eventType);
        metadata = Map.copyOf(metadata);
    }
}
