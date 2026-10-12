package dev.rcaproof.demo.provider;

import dev.rcaproof.demo.contract.Order;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ProviderApplicationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void durableJournalSurvivesRestartAndExpiredKeyReconcilesWithoutNewCharge() {
        var time=java.time.Instant.parse("2026-01-01T00:00:00Z");
        var journal=directory.resolve("charges.json");
        var order=new Order("one","customer",BigDecimal.TEN);
        var provider=new ProviderApplication(journal,java.time.Clock.fixed(time,java.time.ZoneOffset.UTC),java.time.Duration.ofHours(24));
        var original=provider.charge(order,"payment:one");
        var restarted=new ProviderApplication(journal,java.time.Clock.fixed(time.plusSeconds(86400),java.time.ZoneOffset.UTC),java.time.Duration.ofHours(24));
        assertThrows(ResponseStatusException.class,()->restarted.charge(order,"payment:one"));
        assertEquals(original.chargeId(),restarted.reconcile("payment:one").chargeId());
        assertEquals(1,restarted.effects("one").size());
        assertThrows(ResponseStatusException.class,()->restarted.reconcile("unknown"));
    }
    @Test void unreadableJournalFailsClosed() throws Exception {
        var journal=directory.resolve("charges.json"); java.nio.file.Files.writeString(journal,"broken");
        assertThrows(IllegalStateException.class,()->new ProviderApplication(journal,java.time.Clock.systemUTC(),java.time.Duration.ofHours(24)));
    }
    @Test void concurrentRequestsWithSameKeyHaveOneEffect() throws Exception {
        var provider = new ProviderApplication();
        var order = new Order("one", "customer", new BigDecimal("129.95"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = IntStream.range(0, 20).<Callable<ProviderApplication.Charge>>mapToObj(i -> () -> provider.charge(order, "payment:one")).toList();
            var results = executor.invokeAll(calls);
            String id = results.getFirst().get().chargeId();
            for (var result : results) assertEquals(id, result.get().chargeId());
        }
        assertEquals(1, provider.effects("one").size());
    }
    @Test void keyCannotBeReusedForDifferentAmount() {
        var provider = new ProviderApplication();
        provider.charge(new Order("one", "customer", BigDecimal.ONE), "payment:one");
        assertThrows(ResponseStatusException.class, () -> provider.charge(new Order("one", "customer", BigDecimal.TEN), "payment:one"));
    }
    @Test void vulnerableCallsActuallyProduceDuplicateEffects() {
        var provider = new ProviderApplication();
        var order = new Order("one", "customer", BigDecimal.ONE);
        provider.charge(order, null); provider.charge(order, null);
        assertEquals(2, provider.effects("one").size());
    }
}
