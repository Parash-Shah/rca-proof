package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.rcaproof.api.CertificationGate.*;

class CertificationGateTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    static String hex(char c,int count) { return String.valueOf(c).repeat(count); }
    static BuildProvenance.Binding binding() {
        return new BuildProvenance.Binding(new BuildProvenance.Source(hex('a',40),hex('b',40),hex('c',40),hex('d',64)),
                hex('e',64),Map.of("test-dependency.jar",hex('f',64)),Map.of("contract","test fixture"),null);
    }
    static VerificationModels.View run(UUID id,String candidate,String organization) {
        var checks=VerificationGate.REQUIRED.stream().sorted().map(name->new VerificationModels.Check(name,true,Map.of("fixture",true),"Gate fixture; not experimental evidence")).toList();
        var report=new VerificationModels.Report("VERIFIED_IN_SANDBOX",VerificationGate.VERSION,candidate,organization,
                "TEST_FIXTURE_ONLY",checks,List.of(),"fixture",binding());
        return new VerificationModels.View(id,UUID.randomUUID(),"IDEMPOTENCY_LEDGER","VERIFIED_IN_SANDBOX",1,NOW,NOW.plusSeconds(900),checks,report);
    }
    static Attestation evidence(VerificationModels.View run,Instant time) {
        return new Attestation(binding().source(),binding().applicationSha256(),run.report().candidateDigest(),run.report().organizationDigest(),
                run.report().suiteVersion(),hex('f',64),50,0,0,0,new ArrayList<>(REQUIRED_SUITES),"Parash-Shah/rca-proof",".github/workflows/ci.yml","fixture-run",time,Map.of("runner","unit-test"),run.candidateId(),signature(run.candidateId()));
    }
    static KeyPair key() throws Exception { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
    static String publicKey(KeyPair key) { return Base64.getEncoder().encodeToString(key.getPublic().getEncoded()); }
    static Envelope sign(Attestation evidence,KeyPair key) throws Exception {
        String payload=JSON.writeValueAsString(evidence);
        var signature=Signature.getInstance("Ed25519"); signature.initSign(key.getPrivate()); signature.update(payload.getBytes(StandardCharsets.UTF_8));
        return new Envelope(payload,Base64.getEncoder().encodeToString(signature.sign()));
    }
    final CertificationGate gate=new CertificationGate(JSON);
    final VerificationModels.View run=run(UUID.randomUUID(),hex('1',64),hex('2',64));
    Result evaluate(Envelope evidence,String key,Instant now) {
        return gate.evaluate(run,evidence,binding(),run.report().candidateDigest(),run.report().organizationDigest(),key,"Parash-Shah/rca-proof",now);
    }
    @Test void exactTrustedEvidenceCanCertify() throws Exception {
        var key=key(); assertEquals("CERTIFIED",evaluate(sign(evidence(run,NOW),key),publicKey(key),NOW).status());
    }
    @Test void retryContractCertifiesOnlyItsOwnSignedCompleteEvidence() throws Exception {
        var required=new HashSet<>(VerificationGate.RETRY_REQUIRED); required.addAll(HttpTransportSandbox.REQUIRED);
        var checks=required.stream().sorted().map(n->new VerificationModels.Check(n,true,Map.of(),"gate fixture")).toList();
        var report=new VerificationModels.Report("VERIFIED_IN_SANDBOX",HttpTransportSandbox.VERSION,run.report().candidateDigest(),run.report().organizationDigest(),
                HttpTransportSandbox.SCOPE,checks,List.of(),"fixture",binding());
        var retry=new VerificationModels.View(UUID.randomUUID(),UUID.randomUUID(),"BOUNDED_RETRY","VERIFIED_IN_SANDBOX",1,NOW,NOW,checks,report);
        var key=key(); var signed=sign(evidence(retry,NOW),key);
        assertEquals("CERTIFIED",gate.evaluate(retry,signed,binding(),report.candidateDigest(),report.organizationDigest(),publicKey(key),"Parash-Shah/rca-proof",NOW).status());
        // Deliberately identical digests: scenario/suite identity still prevents cross-contract reuse.
        assertEquals("NOT_CERTIFIED",evaluate(signed,publicKey(key),NOW).status());
        assertEquals("NOT_CERTIFIED",gate.evaluate(retry,sign(evidence(run,NOW),key),binding(),report.candidateDigest(),report.organizationDigest(),publicKey(key),"Parash-Shah/rca-proof",NOW).status());
        var incomplete=new VerificationModels.View(retry.id(),retry.incidentId(),retry.candidateId(),retry.status(),1,NOW,NOW,checks.subList(1,checks.size()),report);
        assertEquals("NOT_CERTIFIED",gate.evaluate(incomplete,signed,binding(),report.candidateDigest(),report.organizationDigest(),publicKey(key),"Parash-Shah/rca-proof",NOW).status());
        assertEquals("NOT_CERTIFIED",gate.evaluate(retry,sign(evidence(retry,NOW.minus(Duration.ofDays(8))),key),binding(),report.candidateDigest(),report.organizationDigest(),publicKey(key),"Parash-Shah/rca-proof",NOW).status());
    }
    @Test void tamperedPayloadAndUntrustedKeyCannotCertify() throws Exception {
        var key=key(); var envelope=sign(evidence(run,NOW),key);
        assertEquals("NOT_CERTIFIED",evaluate(new Envelope(envelope.payload()+" ",envelope.signature()),publicKey(key),NOW).status());
        assertEquals("NOT_CERTIFIED",evaluate(envelope,publicKey(key()),NOW).status());
    }
    @Test void expiredAndFutureEvidenceCannotCertify() throws Exception {
        var key=key();
        for (Instant time:List.of(NOW.minus(Duration.ofDays(7)),NOW.plusSeconds(301)))
            assertEquals("NOT_CERTIFIED",evaluate(sign(evidence(run,time),key),publicKey(key),NOW).status());
    }
    @Test void sourcePatchArchiveAndRegressionChangesAreRejected() throws Exception {
        var key=key();
        var original=evidence(run,NOW);
        for (String field:List.of("applicationSha256","candidateDigest","organizationDigest","suiteVersion","candidateId","failureSignature","repository","workflow","failures","errors","skipped","testSuites","source")) {
            var tree=JSON.valueToTree(original);
            var object=(com.fasterxml.jackson.databind.node.ObjectNode)tree;
            if (Set.of("failures","errors","skipped").contains(field)) object.put(field,1);
            else if (field.equals("testSuites")) object.putArray(field);
            else if (field.equals("source")) ((com.fasterxml.jackson.databind.node.ObjectNode)object.get(field)).put("patchSha256",hex('0',64));
            else object.put(field,"mismatch");
            var changed=JSON.treeToValue(tree,Attestation.class);
            assertEquals("NOT_CERTIFIED",evaluate(sign(changed,key),publicKey(key),NOW).status(),field);
        }
    }
    @Test void currentBuildAndPolicyChangesInvalidateEvidence() throws Exception {
        var key=key(); var envelope=sign(evidence(run,NOW),key);
        var changed=new BuildProvenance.Binding(binding().source(),binding().applicationSha256(),binding().dependencies(),Map.of("os","changed"),null);
        assertEquals("NOT_CERTIFIED",gate.evaluate(run,envelope,changed,run.report().candidateDigest(),run.report().organizationDigest(),publicKey(key),"Parash-Shah/rca-proof",NOW).status());
        assertEquals("NOT_CERTIFIED",gate.evaluate(run,envelope,binding(),run.report().candidateDigest(),hex('3',64),publicKey(key),"Parash-Shah/rca-proof",NOW).status());
    }
    @Test void missingCoverageOrFailedInvariantCannotCertify() throws Exception {
        var key=key(); var envelope=sign(evidence(run,NOW),key);
        var incomplete=new VerificationModels.View(run.id(),run.incidentId(),run.candidateId(),run.status(),1,NOW,NOW,List.of(),run.report());
        assertEquals("NOT_CERTIFIED",gate.evaluate(incomplete,envelope,binding(),run.report().candidateDigest(),run.report().organizationDigest(),publicKey(key),"Parash-Shah/rca-proof",NOW).status());
    }
    @Test void absentSourceAndAuthorityFailClosed() throws Exception {
        var key=key(); var envelope=sign(evidence(run,NOW),key);
        assertEquals("NOT_CERTIFIED",evaluate(null,publicKey(key),NOW).status());
        assertEquals("NOT_CERTIFIED",evaluate(envelope,"",NOW).status());
        assertEquals("NOT_CERTIFIED",gate.evaluate(run,envelope,null,run.report().candidateDigest(),run.report().organizationDigest(),publicKey(key),"Parash-Shah/rca-proof",NOW).status());
    }
}
