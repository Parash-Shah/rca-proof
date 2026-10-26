package dev.rcaproof.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@ConditionalOnProperty(name="rcaproof.telemetry-enabled",havingValue="true")
public class TelemetryController {
    private final TelemetryStore store;
    private final HttpRetrySandbox sandbox;
    public TelemetryController(TelemetryStore store,HttpRetrySandbox sandbox) { this.store=store; this.sandbox=sandbox; }
    @PostMapping("/v1/telemetry/events") @ResponseStatus(HttpStatus.ACCEPTED)
    public TelemetryStore.View accept(@Valid @RequestBody TelemetryStore.Submission event) { return store.accept(event); }
    @GetMapping("/v1/telemetry/{kind}/{id}")
    public TelemetryStore.View view(@PathVariable String kind,@PathVariable String id) { return store.view(kind+":"+id); }
    public record Request(@NotNull @Pattern(regexp="HEALTHY|HTTP_500|TRANSIENT_500|HTTP_400") String mode,@Min(1) @Max(6) int attempts) {}
    @PostMapping("/v1/labs/http-requests") @ResponseStatus(HttpStatus.CREATED)
    public TelemetryStore.View request(@Valid @RequestBody Request request) throws Exception {
        var sample=sandbox.sample(request.mode(),request.attempts(),"A",Duration.ofSeconds(3),()->{});
        for (var event:RetryLabController.evidence(sample,Instant.now())) store.accept(new TelemetryStore.Submission(null,event));
        return store.view("retry:"+sample.requestId());
    }
}
