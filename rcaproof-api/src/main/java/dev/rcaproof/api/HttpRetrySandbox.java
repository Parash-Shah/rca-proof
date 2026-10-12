package dev.rcaproof.api;

import com.fasterxml.jackson.databind.*;
import dev.rcaproof.experiment.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static dev.rcaproof.api.VerificationModels.Check;

/** Each trial owns unique mappings and a request matcher. Never resets shared WireMock state. */
@Component
public class HttpRetrySandbox implements SandboxFactory {
    public record Sample(String requestId,List<Integer> statuses,int providerRequests,boolean correlated,long durationMs) {
        public Map<String,Object> measurements() { return Map.of("requestId",requestId,"clientAttempts",statuses.size(),"providerRequests",providerRequests,
                "statuses",statuses,"correlated",correlated,"durationMs",durationMs,"maxDependencyCalls",RetryContract.MAX_DEPENDENCY_CALLS); }
    }
    private final ObjectMapper json;
    private final URI endpoint;
    public HttpRetrySandbox(ObjectMapper json,@Value("${rcaproof.http-sandbox-endpoint:http://localhost:8085}") String endpoint) {
        this.json=json; this.endpoint=URI.create(endpoint);
        if (!"http".equals(this.endpoint.getScheme()) || !Set.of("localhost","127.0.0.1","host.docker.internal","wiremock").contains(this.endpoint.getHost())
                || this.endpoint.getUserInfo()!=null || this.endpoint.getQuery()!=null || this.endpoint.getFragment()!=null || !Set.of("","/").contains(this.endpoint.getPath()))
            throw new IllegalArgumentException("HTTP fault sandbox must be a local WireMock endpoint");
    }
    public Sample sample(String fault,int attempts,String deployment,Duration timeout,Runnable budget) throws Exception {
        if (!Set.of("HEALTHY","HTTP_500","TRANSIENT_500","HTTP_400").contains(fault)) throw new IllegalArgumentException("Unsupported HTTP fault");
        String requestId=UUID.randomUUID().toString(),path="/rcaproof/"+requestId;
        List<UUID> mappings=new ArrayList<>();
        var match=Map.of("method","GET","url",path,"headers",Map.of("X-RCAProof-Request-Id",Map.of("equalTo",requestId)));
        try (var admin=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(); var client=new RetryingHttpClient()) {
            try {
                Map<String,Object> mapping=new HashMap<>(); mapping.put("request",match);
                mapping.put("response",Map.of("status",fault.equals("HTTP_400")?400:fault.equals("HEALTHY")?200:500,"body","local fault-lab response"));
                if (fault.equals("TRANSIENT_500")) { mapping.put("scenarioName",requestId); mapping.put("requiredScenarioState","Started"); mapping.put("newScenarioState","Recovered"); }
                UUID first=UUID.randomUUID(); mappings.add(first); mapping.put("id",first);
                budget.run(); admin(admin,"POST","/__admin/mappings",mapping);
                if (fault.equals("TRANSIENT_500")) {
                    UUID second=UUID.randomUUID(); mappings.add(second);
                    admin(admin,"POST","/__admin/mappings",Map.of("id",second,"request",match,"response",Map.of("status",200,"body","recovered"),"scenarioName",requestId,"requiredScenarioState","Recovered"));
                }
                RetryingHttpClient.Result result;
                if(attempts==2) {
                    var timed=client.getWithin(endpoint.resolve(path),requestId,deployment,attempts,timeout,Duration.ofSeconds(3),true,budget);
                    if(timed.attempts().isEmpty() || timed.attempts().stream().anyMatch(a->!a.outcome().equals("HTTP")))
                        throw new java.io.IOException("Retry sample did not finish with HTTP evidence: "+timed.outcome());
                    result=new RetryingHttpClient.Result(timed.attempts().stream().map(RetryingHttpClient.Attempt::status).toList(),timed.elapsedMillis());
                } else result=client.get(endpoint.resolve(path),requestId,deployment,attempts,Duration.ofMillis(25),timeout,budget);
                budget.run();
                var found=admin(admin,"POST","/__admin/requests/find",match).path("requests");
                Set<Integer> ordinals=new HashSet<>(); boolean correlated=found.isArray();
                for (var request:found) {
                    JsonNode values=json.createArrayNode();
                    for (var fields=request.path("headers").fields();fields.hasNext();) {
                        var header=fields.next(); if (header.getKey().equalsIgnoreCase("X-RCAProof-Attempt")) values=header.getValue();
                    }
                    try {
                        String ordinal=values.isTextual()?values.asText():values.isArray() && values.size()==1?values.get(0).asText():"";
                        correlated &= ordinals.add(Integer.parseInt(ordinal));
                    }
                    catch (RuntimeException e) { correlated=false; }
                }
                correlated &= found.size()==result.statuses().size();
                for (int i=1;i<=result.statuses().size();i++) correlated &= ordinals.contains(i);
                return new Sample(requestId,result.statuses(),found.size(),correlated,result.elapsedMillis());
            } finally {
                Exception cleanup=null;
                for (UUID id:mappings) try { admin(admin,"DELETE","/__admin/mappings/"+id,null); } catch (Exception e) { cleanup=e; }
                try { admin(admin,"POST","/__admin/requests/remove",match); } catch (Exception e) { cleanup=e; }
                if (cleanup!=null) throw new IllegalStateException("Scoped WireMock cleanup failed",cleanup);
            }
        }
    }
    private JsonNode admin(HttpClient client,String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()<200 || response.statusCode()>299) throw new IllegalStateException("WireMock administration returned HTTP "+response.statusCode());
        return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
    }
    @Override public Session open(IncidentModels.Job job,IncidentModels.Incident incident) {
        return new HttpSession();
    }
    final class HttpSession implements Session {
        public Result run(Set<String> factors,int repetition) throws Exception {
            if (!RetryContract.FACTORS.containsAll(factors)) throw new IllegalArgumentException("Unknown retry factor");
            var sample=sample(factors.contains("DEPENDENCY_HTTP_500")?"HTTP_500":"HEALTHY",factors.contains("EXCESSIVE_RETRIES")?6:2,
                    factors.contains("ALTERNATE_DEPLOYMENT")?"B":"A",Duration.ofSeconds(3),()->{});
            boolean fault=factors.contains("DEPENDENCY_HTTP_500");
            return new Result(assess(sample,fault?500:200,fault?(factors.contains("EXCESSIVE_RETRIES")?6:2):1),sample.measurements());
        }
        public void close() {}
    }
    static Observation assess(Sample sample,int expectedStatus,int expectedCalls) {
        boolean interventionObserved=sample.correlated() && sample.providerRequests()==expectedCalls && sample.statuses().size()==expectedCalls
                && sample.statuses().stream().allMatch(status->status==expectedStatus);
        var outcome=!interventionObserved?Observation.Outcome.INCONCLUSIVE:sample.providerRequests()>RetryContract.MAX_DEPENDENCY_CALLS?Observation.Outcome.FAILURE:Observation.Outcome.HEALTHY;
        return new Observation(outcome,"Measured dependency requests="+sample.providerRequests()+" for one correlated GET; max="+RetryContract.MAX_DEPENDENCY_CALLS+"; intended HTTP response/policy observed="+interventionObserved);
    }
    public void verify(Consumer<Check> checkpoint,Runnable budget) throws Exception {
        new HttpTransportSandbox(json,endpoint.toString()).verify(checkpoint,budget);
        for (int i=0;i<2;i++) {
            var baseline=sample("HTTP_500",6,"A",Duration.ofSeconds(3),budget);
            checkpoint.accept(new Check("RETRY_BASELINE_"+i,baseline.correlated() && baseline.providerRequests()==6 && baseline.statuses().stream().allMatch(s->s==500),baseline.measurements(),"Original policy must amplify persistent HTTP 500 responses"));
        }
        check(checkpoint,budget,"RETRY_PERSISTENT_500","HTTP_500",2,500,2);
        check(checkpoint,budget,"RETRY_HEALTHY","HEALTHY",2,200,1);
        check(checkpoint,budget,"RETRY_TRANSIENT_500","TRANSIENT_500",2,200,2);
        check(checkpoint,budget,"RETRY_CLIENT_400","HTTP_400",2,400,1);
        var disabled=sample("TRANSIENT_500",1,"A",Duration.ofSeconds(3),budget);
        checkpoint.accept(new Check("RETRY_REJECT_DISABLED",disabled.correlated() && disabled.statuses().getLast()==500,disabled.measurements(),"Reject disabling retries: it does not recover from a single transient 500"));
        var longer=sample("HTTP_500",6,"A",Duration.ofSeconds(10),budget);
        checkpoint.accept(new Check("RETRY_REJECT_LONG_TIMEOUT",longer.correlated() && longer.providerRequests()>RetryContract.MAX_DEPENDENCY_CALLS,longer.measurements(),"A longer timeout does not bound persistent HTTP 500 retries"));
        try (var executor=Executors.newFixedThreadPool(4)) {
            List<Future<Sample>> futures=new ArrayList<>();
            for (int i=0;i<4;i++) futures.add(executor.submit(()->sample("HTTP_500",2,"B",Duration.ofSeconds(3),budget)));
            List<Sample> results=new ArrayList<>(); for (var future:futures) results.add(future.get(60,TimeUnit.SECONDS));
            boolean passed=results.stream().allMatch(s->s.correlated() && s.providerRequests()==2 && s.statuses().getLast()==500);
            checkpoint.accept(new Check("RETRY_CONCURRENT",passed,Map.of("requests",results.stream().map(Sample::measurements).toList(),"aggregateProviderCalls",results.stream().mapToInt(Sample::providerRequests).sum()),"Four independent GET operations each retain their own two-attempt budget; persistent outage remains visible"));
        }
    }
    private void check(Consumer<Check> checkpoint,Runnable budget,String name,String fault,int attempts,int status,int calls) throws Exception {
        var sample=sample(fault,attempts,"A",Duration.ofSeconds(3),budget);
        checkpoint.accept(new Check(name,sample.correlated() && sample.providerRequests()==calls && sample.statuses().getLast()==status,
                sample.measurements(),"Bound calls, preserve transient recovery, stop on non-retryable responses, and surface a persistent outage"));
    }
}
