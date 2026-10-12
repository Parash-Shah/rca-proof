package dev.rcaproof.api;

import java.util.List;
import static dev.rcaproof.api.IncidentModels.*;

public final class EvidenceAssessment {
    private EvidenceAssessment() {}
    public static String missing(List<TimelineEvent> evidence) {
        if (evidence.stream().anyMatch(e -> e.event().eventType()==EventType.TELEMETRY_GAP))
            return "Declared telemetry gaps prevent linking this incident to the sandbox result";
        long orders=evidence.stream().filter(e -> e.event().eventType()==EventType.ORDER_CREATED).count();
        long effects=evidence.stream().map(TimelineEvent::event)
                .filter(e -> e.eventType()==EventType.PAYMENT_SUCCEEDED && "SUCCESS".equals(e.result()))
                .map(e -> e.metadata().get("chargeId")).filter(id -> id!=null && !id.isBlank()).distinct().count();
        if (orders!=1 || effects<2) return "Need one ORDER_CREATED event and at least two distinct successful provider charge IDs for this order";
        var events=evidence.stream().map(TimelineEvent::event).toList();
        boolean correlated=events.stream().filter(e -> e.eventType()==EventType.WORKER_CRASHED && e.messageId()!=null && !e.messageId().isBlank())
                .anyMatch(crash -> events.stream().filter(e -> e.eventType()==EventType.MESSAGE_RECEIVED && crash.messageId().equals(e.messageId())).count()>=2
                        && events.stream().filter(e -> e.eventType()==EventType.PAYMENT_SUCCEEDED && "SUCCESS".equals(e.result()) && crash.messageId().equals(e.messageId()))
                        .map(e -> e.metadata().get("chargeId")).filter(id -> id!=null && !id.isBlank()).distinct().count()>=2);
        if (!correlated) return "Need repeated message receipt, a worker crash, and distinct successful charges correlated to the same message ID; other causes remain possible";
        return null;
    }
}
