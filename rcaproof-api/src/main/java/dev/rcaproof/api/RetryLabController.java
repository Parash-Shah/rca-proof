package dev.rcaproof.api;

import dev.rcaproof.experiment.RetryContract;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static dev.rcaproof.api.IncidentModels.*;

@RestController
public class RetryLabController {
    private final HttpRetrySandbox sandbox;
    private final IncidentStore incidents;
    public RetryLabController(HttpRetrySandbox sandbox,IncidentStore incidents) { this.sandbox=sandbox; this.incidents=incidents; }
    @PostMapping("/v1/labs/retry-storm") @ResponseStatus(HttpStatus.CREATED)
    public Incident reproduce() {
        try {
            Instant started=Instant.now();
            var sample=sandbox.sample("HTTP_500",6,"A",Duration.ofSeconds(3),()->{});
            if (!sample.correlated() || sample.providerRequests()!=6 || sample.statuses().stream().anyMatch(s->s!=500))
                throw new IllegalStateException("Fault reproduction or provider correlation failed");
            var incident=incidents.create(new CreateIncident("Measured HTTP retry amplification in the local WireMock lab",started,"RETRY_STORM",null));
            incidents.addEvidence(incident.id(),evidence(sample,Instant.now()));
            return incidents.incident(incident.id());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"HTTP lab interrupted"); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"HTTP lab could not reproduce and persist correlated evidence; check the local WireMock service"); }
    }
    static List<Evidence> evidence(HttpRetrySandbox.Sample sample,Instant observedAt) {
        List<Evidence> events=new ArrayList<>();
        for (int i=0;i<sample.statuses().size();i++) events.add(new Evidence(UUID.randomUUID(),observedAt,"retry-http-client",EventType.HTTP_DEPENDENCY_ATTEMPT,
                null,null,null,sample.requestId(),null,"wiremock:/rcaproof/"+sample.requestId(),"GET",Integer.toString(sample.statuses().get(i)),
                Map.of("scenarioVersion",RetryContract.VERSION,"attempt",Integer.toString(i+1),"providerRequests",Integer.toString(sample.providerRequests()),"correlated",Boolean.toString(sample.correlated()),"timestampMeaning","sample observation time, not individual request start")));
        return List.copyOf(events);
    }
}
