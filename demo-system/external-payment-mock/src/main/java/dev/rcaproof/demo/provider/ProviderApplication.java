package dev.rcaproof.demo.provider;

import dev.rcaproof.demo.contract.Order;
import jakarta.validation.Valid;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.time.*;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication
@RestController
public class ProviderApplication {
    public record Charge(String chargeId, String orderId) {}
    public record Saved(Order order, Charge charge, long expiresAt) {}
    public record State(Map<String,Saved> keys,List<Charge> effects) {}
    public record Reconciliation(String chargeId,String orderId,String customerId,java.math.BigDecimal amount) {}
    private Map<String, Saved> keys = new HashMap<>();
    private List<Charge> effects = new ArrayList<>();
    private final Path stateFile;
    private final Clock clock;
    private final Duration retention;
    public ProviderApplication() { this(null,Clock.systemUTC(),Duration.ofHours(24)); }
    /** Single-process synthetic provider with an atomic durable journal; not a real payment integration. */
    public ProviderApplication(Path stateFile,Clock clock,Duration retention) {
        this.stateFile=stateFile; this.clock=clock; this.retention=retention;
        if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("Positive key retention required");
        try {
            if (stateFile!=null && Files.exists(stateFile)) {
                State state=new ObjectMapper().readValue(Files.readAllBytes(stateFile),State.class);
                keys=new HashMap<>(state.keys()); effects=new ArrayList<>(state.effects());
            }
        } catch (Exception e) { throw new IllegalStateException("Provider journal cannot be read",e); }
    }
    public static void main(String[] args) { SpringApplication.run(ProviderApplication.class, args); }

    @PostMapping("/charges")
    public synchronized Charge charge(@Valid @RequestBody Order order,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        if (key != null && keys.containsKey(key)) {
            Saved saved = keys.get(key);
            if (!saved.order().equals(order)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Key reused for a different payment");
            if (clock.millis()>=saved.expiresAt()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Key expired; reconcile the durable charge before retrying");
            return saved.charge();
        }
        Charge charge = new Charge(UUID.randomUUID().toString(), order.orderId());
        var nextEffects=new ArrayList<>(effects); nextEffects.add(charge);
        var nextKeys=new HashMap<>(keys);
        if (key != null) nextKeys.put(key, new Saved(order, charge,clock.instant().plus(retention).toEpochMilli()));
        if (stateFile!=null) {
            Path temporary=stateFile.resolveSibling(stateFile.getFileName()+".tmp");
            try {
                Files.write(temporary,new ObjectMapper().writeValueAsBytes(new State(nextKeys,nextEffects)));
                try (var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(temporary,stateFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { throw new IllegalStateException("Provider journal commit failed",e); }
        }
        keys=nextKeys; effects=nextEffects;
        return charge;
    }
    @GetMapping("/charges/reconcile")
    public synchronized Reconciliation reconcile(@RequestParam String key) {
        Saved saved=keys.get(key);
        if (saved==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown payment key");
        return new Reconciliation(saved.charge().chargeId(),saved.order().orderId(),saved.order().customerId(),saved.order().amount());
    }
    @GetMapping("/effects")
    public synchronized List<Charge> effects(@RequestParam(required=false) String orderId) {
        return effects.stream().filter(c -> orderId == null || c.orderId().equals(orderId)).toList();
    }
}
