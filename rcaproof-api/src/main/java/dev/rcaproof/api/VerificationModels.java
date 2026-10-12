package dev.rcaproof.api;

import java.time.Instant;
import java.util.*;

public final class VerificationModels {
    private VerificationModels() {}
    public record Check(String name, boolean passed, Map<String,Object> measurements, String detail) {}
    public record Job(UUID id, UUID incidentId, String candidateId, String status, Instant createdAt,
                      Instant deadline, UUID leaseToken, int attempts, String artifactDigest, BuildProvenance.Binding binding) {}
    public record Report(String status, String suiteVersion, String candidateDigest, String organizationDigest,
                         String scope, List<Check> checks, List<String> certificationBlockers, String reason, BuildProvenance.Binding binding) {}
    public record View(UUID id, UUID incidentId, String candidateId, String status, int attempts,
                       Instant createdAt, Instant deadline, List<Check> checks, Report report) {}
}
