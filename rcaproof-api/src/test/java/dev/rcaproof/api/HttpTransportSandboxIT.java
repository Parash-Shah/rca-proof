package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rcaproof.experiment.RetryingHttpClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class HttpTransportSandboxIT {
    @Container static final GenericContainer<?> wiremock=new GenericContainer<>("wiremock/wiremock:3.13.1")
            .withExposedPorts(8080).waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));
    HttpTransportSandbox lab() { return new HttpTransportSandbox(new ObjectMapper(),"http://"+wiremock.getHost()+":"+wiremock.getMappedPort(8080)); }
    @Test void measuredTransportSuitePassesAndLegacyCoverageCannotPassV2() throws Exception {
        var checks=new ArrayList<VerificationModels.Check>(); lab().verify(checks::add,()->{});
        assertEquals(HttpTransportSandbox.REQUIRED,checks.stream().map(VerificationModels.Check::name).collect(java.util.stream.Collectors.toSet()));
        assertTrue(checks.stream().allMatch(VerificationModels.Check::passed),checks.toString());
        var legacy=VerificationGate.RETRY_REQUIRED.stream().map(n->new VerificationModels.Check(n,true,Map.of(),"old coverage")).toList();
        assertEquals("INCONCLUSIVE",VerificationGate.status("BOUNDED_RETRY",legacy));
        checks.addAll(legacy); assertEquals("VERIFIED_IN_SANDBOX",VerificationGate.status("BOUNDED_RETRY",checks));
        checks.set(0,new VerificationModels.Check(checks.getFirst().name(),false,Map.of(),"deadline regression"));
        assertEquals("FAILED",VerificationGate.status("BOUNDED_RETRY",checks));
    }
    @Test void expiredBudgetDoesNotAttemptToConnectAndInterruptionIsPreserved() throws Exception {
        try(var client=new RetryingHttpClient()) {
            var result=client.getWithin(URI.create("http://127.0.0.1:1"),"expired","A",2,Duration.ofSeconds(1),Duration.ZERO,true,()->{});
            assertEquals("DEADLINE_EXCEEDED",result.outcome()); assertTrue(result.attempts().isEmpty());
            assertThrows(IllegalArgumentException.class,()->client.getWithin(URI.create("http://127.0.0.1:1"),"invalid","A",0,Duration.ofSeconds(1),Duration.ofSeconds(1),true,()->{}));
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class,()->client.getWithin(URI.create("http://"+wiremock.getHost()+":"+wiremock.getMappedPort(8080)),"interrupted","A",2,Duration.ofSeconds(1),Duration.ofSeconds(1),true,()->{}));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }
    @Test void unavailableFaultControlCannotProducePassingEvidence() {
        var unavailable=new HttpTransportSandbox(new ObjectMapper(),"http://127.0.0.1:1");
        assertThrows(Exception.class,()->unavailable.sample("LATENCY",true,600,()->{}));
        assertThrows(IllegalArgumentException.class,()->lab().sample("UNKNOWN",true,600,()->{}));
    }
}
