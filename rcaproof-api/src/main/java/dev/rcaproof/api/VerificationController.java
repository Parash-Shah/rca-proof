package dev.rcaproof.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.*;

@RestController
public class VerificationController {
    private final VerificationStore store;
    private final CertificationService certificates;
    public VerificationController(VerificationStore store,CertificationService certificates) { this.store=store; this.certificates=certificates; }
    @PostMapping("/v1/incidents/{incidentId}/remediations/{candidateId}/verify")
    public ResponseEntity<VerificationModels.View> verify(@PathVariable UUID incidentId,@PathVariable String candidateId) {
        var run=store.enqueue(incidentId,candidateId);
        return ResponseEntity.accepted().location(URI.create("/v1/verifications/"+run.id())).body(run);
    }
    @GetMapping("/v1/verifications/{id}") public VerificationModels.View view(@PathVariable UUID id) { return store.view(id); }
    @GetMapping("/v1/verifications/{id}/certificate") public CertificationService.Certificate certificate(@PathVariable UUID id) { return certificates.certificate(id); }
    @PostMapping("/v1/verifications/{id}/certificate") public CertificationService.Certificate certify(@PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody CertificationGate.Envelope evidence) { return certificates.issue(id,evidence); }
}
