package dev.rcaproof.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetryEvidenceTest {
    @Test void wrongFaultOrUnhealthyControlCannotBeInterpretedAsHealthy() {
        var unexpected=new HttpRetrySandbox.Sample("request",List.of(404),1,true,10);
        assertEquals(dev.rcaproof.experiment.Observation.Outcome.INCONCLUSIVE,HttpRetrySandbox.assess(unexpected,200,1).outcome());
        var healthy=new HttpRetrySandbox.Sample("request",List.of(200),1,true,10);
        assertEquals(dev.rcaproof.experiment.Observation.Outcome.INCONCLUSIVE,HttpRetrySandbox.assess(healthy,500,6).outcome());
        assertEquals(dev.rcaproof.experiment.Observation.Outcome.HEALTHY,HttpRetrySandbox.assess(healthy,200,1).outcome());
    }
    List<IncidentModels.TimelineEvent> timeline(HttpRetrySandbox.Sample sample) {
        return RetryLabController.evidence(sample,Instant.now()).stream().map(e->new IncidentModels.TimelineEvent(1,Instant.now(),e)).toList();
    }
    @Test void completeCorrelatedHttpReceiptsAreRequired() {
        var sample=new HttpRetrySandbox.Sample("request",Collections.nCopies(6,500),6,true,10);
        assertNull(ScenarioDefinition.retryEvidenceMissing(timeline(sample)));
        assertNotNull(ScenarioDefinition.retryEvidenceMissing(timeline(new HttpRetrySandbox.Sample("request",Collections.nCopies(6,500),5,true,10))));
        assertNotNull(ScenarioDefinition.retryEvidenceMissing(timeline(new HttpRetrySandbox.Sample("request",Collections.nCopies(6,500),6,false,10))));
        assertNotNull(ScenarioDefinition.retryEvidenceMissing(timeline(sample).subList(1,6)));
    }
    @Test void duplicateOrdinalsAndMixedRequestsCannotEstablishAmplification() {
        var events=new ArrayList<>(timeline(new HttpRetrySandbox.Sample("request",Collections.nCopies(6,500),6,true,10)));
        events.set(5,events.getFirst()); assertNotNull(ScenarioDefinition.retryEvidenceMissing(events));
        events.set(5,timeline(new HttpRetrySandbox.Sample("different-request",Collections.nCopies(6,500),6,true,10)).getLast());
        assertNotNull(ScenarioDefinition.retryEvidenceMissing(events));
    }
}
