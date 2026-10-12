package dev.rcaproof.api;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VerificationGateTest {
    @Test void missingDuplicateOrFailedChecksNeverPass() {
        var checks=new ArrayList<>(VerificationGate.REQUIRED.stream().map(name->new VerificationModels.Check(name,true,Map.of(),"test")).toList());
        assertEquals("VERIFIED_IN_SANDBOX",VerificationGate.status(checks));
        checks.removeLast();
        assertEquals("INCONCLUSIVE",VerificationGate.status(checks));
        checks.add(checks.getFirst());
        assertEquals("INCONCLUSIVE",VerificationGate.status(checks));
        checks.set(0,new VerificationModels.Check(checks.getFirst().name(),false,Map.of(),"regression"));
        assertEquals("FAILED",VerificationGate.status(checks));
        assertFalse(VerificationGate.blockers().isEmpty());
    }
}
