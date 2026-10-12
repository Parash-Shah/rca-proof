package dev.rcaproof.api;

import com.fasterxml.jackson.databind.*;
import dev.rcaproof.experiment.RetryingHttpClient;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import static dev.rcaproof.api.VerificationModels.Check;

/** Isolated transport interventions. A client exception alone is never evidence of an injected fault. */
final class HttpTransportSandbox {
    static final String VERSION="http-retry-verification-v2";
    static final Set<String> REQUIRED=Set.of("HTTP_LATENCY_BASELINE","HTTP_DEADLINE","HTTP_BUDGET_PROPAGATION",
            "HTTP_TRANSPORT_HEALTHY","HTTP_CONNECTION_LOSS","HTTP_TRUNCATED_RESPONSE","HTTP_EXPIRED_DEADLINE");
    static final String SCOPE="LOCAL_HTTP_LAB: bounded GET retries plus delayed responses, monotonic caller deadline, relative one-hop budget propagation and WireMock connection faults; no production SLO or multi-hop cancellation guarantee; "+VERSION;
    record Measurement(RetryingHttpClient.TimedResult client,int providerRequests,boolean correlated,List<Long> receivedBudgets) {
        Map<String,Object> values() { return Map.of("client",client,"providerRequests",providerRequests,"correlated",correlated,
                "receivedBudgetsMs",receivedBudgets,"wireReplayObserved",providerRequests>client.attempts().size()); }
    }
    private final ObjectMapper json;
    private final URI endpoint;
    HttpTransportSandbox(ObjectMapper json,String endpoint) {
        // Apply the same fixed local-target boundary as the HTTP retry adapter.
        new HttpRetrySandbox(json,endpoint);
        this.json=json; this.endpoint=URI.create(endpoint);
    }
    Measurement sample(String fault,boolean enforceDeadline,long totalMs,Runnable budget) throws Exception {
        Map<String,Object> response=switch(fault) {
            case "HEALTHY" -> Map.of("status",200,"body","healthy");
            case "LATENCY" -> Map.of("status",200,"fixedDelayMilliseconds",1200,"body","delayed");
            case "SLOW_500" -> Map.of("status",500,"fixedDelayMilliseconds",200);
            case "CONNECTION_LOSS" -> Map.of("fault","EMPTY_RESPONSE");
            case "TRUNCATED_RESPONSE" -> Map.of("fault","MALFORMED_RESPONSE_CHUNK");
            default -> throw new IllegalArgumentException("Unknown transport intervention");
        };
        String id=UUID.randomUUID().toString(),path="/rcaproof/transport/"+id;
        var match=Map.of("method","GET","url",path,"headers",Map.of("X-RCAProof-Request-Id",Map.of("equalTo",id)));
        try (var admin=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();var client=new RetryingHttpClient()) {
            try {
                budget.run(); request(admin,"POST","/__admin/mappings",Map.of("id",id,"request",match,"response",response));
                var result=client.getWithin(endpoint.resolve(path),id,"A",2,Duration.ofSeconds(3),Duration.ofMillis(totalMs),enforceDeadline,budget);
                // Delayed requests may enter the journal only after their server response completes.
                JsonNode receipts; long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                do {
                    budget.run(); receipts=request(admin,"POST","/__admin/requests/find",match).path("requests");
                    if (receipts.size()>=result.attempts().size()) break;
                    Thread.sleep(50);
                } while(System.nanoTime()<until);
                boolean correlated=receipts.isArray(); Set<Integer> seen=new HashSet<>(); List<Long> received=new ArrayList<>();
                for (var receipt:receipts) {
                    try {
                        int ordinal=Integer.parseInt(header(receipt,"X-RCAProof-Attempt"));
                        long remaining=Long.parseLong(header(receipt,"X-RCAProof-Remaining-Ms")); received.add(remaining);
                        correlated &= ordinal>=1 && ordinal<=result.attempts().size();
                        if (ordinal>=1 && ordinal<=result.attempts().size()) correlated &= remaining==result.attempts().get(ordinal-1).remainingMs();
                        seen.add(ordinal);
                    } catch(RuntimeException e) { correlated=false; }
                }
                correlated &= seen.size()==result.attempts().size();
                return new Measurement(result,receipts.size(),correlated,List.copyOf(received));
            } finally {
                try { request(admin,"DELETE","/__admin/mappings/"+id,null); }
                finally { request(admin,"POST","/__admin/requests/remove",match); }
            }
        }
    }
    private static String header(JsonNode request,String name) {
        for(var fields=request.path("headers").fields();fields.hasNext();) {
            var field=fields.next(); if(field.getKey().equalsIgnoreCase(name)) {
                var value=field.getValue(); return value.isTextual()?value.asText():value.isArray() && value.size()==1?value.get(0).asText():"";
            }
        }
        return "";
    }
    private JsonNode request(HttpClient client,String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        var response=client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()<200 || response.statusCode()>299) throw new IllegalStateException("Transport lab administration failed: "+response.statusCode());
        return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
    }
    void verify(Consumer<Check> checkpoint,Runnable budget) throws Exception {
        var baseline=sample("LATENCY",false,600,budget);
        emit(checkpoint,"HTTP_LATENCY_BASELINE",baseline,baseline.client().outcome().equals("HTTP_200") && baseline.providerRequests()==1 && baseline.client().elapsedMillis()>=1200,
                "Per-attempt timeout alone permits a response after the 600ms caller budget");
        var deadline=sample("LATENCY",true,600,budget);
        emit(checkpoint,"HTTP_DEADLINE",deadline,deadline.client().outcome().equals("DEADLINE_EXCEEDED") && deadline.providerRequests()==1 && deadline.client().elapsedMillis()<=1000,
                "Shared 600ms deadline surfaces timeout; 400ms scheduling tolerance is local validation only, not an SLO");
        var propagation=sample("SLOW_500",true,2000,budget);
        var attempts=propagation.client().attempts();
        emit(checkpoint,"HTTP_BUDGET_PROPAGATION",propagation,attempts.size()==2 && propagation.providerRequests()==2
                && attempts.stream().allMatch(a->a.outcome().equals("HTTP") && a.status()==500 && a.timeoutMs()<=a.remainingMs())
                && attempts.get(1).remainingMs()<attempts.get(0).remainingMs(),"Each retry forwards the remaining budget, never a fresh original timeout");
        var healthy=sample("HEALTHY",true,2000,budget);
        emit(checkpoint,"HTTP_TRANSPORT_HEALTHY",healthy,healthy.client().outcome().equals("HTTP_200") && healthy.providerRequests()==1,"Healthy negative control must return 200");
        for(String fault:List.of("CONNECTION_LOSS","TRUNCATED_RESPONSE")) {
            var broken=sample(fault,true,2000,budget);
            emit(checkpoint,"HTTP_"+fault,broken,broken.client().outcome().equals("TRANSPORT_ERROR") && broken.client().attempts().size()==1
                    && broken.providerRequests()>=1 && broken.providerRequests()<=2,"Surface broken transport without application retry; independently count possible JDK GET replay (maximum two wire requests)");
        }
        var expired=sample("HEALTHY",true,0,budget);
        emit(checkpoint,"HTTP_EXPIRED_DEADLINE",expired,expired.client().outcome().equals("DEADLINE_EXCEEDED")
                && expired.client().attempts().isEmpty() && expired.providerRequests()==0,"An expired caller budget must issue no HTTP request");
    }
    private static void emit(Consumer<Check> sink,String name,Measurement value,boolean passed,String detail) {
        sink.accept(new Check(name,value.correlated() && passed,value.values(),detail));
    }
}
