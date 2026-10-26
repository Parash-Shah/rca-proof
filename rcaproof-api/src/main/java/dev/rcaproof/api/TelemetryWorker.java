package dev.rcaproof.api;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name={"rcaproof.telemetry-enabled","rcaproof.worker-enabled"},havingValue="true")
public class TelemetryWorker {
    private final TelemetryStore store;
    public TelemetryWorker(TelemetryStore store) { this.store=store; }
    @Scheduled(fixedDelayString="${rcaproof.telemetry-poll-ms:1000}") public void poll() {
        for (String key:store.pending()) {
            try { store.materialize(key); }
            catch (RuntimeException e) { LoggerFactory.getLogger(getClass()).warn("Telemetry investigation could not be queued for {}",key,e); }
        }
    }
}
