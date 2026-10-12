package dev.rcaproof.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.net.URI;
import java.util.*;
import static dev.rcaproof.api.IncidentModels.*;

@RestController
@RequestMapping("/v1/incidents")
public class IncidentController {
    private final IncidentStore store;
    private final DecisionService decisions;
    public IncidentController(IncidentStore store,DecisionService decisions) { this.store=store; this.decisions=decisions; }
    @PostMapping public ResponseEntity<Incident> create(@Valid @RequestBody CreateIncident request) {
        Incident incident=store.create(request);
        return ResponseEntity.created(URI.create("/v1/incidents/"+incident.id())).body(incident);
    }
    @GetMapping("/{id}") public Incident incident(@PathVariable UUID id) { return store.incident(id); }
    @PostMapping("/{id}/evidence") public Map<String,Integer> evidence(@PathVariable UUID id,@Valid @RequestBody EvidenceBatch request) {
        return Map.of("inserted",store.addEvidence(id,request.events()));
    }
    @GetMapping("/{id}/timeline") public Timeline timeline(@PathVariable UUID id) { return store.timeline(id); }
    @PostMapping({"/{id}/investigate","/{id}/experiments"}) public ResponseEntity<JobView> investigate(@PathVariable UUID id,@Valid @RequestBody Investigate request) {
        store.enqueue(id,request.repetitions());
        return ResponseEntity.accepted().location(URI.create("/v1/incidents/"+id+"/investigation")).body(store.investigation(id));
    }
    @GetMapping("/{id}/investigation") public JobView investigation(@PathVariable UUID id) { return store.investigation(id); }
    @GetMapping("/{id}/proof") public Proof proof(@PathVariable UUID id) { return store.proof(id); }
    @GetMapping("/{id}/classification") public DecisionService.Classification classification(@PathVariable UUID id) { return decisions.classification(id); }
    @PostMapping("/{id}/remediations") public DecisionService.Remediations remediations(@PathVariable UUID id) { return decisions.plan(id); }
    @GetMapping("/{id}/remediations") public DecisionService.Remediations plan(@PathVariable UUID id) { return decisions.existingPlan(id); }
}
