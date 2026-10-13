package dev.rcaproof.api;

import dev.rcaproof.causal.CounterfactualEngine;
import dev.rcaproof.demo.contract.Order;
import dev.rcaproof.experiment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.rcaproof.api.IncidentModels.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="rcaproof.worker-enabled=false")
@Timeout(value=4,unit=TimeUnit.MINUTES)
class IncidentApiIT {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final org.testcontainers.containers.GenericContainer<?> wiremock=new org.testcontainers.containers.GenericContainer<>("wiremock/wiremock:3.13.1")
            .withExposedPorts(8080).waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/__admin/mappings").forStatusCode(200));
    @Container static final LocalStackContainer aws=new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.7.0"))
            .withServices(LocalStackContainer.Service.SQS,LocalStackContainer.Service.DYNAMODB);
    static String sandboxEndpoint() { return "http://"+aws.getHost()+":"+aws.getMappedPort(4566); }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("rcaproof.sandbox-endpoint",IncidentApiIT::sandboxEndpoint);
        registry.add("rcaproof.http-sandbox-endpoint",()->"http://"+wiremock.getHost()+":"+wiremock.getMappedPort(8080));
    }
    @Autowired TestRestTemplate http;
    @Autowired IncidentStore store;
    @Autowired JdbcTemplate db;
    @Autowired DecisionService decisions;
    @Autowired VerificationStore verifications;
    @Autowired CertificationService certificates;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired DuplicatePaymentSandbox paymentSandbox;
    @Autowired HttpRetrySandbox retrySandbox;
    @BeforeEach void clean() { db.execute("TRUNCATE incidents CASCADE"); }
    Incident create() {
        var response=http.postForEntity("/v1/incidents",new CreateIncident("Synthetic duplicate payment",Instant.parse("2026-01-01T00:00:00Z"),
                "DUPLICATE_PAYMENT",new Order("order-"+UUID.randomUUID(),"customer",new BigDecimal("129.95"))),Incident.class);
        assertEquals(HttpStatus.CREATED,response.getStatusCode());
        assertNotNull(response.getHeaders().getLocation());
        assertEquals(Status.DETECTED,response.getBody().status());
        return response.getBody();
    }
    Evidence event(Incident incident,int second,EventType type,String chargeId) {
        return new Evidence(UUID.randomUUID(),incident.startedAt().plusSeconds(second),"payment-lab",type,incident.order().orderId(),
                "trace-1",null,"request-1","message-1",null,null,"SUCCESS",chargeId==null?Map.of():Map.of("chargeId",chargeId));
    }
    List<Evidence> completeEvidence(Incident incident) {
        return List.of(event(incident,0,EventType.ORDER_CREATED,null),event(incident,1,EventType.MESSAGE_RECEIVED,null),
                event(incident,2,EventType.PAYMENT_SUCCEEDED,"charge-1"),event(incident,3,EventType.WORKER_CRASHED,null),
                event(incident,4,EventType.MESSAGE_RECEIVED,null),event(incident,5,EventType.PAYMENT_SUCCEEDED,"charge-2"));
    }
    void upload(Incident incident,List<Evidence> events) {
        assertEquals(HttpStatus.OK,http.postForEntity("/v1/incidents/"+incident.id()+"/evidence",new EvidenceBatch(events),Map.class).getStatusCode());
    }
    JobView enqueue(Incident incident) {
        var response=http.postForEntity("/v1/incidents/"+incident.id()+"/investigate",new Investigate(2),JobView.class);
        assertEquals(HttpStatus.ACCEPTED,response.getStatusCode());
        return response.getBody();
    }
    ServletWebServerApplicationContext restart(boolean worker) {
        return (ServletWebServerApplicationContext)new SpringApplicationBuilder(ApiApplication.class).run(
                "--server.port=0","--spring.datasource.url="+postgres.getJdbcUrl(),
                "--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),
                "--rcaproof.sandbox-endpoint="+sandboxEndpoint(),"--rcaproof.worker-enabled="+worker,"--rcaproof.poll-ms=50");
    }
    @Test void queuedInvestigationRunsInBackgroundAndProofSurvivesRestart() throws Exception {
        Incident incident=create(); upload(incident,completeEvidence(incident));
        assertEquals("QUEUED",enqueue(incident).status());
        try (var worker=restart(true)) {
            Instant deadline=Instant.now().plusSeconds(150);
            while (!"COMPLETED".equals(store.investigation(incident.id()).status()) && Instant.now().isBefore(deadline)) Thread.sleep(100);
            assertEquals("COMPLETED",store.investigation(incident.id()).status());
        }
        try (var restarted=restart(false)) {
            var client=RestClient.create("http://localhost:"+restarted.getWebServer().getPort());
            Proof proof=client.get().uri("/v1/incidents/"+incident.id()+"/proof").retrieve().body(Proof.class);
            assertEquals(CounterfactualEngine.ProofState.PROVEN,proof.report().state(),proof.reason());
            assertEquals(16,proof.report().trials().size());
            assertEquals(List.of(Set.of("MESSAGE_REDELIVERY","MISSING_IDEMPOTENCY")),proof.report().minimalCausalSets());
            assertEquals(Status.PROVEN,client.get().uri("/v1/incidents/"+incident.id()).retrieve().body(Incident.class).status());
            assertEquals(List.of(Status.DETECTED,Status.EVIDENCE_COLLECTED,Status.REPRODUCED,Status.INVESTIGATING,Status.PROVEN),
                    store.timeline(incident.id()).statusHistory().stream().map(Transition::status).toList());
            assertTrue(store.investigation(incident.id()).runs().stream().allMatch(r -> r.measurements().containsKey("chargeIds")));
        }
    }
    @Test void timelineIsOrderedAndDuplicateEvidenceIsIdempotentAndConflictingBatchRollsBack() {
        Incident incident=create();
        List<Evidence> events=new ArrayList<>(completeEvidence(incident)); Collections.reverse(events);
        upload(incident,events); upload(incident,events);
        Timeline timeline=http.getForObject("/v1/incidents/"+incident.id()+"/timeline",Timeline.class);
        assertEquals(6,timeline.events().size());
        assertEquals(EventType.ORDER_CREATED,timeline.events().getFirst().event().eventType());
        Evidence original=events.getFirst();
        Evidence changed=new Evidence(original.eventId(),original.timestamp(),"changed",original.eventType(),original.orderId(),null,null,null,null,null,null,null,Map.of());
        assertEquals(HttpStatus.CONFLICT,http.postForEntity("/v1/incidents/"+incident.id()+"/evidence",
                new EvidenceBatch(List.of(event(incident,10,EventType.MESSAGE_ACKNOWLEDGED,null),changed)),String.class).getStatusCode());
        assertEquals(6,store.timeline(incident.id()).events().size());
        assertEquals(Status.EVIDENCE_COLLECTED,store.incident(incident.id()).status());
    }
    @Test void queueSubmissionIsIdempotentAndEvidenceFreezesAtomically() throws Exception {
        Incident incident=create(); upload(incident,completeEvidence(incident));
        try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<UUID>> calls=List.of(() -> store.enqueue(incident.id(),2).id(),() -> store.enqueue(incident.id(),2).id());
            var results=pool.invokeAll(calls);
            assertEquals(results.getFirst().get(),results.getLast().get());
        }
        assertEquals(HttpStatus.CONFLICT,http.postForEntity("/v1/incidents/"+incident.id()+"/evidence",new EvidenceBatch(completeEvidence(incident)),String.class).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,http.postForEntity("/v1/incidents/"+incident.id()+"/investigate",new Investigate(3),String.class).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,http.getForEntity("/v1/incidents/"+incident.id()+"/proof",String.class).getStatusCode());
    }
    private SandboxFactory truthTable(AtomicInteger calls,boolean failControl) {
        return (job,incident) -> new SandboxFactory.Session() {
            public SandboxFactory.Result run(Set<String> factors,int repetition) {
                calls.incrementAndGet();
                boolean failure=failControl || factors.containsAll(Set.of("MESSAGE_REDELIVERY","MISSING_IDEMPOTENCY"));
                return new SandboxFactory.Result(Observation.duplicatePayment(1,failure?2:1),Map.of("testDouble",true));
            }
            public void close() {}
        };
    }
    @Test void interruptedTrialIsAbandonedAndCompletedTrialsResumeWithFencedWrites() {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        Job old=store.claim().orElseThrow();
        assertTrue(store.claim().isEmpty(),"An active lease cannot be stolen");
        store.beginTrial(old,7,0);
        store.finishTrial(old,7,0,Observation.duplicatePayment(1,2),Map.of("checkpoint","before-restart"));
        store.beginTrial(old,7,1);
        db.update("UPDATE investigations SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",old.id());
        try (var restarted=restart(false)) {
            IncidentStore recovered=restarted.getBean(IncidentStore.class);
            Job current=recovered.claim().orElseThrow();
            assertEquals(2,current.attempts());
            assertFalse(store.heartbeat(old));
            assertThrows(ExperimentAbortedException.class,() -> store.finishTrial(old,7,1,Observation.duplicatePayment(1,1),Map.of()));
            AtomicInteger calls=new AtomicInteger();
            new InvestigationWorker(recovered,truthTable(calls,false)).execute(current);
            assertEquals(15,calls.get(),"The completed checkpoint must not be rerun");
            assertEquals(CounterfactualEngine.ProofState.PROVEN,recovered.proof(incident.id()).report().state());
            var runs=recovered.investigation(incident.id()).runs();
            assertEquals(17,runs.size());
            assertEquals(1,runs.stream().filter(r -> "ABANDONED".equals(r.status())).count());
            assertEquals("before-restart",runs.getFirst().measurements().get("checkpoint"));
        }
    }
    @Test void declaredGapProducesInconclusiveWithoutRunningSandbox() {
        Incident incident=create(); var events=new ArrayList<>(completeEvidence(incident));
        events.add(event(incident,2,EventType.TELEMETRY_GAP,null)); upload(incident,events); enqueue(incident);
        new InvestigationWorker(store,(job,i) -> { throw new AssertionError("Sandbox must not be opened"); }).poll();
        assertEquals(Status.INCONCLUSIVE,store.incident(incident.id()).status());
        assertTrue(store.proof(incident.id()).reason().contains("telemetry gaps"));
        assertTrue(store.investigation(incident.id()).runs().isEmpty());
    }
    @Test void gracefulInterruptionLeavesJobResumable() {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        Job old=store.claim().orElseThrow();
        SandboxFactory interrupted=(job,i) -> new SandboxFactory.Session() {
            public SandboxFactory.Result run(Set<String> factors,int repetition) throws Exception { throw new InterruptedException("Shutdown"); }
            public void close() {}
        };
        try { new InvestigationWorker(store,interrupted).execute(old); }
        finally { Thread.interrupted(); }
        assertEquals("RUNNING",store.investigation(incident.id()).status());
        assertEquals("RUNNING",store.investigation(incident.id()).runs().getFirst().status());
        db.update("UPDATE investigations SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",old.id());
        new InvestigationWorker(store,truthTable(new AtomicInteger(),false)).poll();
        assertEquals(Status.PROVEN,store.incident(incident.id()).status());
        assertEquals(1,store.investigation(incident.id()).runs().stream().filter(r -> "ABANDONED".equals(r.status())).count());
    }
    @Test void uncorrelatedDuplicateChargesCannotEstablishRedeliveryCause() {
        Incident incident=create();
        upload(incident,List.of(event(incident,0,EventType.ORDER_CREATED,null),event(incident,1,EventType.PAYMENT_SUCCEEDED,"one"),event(incident,2,EventType.PAYMENT_SUCCEEDED,"two")));
        enqueue(incident);
        new InvestigationWorker(store,(job,i) -> { throw new AssertionError("No correlated redelivery evidence"); }).poll();
        assertEquals(Status.INCONCLUSIVE,store.incident(incident.id()).status());
        assertTrue(store.proof(incident.id()).reason().contains("other causes remain possible"));
    }
    @Test void infrastructureErrorsPersistAndStopAtFailedBaseline() {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        SandboxFactory broken=(job,i) -> new SandboxFactory.Session() {
            public SandboxFactory.Result run(Set<String> factors,int repetition) throws Exception { throw new java.io.IOException("Unavailable"); }
            public void close() {}
        };
        new InvestigationWorker(store,broken).poll();
        assertEquals(Status.INCONCLUSIVE,store.incident(incident.id()).status());
        assertEquals(2,store.investigation(incident.id()).runs().size());
        assertTrue(store.investigation(incident.id()).runs().stream().allMatch(r -> r.observation().outcome()==Observation.Outcome.INCONCLUSIVE));
        assertEquals(CounterfactualEngine.ProofState.INCONCLUSIVE,store.proof(incident.id()).report().state());
    }
    @Test void failingNegativeControlCannotBecomeProven() {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        new InvestigationWorker(store,truthTable(new AtomicInteger(),true)).poll();
        assertEquals(Status.INCONCLUSIVE,store.incident(incident.id()).status());
        assertEquals(16,store.proof(incident.id()).report().trials().size());
    }
    @Test void exhaustedBudgetAndFabricatedIncompleteProofCannotBecomeProven() {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        Job job=store.claim().orElseThrow();
        var fabricated=new CounterfactualEngine.Report(CounterfactualEngine.ProofState.PROVEN,List.of(Set.of("MESSAGE_REDELIVERY")),List.of(),2,"fabricated");
        assertThrows(ExperimentAbortedException.class,() -> store.complete(job,fabricated,"untrusted"));
        assertEquals(Status.EVIDENCE_COLLECTED,store.incident(incident.id()).status());
        db.update("UPDATE investigations SET deadline=clock_timestamp()-interval '1 second',lease_until=clock_timestamp()-interval '1 second' WHERE id=?",job.id());
        new InvestigationWorker(store,(j,i) -> { throw new AssertionError("Budget exhausted"); }).poll();
        assertEquals(Status.INCONCLUSIVE,store.incident(incident.id()).status());
        assertTrue(store.proof(incident.id()).reason().contains("budget exhausted"));
    }
    @Test void invalidHttpRequestsAreRejected() {
        assertEquals(HttpStatus.NOT_FOUND,http.getForEntity("/v1/incidents/"+UUID.randomUUID(),String.class).getStatusCode());
        Incident incident=create();
        assertEquals(HttpStatus.CONFLICT,http.postForEntity("/v1/incidents/"+incident.id()+"/investigate",new Investigate(2),String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,http.postForEntity("/v1/incidents/"+incident.id()+"/investigate",new Investigate(100),String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,http.postForEntity("/v1/incidents/"+incident.id()+"/evidence",new EvidenceBatch(List.of()),String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,http.postForEntity("/v1/incidents",new CreateIncident("bad",Instant.now(),"DUPLICATE_PAYMENT",new Order("o","c",BigDecimal.ZERO)),String.class).getStatusCode());
    }
    @Test void fabricatedProofStateIsRejectedEvenWithACompleteDurableTrialSet() {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        Job job=store.claim().orElseThrow();
        var healthy=new CounterfactualEngine().investigate(DuplicatePaymentSandbox.FACTORS,2,DuplicatePaymentSandbox.SCOPE,
                (factors,repetition) -> Observation.duplicatePayment(1,1));
        for (var trial:healthy.trials()) {
            int mask=DuplicatePaymentSandbox.mask(trial.factors());
            store.beginTrial(job,mask,trial.repetition());
            store.finishTrial(job,mask,trial.repetition(),trial.observation(),Map.of("testDouble",true));
        }
        var fabricated=new CounterfactualEngine.Report(CounterfactualEngine.ProofState.PROVEN,
                List.of(Set.of("MESSAGE_REDELIVERY","MISSING_IDEMPOTENCY")),healthy.trials(),2,DuplicatePaymentSandbox.SCOPE);
        assertThrows(ExperimentAbortedException.class,() -> store.complete(job,fabricated,"Caller claims success"));
        assertEquals(Status.EVIDENCE_COLLECTED,store.incident(incident.id()).status());
        assertEquals(HttpStatus.CONFLICT,http.getForEntity("/v1/incidents/"+incident.id()+"/proof",String.class).getStatusCode());
    }
    Incident nonPayment(String signature) {
        var response=http.postForEntity("/v1/incidents",new CreateIncident("Synthetic "+signature,Instant.now(),signature,null),Incident.class);
        assertEquals(HttpStatus.CREATED,response.getStatusCode());
        return response.getBody();
    }
    Evidence access(Incident incident,Map<String,String> metadata) {
        return new Evidence(UUID.randomUUID(),incident.startedAt(),"synthetic-resource-service",EventType.ACCESS_DENIED,null,
                "trace-access",null,"request-access",null,"resource-123","GET","DENIED",metadata);
    }
    Map<String,String> policy() {
        return Map.of("serviceHealthy","true","resourceExists","true","policyVersion","demo-read-policy-v1","hasReadPermission","false");
    }
    @Test void accessDenialIsExpectedOnlyAfterPermissionInterventionsAndHasNoCandidates() {
        Incident incident=nonPayment("ACCESS_DENIED"); upload(incident,List.of(access(incident,policy()))); enqueue(incident);
        new InvestigationWorker(store,(j,i)->{throw new AssertionError("Payment sandbox must not run");}).poll();
        assertEquals(Status.PROVEN,store.incident(incident.id()).status());
        assertEquals(8,store.investigation(incident.id()).runs().size());
        assertEquals(List.of(Set.of("MISSING_PERMISSION")),store.proof(incident.id()).report().minimalCausalSets());
        var classification=http.getForObject("/v1/incidents/"+incident.id()+"/classification",DecisionService.Classification.class);
        assertEquals("EXPECTED_BEHAVIOR",classification.decision().category().name());
        assertFalse(classification.decision().customerMessage().contains("Evaluator"));
        var plan=http.postForObject("/v1/incidents/"+incident.id()+"/remediations",null,DecisionService.Remediations.class);
        assertEquals("NO_CODE_CHANGE",plan.plan().status()); assertTrue(plan.plan().candidates().isEmpty());
    }
    @Test void unsupportedPolicyAndContradictoryEvidenceAbstain() {
        for (boolean contradiction:List.of(false,true)) {
            Incident incident=nonPayment("ACCESS_DENIED");
            upload(incident,contradiction?List.of(access(incident,policy()),access(incident,policy())):
                    List.of(access(incident,Map.of("hasReadPermission","false"))));
            enqueue(incident);
            new InvestigationWorker(store,(j,i)->{throw new AssertionError("No sandbox for insufficient evidence");}).poll();
            assertEquals(Status.INCONCLUSIVE,store.incident(incident.id()).status());
            assertEquals("UNKNOWN",decisions.classification(incident.id()).decision().category().name());
            assertTrue(decisions.plan(incident.id()).plan().candidates().isEmpty());
        }
    }
    @Test void intermittentTimeoutAbstainsAndRequestsTelemetry() {
        Incident incident=nonPayment("INTERMITTENT_TIMEOUT");
        upload(incident,List.of(new Evidence(UUID.randomUUID(),incident.startedAt(),"client",EventType.REQUEST_TIMEOUT,null,
                null,null,"request-timeout",null,"dependency","GET","TIMEOUT",Map.of()))); enqueue(incident);
        new InvestigationWorker(store,(j,i)->{throw new AssertionError("Timeout has no discriminating sandbox");}).poll();
        assertEquals(Status.INCONCLUSIVE,store.incident(incident.id()).status());
        assertTrue(store.investigation(incident.id()).runs().isEmpty());
        assertEquals("COLLECT_MORE_EVIDENCE",decisions.plan(incident.id()).plan().status());
        assertTrue(store.proof(incident.id()).reason().contains("client/server spans"));
    }
    @Test void plansAreGatedIdempotentAndSurviveRestartWithOrganizationSnapshot() {
        Incident incident=create();
        String url="/v1/incidents/"+incident.id();
        assertEquals(HttpStatus.CONFLICT,http.getForEntity(url+"/classification",String.class).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,http.postForEntity(url+"/remediations",null,String.class).getStatusCode());
        upload(incident,completeEvidence(incident)); enqueue(incident);
        new InvestigationWorker(store,truthTable(new AtomicInteger(),false)).poll();
        var first=http.postForObject(url+"/remediations",null,DecisionService.Remediations.class);
        assertEquals("ARCHITECTURE_DEFECT",decisions.classification(incident.id()).decision().category().name());
        assertEquals("IDEMPOTENCY_LEDGER",first.plan().recommendedCandidate());
        assertEquals(64,first.organizationProfile().sha256().length());
        assertEquals(first,http.postForObject(url+"/remediations",null,DecisionService.Remediations.class));
        try (var restarted=restart(false)) {
            var client=RestClient.create("http://localhost:"+restarted.getWebServer().getPort());
            assertEquals(first,client.get().uri(url+"/remediations").retrieve().body(DecisionService.Remediations.class));
        }
        assertEquals(HttpStatus.BAD_REQUEST,http.postForEntity("/v1/incidents",new CreateIncident("missing order",Instant.now(),"DUPLICATE_PAYMENT",null),String.class).getStatusCode());
    }
    @Test void candidateVerificationRunsRealFaultSuiteAndPersistsScopedReport() throws Exception {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        new InvestigationWorker(store,truthTable(new AtomicInteger(),false)).poll();
        String url="/v1/incidents/"+incident.id()+"/remediations/";
        assertEquals(HttpStatus.CONFLICT,http.postForEntity(url+"FIFO_MIGRATION/verify",null,String.class).getStatusCode());
        var response=http.postForEntity(url+"IDEMPOTENCY_LEDGER/verify",null,VerificationModels.View.class);
        assertEquals(HttpStatus.ACCEPTED,response.getStatusCode());
        UUID id=response.getBody().id();
        assertEquals(id,http.postForObject(url+"IDEMPOTENCY_LEDGER/verify",null,VerificationModels.View.class).id());
        assertEquals(HttpStatus.CONFLICT,http.getForEntity("/v1/verifications/"+id+"/certificate",String.class).getStatusCode());
        var verificationWorker=new VerificationWorker(verifications,store,paymentSandbox);
        // This context disables scheduled workers; preserve the production lease heartbeat
        // while the real fault suite runs, including on slower Docker hosts.
        try (var heartbeat=Executors.newSingleThreadScheduledExecutor()) {
            heartbeat.scheduleWithFixedDelay(verificationWorker::heartbeat,10,10,TimeUnit.SECONDS);
            verificationWorker.poll();
        }
        var result=verifications.view(id);
        assertEquals("VERIFIED_IN_SANDBOX",result.status(),String.valueOf(result.report()));
        assertEquals(22,result.checks().size());
        assertTrue(result.checks().stream().allMatch(VerificationModels.Check::passed));
        assertEquals("NOT_CERTIFIED",certificates.certificate(id).status());
        var rejected=http.postForEntity("/v1/verifications/"+id+"/certificate",new CertificationGate.Envelope("{}","AA=="),CertificationService.Certificate.class);
        assertEquals(HttpStatus.OK,rejected.getStatusCode());
        assertEquals("NOT_CERTIFIED",rejected.getBody().status());
        assertFalse(rejected.getBody().blockers().isEmpty());
        assertEquals(HttpStatus.BAD_REQUEST,http.postForEntity("/v1/verifications/"+id+"/certificate",new CertificationGate.Envelope("",""),String.class).getStatusCode());
        exportCertificationSubject(result,"DUPLICATE_PAYMENT","certification-subject.json");
        try (var restarted=restart(false)) {
            assertEquals(result,restarted.getBean(VerificationStore.class).view(id));
        }
    }
    @Test void verificationFencesOldWorkersAndCannotPassWithMissingChecks() {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        new InvestigationWorker(store,truthTable(new AtomicInteger(),false)).poll();
        UUID id=verifications.enqueue(incident.id(),"IDEMPOTENCY_LEDGER").id();
        var old=verifications.claim().orElseThrow();
        verifications.checkpoint(old,new VerificationModels.Check("BASELINE_0",true,Map.of(),"old attempt"));
        db.update("UPDATE verification_runs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",id);
        var current=verifications.claim().orElseThrow();
        assertFalse(verifications.heartbeat(old));
        assertThrows(IllegalStateException.class,()->verifications.complete(old,null));
        assertTrue(verifications.checks(current).isEmpty(),"Evidence from abandoned attempts cannot fill coverage gaps");
        verifications.complete(current,null);
        assertEquals("INCONCLUSIVE",verifications.view(id).status());
        assertEquals("NOT_CERTIFIED",certificates.certificate(id).status());
    }
    @Test void expiredVerificationCannotStartFaults() {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        new InvestigationWorker(store,truthTable(new AtomicInteger(),false)).poll();
        UUID id=verifications.enqueue(incident.id(),"IDEMPOTENCY_LEDGER").id();
        db.update("UPDATE verification_runs SET deadline=clock_timestamp()-interval '1 second' WHERE id=?",id);
        new VerificationWorker(verifications,store,paymentSandbox).poll();
        assertEquals("INCONCLUSIVE",verifications.view(id).status());
        assertTrue(verifications.view(id).checks().isEmpty());
    }
    @Test void certificatesPersistIdempotentlyAndKeyRevocationMakesThemStale() throws Exception {
        Incident incident=create(); upload(incident,completeEvidence(incident)); enqueue(incident);
        new InvestigationWorker(store,truthTable(new AtomicInteger(),false)).poll();
        UUID id=verifications.enqueue(incident.id(),"IDEMPOTENCY_LEDGER").id();
        var organization=new OrganizationProfile();
        var initialFixture=CertificationGateTest.run(id,VerificationStore.artifactDigest(),organization.snapshot().sha256());
        var fixture=new VerificationModels.View(id,incident.id(),initialFixture.candidateId(),initialFixture.status(),1,initialFixture.createdAt(),initialFixture.deadline(),initialFixture.checks(),initialFixture.report());
        // This explicitly mocked subject and ephemeral authority only exercise issuance/persistence.
        // The real fault suite above supplies CI evidence; this fixture is never exported.
        var mockRuns=org.mockito.Mockito.mock(VerificationStore.class);
        org.mockito.Mockito.when(mockRuns.view(id)).thenReturn(fixture);
        var mockBuild=org.mockito.Mockito.mock(BuildProvenance.class);
        org.mockito.Mockito.when(mockBuild.binding()).thenReturn(CertificationGateTest.binding());
        var key=CertificationGateTest.key();
        var envelope=CertificationGateTest.sign(CertificationGateTest.evidence(fixture,Instant.now()),key);
        var service=new CertificationService(mockRuns,mockBuild,organization,db,json,CertificationGateTest.publicKey(key),"Parash-Shah/rca-proof");
        var certificate=service.issue(id,envelope);
        assertEquals("CERTIFIED",certificate.status()); assertNotNull(certificate.id());
        assertEquals(certificate,service.issue(id,envelope));
        var reloaded=new CertificationService(mockRuns,mockBuild,organization,db,json,CertificationGateTest.publicKey(key),"Parash-Shah/rca-proof");
        assertEquals(certificate,reloaded.certificate(id));
        var github=org.mockito.Mockito.mock(GitHubReviewClient.class);
        org.mockito.Mockito.when(github.configured()).thenReturn(true);
        var reviews=new ReviewService(db,mockRuns,service,store,decisions,github,"Parash-Shah/rca-proof","main");
        var prepared=reviews.prepare(id);
        assertEquals(prepared,reviews.prepare(id));
        assertEquals("PREPARED",prepared.status()); assertTrue(prepared.body().contains(certificate.id().toString()));
        var remote=new java.util.concurrent.atomic.AtomicReference<GitHubReviewClient.Pull>();
        org.mockito.Mockito.when(github.find(prepared.repository(),prepared.branch(),prepared.baseBranch())).thenAnswer(call->remote.get());
        org.mockito.Mockito.when(github.create(prepared.repository(),prepared.branch(),prepared.baseBranch(),prepared.title(),prepared.body())).thenAnswer(call->{
            remote.set(new GitHubReviewClient.Pull(17,"https://github.com/Parash-Shah/rca-proof/pull/17","open",false,prepared.headSha(),prepared.baseSha(),prepared.body()));
            throw new IllegalStateException("Simulated lost reply after remote creation");
        });
        assertEquals("RETRY_REQUIRED",reviews.publish(id).status());
        var published=reviews.publish(id); assertEquals("OPEN",published.status()); assertEquals(17,published.pullNumber());
        var restartedReviews=new ReviewService(db,mockRuns,reloaded,store,decisions,github,"Parash-Shah/rca-proof","main");
        assertEquals(published,restartedReviews.get(id));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM change_requests WHERE verification_id=?",Integer.class,id));
        org.mockito.Mockito.verify(github,org.mockito.Mockito.times(1)).create(prepared.repository(),prepared.branch(),prepared.baseBranch(),prepared.title(),prepared.body());
        var tampered=new CertificationGate.Envelope(envelope.payload()+" ",envelope.signature());
        assertEquals("NOT_CERTIFIED",service.issue(id,tampered).status());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM remediation_certificates WHERE verification_id=?",Integer.class,id));
        var revoked=new CertificationService(mockRuns,mockBuild,organization,db,json,CertificationGateTest.publicKey(CertificationGateTest.key()),"Parash-Shah/rca-proof");
        assertEquals("STALE",revoked.certificate(id).status());
        var staleReviews=new ReviewService(db,mockRuns,revoked,store,decisions,github,"Parash-Shah/rca-proof","main");
        assertEquals("STALE",staleReviews.get(id).status());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->staleReviews.publish(id));
        assertEquals("CERTIFIED",db.queryForObject("SELECT certificate->>'status' FROM remediation_certificates WHERE verification_id=?",String.class,id));
    }
    @Test void reusableScenariosProduceScopedProofsAndDurableMetrics() {
        for (var definition:dev.rcaproof.experiment.ScenarioLab.definitions()) {
            if (Set.of("ACCESS_DENIED","RETRY_STORM").contains(definition.signature())) continue;
            var response=http.postForEntity("/v1/incidents",new CreateIncident("Behavioral model "+definition.signature(),Instant.now(),definition.signature(),null),Incident.class);
            assertEquals(HttpStatus.CREATED,response.getStatusCode()); var incident=response.getBody();
            var evidence=new Evidence(UUID.randomUUID(),Instant.now(),"local-behavioral-lab",EventType.LAB_OBSERVATION,null,null,null,"request",null,"local-model","RUN","FAILURE",
                    Map.of("scenarioVersion",dev.rcaproof.experiment.ScenarioLab.VERSION,"signature",definition.signature(),"variant","STANDARD"));
            upload(incident,List.of(evidence)); enqueue(incident);
            new InvestigationWorker(store,paymentSandbox).poll();
            assertEquals(dev.rcaproof.causal.CounterfactualEngine.ProofState.PROVEN,store.proof(incident.id()).report().state());
            assertEquals(dev.rcaproof.experiment.ScenarioLab.SCOPE,store.proof(incident.id()).report().scope());
            assertNotEquals(dev.rcaproof.classification.FaultClassifier.Category.UNKNOWN,decisions.classification(incident.id()).decision().category());
            assertEquals(HttpStatus.OK,http.getForEntity("/v1/incidents/"+incident.id()+"/hypotheses",String.class).getStatusCode());
        }
        var metrics=http.getForEntity("/v1/metrics",String.class);
        assertEquals(HttpStatus.OK,metrics.getStatusCode()); assertTrue(metrics.getBody().contains("rcaproof_incidents_proven 6"));
        assertEquals(HttpStatus.OK,http.getForEntity("/v1/overview",String.class).getStatusCode());
        assertEquals(HttpStatus.OK,http.getForEntity("/v1/scenarios",String.class).getStatusCode());
    }
    @Test void measuredHttpRetryIncidentReachesProofPlanAndScopedVerification() throws Exception {
        var response=http.postForEntity("/v1/labs/retry-storm",null,Incident.class);
        assertEquals(HttpStatus.CREATED,response.getStatusCode()); var incident=response.getBody();
        assertEquals(6,store.timeline(incident.id()).events().size()); enqueue(incident);
        new InvestigationWorker(store,retrySandbox).poll();
        assertEquals(CounterfactualEngine.ProofState.PROVEN,store.proof(incident.id()).report().state());
        assertEquals(dev.rcaproof.experiment.RetryContract.SCOPE,store.proof(incident.id()).report().scope());
        assertEquals("BOUNDED_RETRY",decisions.plan(incident.id()).plan().recommendedCandidate());
        UUID id=verifications.enqueue(incident.id(),"BOUNDED_RETRY").id();
        var worker=new VerificationWorker(verifications,store,paymentSandbox,retrySandbox);
        try (var heartbeat=Executors.newSingleThreadScheduledExecutor()) {
            heartbeat.scheduleWithFixedDelay(worker::heartbeat,10,10,TimeUnit.SECONDS); worker.poll();
        }
        var result=verifications.view(id);
        assertEquals("VERIFIED_IN_SANDBOX",result.status(),String.valueOf(result.report())); assertEquals(16,result.checks().size());
        assertEquals("http-retry-verification-v2",result.report().suiteVersion());
        exportCertificationSubject(result,"RETRY_STORM","certification-subject-http-retry.json");
        assertEquals("NOT_CERTIFIED",certificates.certificate(id).status());
        assertEquals(HttpStatus.CONFLICT,http.postForEntity("/v1/verifications/"+id+"/review",null,String.class).getStatusCode());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->verifications.enqueue(incident.id(),"DISABLE_RETRIES"));
        // Local ephemeral authority and explicitly substituted committed-build fixture.
        // Never export this fixture as independently measured CI evidence.
        var bound=new VerificationModels.Report(result.report().status(),result.report().suiteVersion(),result.report().candidateDigest(),result.report().organizationDigest(),
                result.report().scope(),result.checks(),List.of(),"Certification persistence fixture",CertificationGateTest.binding());
        var fixture=new VerificationModels.View(id,incident.id(),result.candidateId(),result.status(),1,result.createdAt(),result.deadline(),result.checks(),bound);
        var mockRuns=org.mockito.Mockito.mock(VerificationStore.class);
        org.mockito.Mockito.when(mockRuns.view(id)).thenReturn(fixture);
        var mockBuild=org.mockito.Mockito.mock(BuildProvenance.class);
        org.mockito.Mockito.when(mockBuild.binding()).thenReturn(CertificationGateTest.binding());
        var key=CertificationGateTest.key();
        var authority=new CertificationService(mockRuns,mockBuild,new OrganizationProfile(),db,json,CertificationGateTest.publicKey(key),"Parash-Shah/rca-proof");
        var envelope=CertificationGateTest.sign(CertificationGateTest.evidence(fixture,Instant.now()),key);
        var certified=authority.issue(id,envelope);
        assertEquals("CERTIFIED",certified.status()); assertEquals(certified,authority.issue(id,envelope));
        var reloaded=new CertificationService(mockRuns,mockBuild,new OrganizationProfile(),db,json,CertificationGateTest.publicKey(key),"Parash-Shah/rca-proof");
        assertEquals(certified,reloaded.certificate(id));
        var reviewService=new ReviewService(db,mockRuns,reloaded,store,decisions,org.mockito.Mockito.mock(GitHubReviewClient.class),"Parash-Shah/rca-proof","main");
        var prepared=reviewService.prepare(id);
        assertEquals("PREPARED",prepared.status()); assertTrue(prepared.title().contains("HTTP"));
        assertTrue(prepared.body().contains("16 passing checks")); assertFalse(prepared.body().contains("payment ledger"));
        assertEquals(prepared,reviewService.prepare(id));
        var revoked=new CertificationService(mockRuns,mockBuild,new OrganizationProfile(),db,json,CertificationGateTest.publicKey(CertificationGateTest.key()),"Parash-Shah/rca-proof");
        assertEquals("STALE",revoked.certificate(id).status());
    }
    private void exportCertificationSubject(VerificationModels.View result,String signature,String filename) throws Exception {
        // Unbound local builds can exercise the lab. Committed CI builds must export
        // the real source/archive binding, never a passing suite with broken provenance.
        try (var manifest=getClass().getResourceAsStream("/build-evidence/source.json")) {
            if (manifest!=null) {
                var binding=result.report().binding();
                assertNotNull(binding);
                assertNull(binding.problem(),"Committed verification must have valid build provenance");
                assertEquals(json.readValue(manifest,BuildProvenance.Source.class),binding.source());
                assertFalse(binding.dependencies().isEmpty());
                assertEquals(BuildProvenance.sha256(java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("target/rcaproof-api-0.1.0-SNAPSHOT-exec.jar"))),binding.applicationSha256());
            }
        }
        var subject=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(result.report());
        subject.put("candidateId",result.candidateId()).put("failureSignature",signature);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target",filename),json.writeValueAsString(subject));
    }
}
