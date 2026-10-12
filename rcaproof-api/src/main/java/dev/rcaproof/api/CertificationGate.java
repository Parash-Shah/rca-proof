package dev.rcaproof.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;

/** Only an operator-trusted signing authority can attest independent CI results. */
final class CertificationGate {
    static String signature(String candidate) {
        return switch(candidate) { case "IDEMPOTENCY_LEDGER" -> "DUPLICATE_PAYMENT"; case "BOUNDED_RETRY" -> "RETRY_STORM"; default -> "UNSUPPORTED"; };
    }
    static final Set<String> REQUIRED_SUITES=Set.of("dev.rcaproof.causal.CounterfactualEngineTest",
            "dev.rcaproof.remediation.RemediationPlannerTest","dev.rcaproof.demo.order.OrderApplicationTest",
            "dev.rcaproof.demo.worker.PaymentCounterfactualIT","dev.rcaproof.demo.provider.ProviderApplicationTest",
            "dev.rcaproof.api.IncidentApiIT","dev.rcaproof.api.CertificationGateTest",
            "dev.rcaproof.experiment.ScenarioEvaluationTest","dev.rcaproof.api.GitHubReviewClientTest","dev.rcaproof.api.HttpRetrySandboxIT","dev.rcaproof.api.HttpTransportSandboxIT");
    record Envelope(@NotBlank @Size(max=200000) String payload,@NotBlank @Size(max=200) String signature) {}
    record Attestation(BuildProvenance.Source source,String applicationSha256,String candidateDigest,String organizationDigest,
                       String suiteVersion,String testReportsSha256,int tests,int failures,int errors,int skipped,
                       List<String> testSuites,String repository,String workflow,String runId,Instant issuedAt,Map<String,String> environment,
                       String candidateId,String failureSignature) {}
    record Result(String status,List<String> blockers,Attestation attestation) {}
    private final ObjectMapper json;
    CertificationGate(ObjectMapper json) {
        this.json=json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    Result evaluate(VerificationModels.View run,Envelope envelope,BuildProvenance.Binding current,
                    String currentCandidate,String currentOrganization,String publicKey,String repository,Instant now) {
        List<String> blockers=new ArrayList<>();
        if ("UNSUPPORTED".equals(signature(run.candidateId()))) blockers.add("Unsupported certification contract");
        var report=run.report();
        if (report==null) return new Result("NOT_CERTIFIED",List.of("Verification is not complete"),null);
        if (!"VERIFIED_IN_SANDBOX".equals(run.status()) || !"VERIFIED_IN_SANDBOX".equals(report.status())
                || !"VERIFIED_IN_SANDBOX".equals(VerificationGate.status(run.candidateId(),run.checks()))
                || !report.checks().equals(run.checks()) || !VerificationGate.version(run.candidateId()).equals(report.suiteVersion()))
            blockers.add("The current complete verification suite did not pass or differs from persisted checks");
        var binding=report.binding();
        if (binding==null || binding.problem()!=null || binding.source()==null || binding.dependencies().isEmpty()
                || !BuildProvenance.hex(binding.applicationSha256(),64)) blockers.add("Verified build has no complete committed source/patch and dependency binding");
        if (current==null || current.problem()!=null || !Objects.equals(binding,current)
                || !Objects.equals(report.candidateDigest(),currentCandidate) || !Objects.equals(report.organizationDigest(),currentOrganization))
            blockers.add("Build, runtime environment, candidate or organization differs from the verified subject");
        Attestation attestation=null;
        if (envelope==null) blockers.add("A signed independent CI attestation is required");
        else if (publicKey==null || publicKey.isBlank()) blockers.add("No trusted CI signing public key is configured");
        else {
            try {
                String encoded=publicKey.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
                PublicKey key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
                Signature verifier=Signature.getInstance("Ed25519"); verifier.initVerify(key);
                verifier.update(envelope.payload().getBytes(StandardCharsets.UTF_8));
                if (!verifier.verify(Base64.getDecoder().decode(envelope.signature()))) throw new IllegalArgumentException("Bad signature");
                attestation=json.readValue(envelope.payload(),Attestation.class);
                if (binding==null || !Objects.equals(attestation.source(),binding.source())
                        || !Objects.equals(attestation.applicationSha256(),binding.applicationSha256())
                        || !Objects.equals(attestation.candidateDigest(),report.candidateDigest())
                        || !Objects.equals(attestation.organizationDigest(),report.organizationDigest())
                        || !Objects.equals(attestation.suiteVersion(),report.suiteVersion())
                        || !Objects.equals(attestation.candidateId(),run.candidateId())
                        || !Objects.equals(attestation.failureSignature(),signature(run.candidateId()))) blockers.add("CI attestation belongs to a different source, patch, archive, candidate, scenario, policy or suite");
                if (attestation.issuedAt()==null || attestation.issuedAt().isAfter(now.plusSeconds(300))
                        || !attestation.issuedAt().plus(Duration.ofDays(7)).isAfter(now)) blockers.add("CI attestation is stale or future-dated");
                if (!repository.equals(attestation.repository()) || !Set.of(".github/workflows/ci.yml",".github/workflows/certify.yml").contains(attestation.workflow())
                        || attestation.runId()==null || attestation.runId().isBlank()) blockers.add("CI repository/workflow/run identity is not trusted");
                if (attestation.tests()<REQUIRED_SUITES.size() || attestation.failures()!=0 || attestation.errors()!=0 || attestation.skipped()!=0
                        || attestation.testSuites()==null || !new HashSet<>(attestation.testSuites()).containsAll(REQUIRED_SUITES)
                        || !BuildProvenance.hex(attestation.testReportsSha256(),64) || attestation.environment()==null || attestation.environment().isEmpty())
                    blockers.add("CI regression evidence is incomplete, failing or skipped");
            } catch (Exception e) { blockers.add("CI signature or signed payload is invalid"); }
        }
        return new Result(blockers.isEmpty()?"CERTIFIED":"NOT_CERTIFIED",List.copyOf(blockers),attestation);
    }
}
