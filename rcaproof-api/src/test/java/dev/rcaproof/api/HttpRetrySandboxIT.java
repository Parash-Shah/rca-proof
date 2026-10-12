package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rcaproof.causal.CounterfactualEngine;
import dev.rcaproof.experiment.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class HttpRetrySandboxIT {
    @Container static final GenericContainer<?> wiremock=new GenericContainer<>("wiremock/wiremock:3.13.1")
            .withExposedPorts(8080).waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));
    HttpRetrySandbox sandbox() { return new HttpRetrySandbox(new ObjectMapper(),"http://"+wiremock.getHost()+":"+wiremock.getMappedPort(8080)); }
    @Test void realHttpCounterfactualsIdentifyInteractionAndRejectDeploymentHypothesis() throws Exception {
        try (var session=sandbox().open(null,null)) {
            var report=new CounterfactualEngine().investigate(RetryContract.FACTORS,2,RetryContract.SCOPE,(f,r)->session.run(f,r).observation());
            assertEquals(CounterfactualEngine.ProofState.PROVEN,report.state());
            assertEquals(List.of(Set.of("DEPENDENCY_HTTP_500","EXCESSIVE_RETRIES")),report.minimalCausalSets());
            assertEquals(16,report.trials().size());
        }
    }
    @Test void fixSuitePreservesRecoveryAndRejectsIncompleteAlternatives() throws Exception {
        var checks=new ArrayList<VerificationModels.Check>(); sandbox().verify(checks::add,()->{});
        assertEquals("VERIFIED_IN_SANDBOX",VerificationGate.status("BOUNDED_RETRY",checks),checks.toString());
        assertEquals(16,checks.size());
        assertTrue(checks.stream().allMatch(VerificationModels.Check::passed));
        assertEquals("INCONCLUSIVE",VerificationGate.status(checks),"Retry checks must never satisfy payment certification coverage");
        assertEquals("INCONCLUSIVE",VerificationGate.status("BOUNDED_RETRY",checks.subList(1,checks.size())));
    }
    @Test void independentSamplesDoNotResetEachOthersMappingsOrJournal() throws Exception {
        var lab=sandbox();
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->lab.sample("TRANSIENT_500",2,"A",Duration.ofSeconds(3),()->{}));
            var b=executor.submit(()->lab.sample("HTTP_500",6,"B",Duration.ofSeconds(3),()->{}));
            assertEquals(List.of(500,200),a.get().statuses()); assertTrue(a.get().correlated());
            assertEquals(6,b.get().providerRequests()); assertTrue(b.get().correlated());
        }
    }
    @Test void arbitraryRemoteFaultTargetsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new HttpRetrySandbox(new ObjectMapper(),"https://example.com"));
        assertThrows(IllegalArgumentException.class,()->new HttpRetrySandbox(new ObjectMapper(),"http://localhost:8080/proxy"));
    }
}
