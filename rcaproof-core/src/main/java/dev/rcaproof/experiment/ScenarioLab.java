package dev.rcaproof.experiment;

import java.util.*;
import java.util.concurrent.Semaphore;

/** Small executable behavioral models. These are not production dependency emulators. */
public final class ScenarioLab {
    public record Definition(String signature,List<String> factors,String invariant,String description) {}
    public record Measurement(Observation observation,Map<String,Object> values) {}
    public static final String VERSION="behavioral-lab-v1";
    public static final String SCOPE="LOCAL_BEHAVIORAL_MODEL: bounded Java retry, queue, deadline, configuration, capacity and permission implementations; not production reproduction; "+VERSION;
    private static final List<Definition> DEFINITIONS=List.of(
            new Definition("RETRY_STORM",List.of("DEPENDENCY_HTTP_500","EXCESSIVE_RETRIES","ALTERNATE_DEPLOYMENT"),"dependency calls <= 3","Failed downstream calls exhaust a bounded retry loop"),
            new Definition("POISON_MESSAGE",List.of("INVALID_MESSAGE","MISSING_DEAD_LETTER_QUEUE","ALTERNATE_DEPLOYMENT"),"poison messages leave the active queue within 3 receives","Malformed payload is parsed, retried and optionally dead-lettered"),
            new Definition("TIMEOUT_PROPAGATION",List.of("DEPENDENCY_LATENCY","SHORT_TIMEOUT_BUDGET","ALTERNATE_DEPLOYMENT"),"dependency completes before the caller deadline","A virtual monotonic clock measures downstream work against its deadline"),
            new Definition("DEPENDENCY_OUTAGE",List.of("DEPENDENCY_UNAVAILABLE","MISSING_FALLBACK","ALTERNATE_DEPLOYMENT"),"request has an available response","An unavailable dependency throws; fallback can supply a response"),
            new Definition("INVALID_REQUEST",List.of("INVALID_AMOUNT","ALTERNATE_DEPLOYMENT"),"reported request is accepted","Request validator rejects invalid amounts; rejection is expected behavior"),
            new Definition("BAD_CONFIGURATION",List.of("MISSING_ROUTE","ALTERNATE_DEPLOYMENT"),"configured route resolves","An absent named route prevents dependency resolution"),
            new Definition("CAPACITY_FAILURE",List.of("BURST_LOAD","LOW_CONCURRENCY_LIMIT","ALTERNATE_DEPLOYMENT"),"all bounded concurrent work acquires a permit","Semaphore admission under simultaneous outstanding work"),
            new Definition("ACCESS_DENIED",List.of("MISSING_PERMISSION","ALTERNATE_DEPLOYMENT"),"reported request is allowed","A resource policy enforces read permission")
    );
    public static List<Definition> definitions() { return DEFINITIONS; }
    public static Optional<Definition> find(String signature) { return DEFINITIONS.stream().filter(d->d.signature().equals(signature)).findFirst(); }
    public static Measurement run(String signature,Set<String> enabled,String variant,int repetition) {
        var definition=find(signature).orElseThrow(()->new IllegalArgumentException("Unknown behavioral scenario"));
        if (!definition.factors().containsAll(enabled) || !Set.of("STANDARD","DISTRACTOR","GAP","CONTROL_FAILURE").contains(variant)) throw new IllegalArgumentException("Unsupported scenario factors or variant");
        if (variant.equals("GAP")) return new Measurement(new Observation(Observation.Outcome.INCONCLUSIVE,"Dependency measurements unavailable"),Map.of("telemetryComplete",false));
        // Explicit uncontrolled fault variant tests whether unhealthy negative controls prevent proof.
        Set<String> factors=variant.equals("CONTROL_FAILURE")?Set.copyOf(definition.factors()):enabled;
        Map<String,Object> measured=new LinkedHashMap<>();
        boolean failure;
        switch (signature) {
            case "RETRY_STORM" -> {
                int attempts=0,limit=factors.contains("EXCESSIVE_RETRIES")?6:2;
                for (int i=0;i<limit;i++) { attempts++; if (!factors.contains("DEPENDENCY_HTTP_500")) break; }
                measured.put("dependencyCalls",attempts); measured.put("callLimit",3); failure=attempts>3;
            }
            case "POISON_MESSAGE" -> {
                var queue=new ArrayDeque<String>(); queue.add(factors.contains("INVALID_MESSAGE")?"not-an-integer":"42");
                int receives=0,deadLetters=0,completed=0;
                while (!queue.isEmpty() && receives<5) {
                    String payload=queue.remove(); receives++;
                    try { Integer.parseInt(payload); completed++; }
                    catch (NumberFormatException e) {
                        if (!factors.contains("MISSING_DEAD_LETTER_QUEUE") && receives>=3) deadLetters++; else queue.add(payload);
                    }
                }
                measured.put("receives",receives); measured.put("activeMessages",queue.size()); measured.put("deadLetters",deadLetters); measured.put("completed",completed); failure=!queue.isEmpty();
            }
            case "TIMEOUT_PROPAGATION" -> {
                long clock=0,deadline=factors.contains("SHORT_TIMEOUT_BUDGET")?20:200;
                clock+=factors.contains("DEPENDENCY_LATENCY")?80:5;
                failure=clock>deadline; measured.put("elapsedVirtualMs",clock); measured.put("deadlineVirtualMs",deadline);
            }
            case "DEPENDENCY_OUTAGE" -> {
                String response;
                try { response=dependency(factors.contains("DEPENDENCY_UNAVAILABLE")); }
                catch (IllegalStateException e) { response=factors.contains("MISSING_FALLBACK")?null:"cached-response"; }
                failure=response==null; measured.put("responseAvailable",!failure); measured.put("fallbackUsed","cached-response".equals(response));
            }
            case "INVALID_REQUEST" -> {
                int amount=factors.contains("INVALID_AMOUNT")?-1:100;
                int status=amount>0?201:400; failure=status==400; measured.put("amount",amount); measured.put("status",status);
            }
            case "BAD_CONFIGURATION" -> {
                Map<String,String> routes=factors.contains("MISSING_ROUTE")?Map.of():Map.of("payment","local-provider");
                failure=routes.get("payment")==null; measured.put("routeResolved",!failure);
            }
            case "CAPACITY_FAILURE" -> {
                int limit=factors.contains("LOW_CONCURRENCY_LIMIT")?2:8,work=factors.contains("BURST_LOAD")?6:1;
                var permits=new Semaphore(limit); int accepted=0,rejected=0;
                // All work remains outstanding until admission completes; deterministic simultaneous load.
                for (int i=0;i<work;i++) if (permits.tryAcquire()) accepted++; else rejected++;
                permits.release(accepted); failure=rejected>0; measured.put("accepted",accepted); measured.put("rejected",rejected); measured.put("capacity",limit);
            }
            case "ACCESS_DENIED" -> {
                Set<String> permissions=factors.contains("MISSING_PERMISSION")?Set.of():Set.of("resource:read");
                int status=permissions.contains("resource:read")?200:403; failure=status==403; measured.put("status",status);
            }
            default -> throw new IllegalArgumentException("Unsupported signature");
        }
        measured.put("deployment",enabled.contains("ALTERNATE_DEPLOYMENT")?"B":"A"); measured.put("variant",variant);
        return new Measurement(new Observation(failure?Observation.Outcome.FAILURE:Observation.Outcome.HEALTHY,definition.invariant()+"; measured="+measured),Map.copyOf(measured));
    }
    private static String dependency(boolean unavailable) { if (unavailable) throw new IllegalStateException("Injected dependency outage"); return "response"; }
}
