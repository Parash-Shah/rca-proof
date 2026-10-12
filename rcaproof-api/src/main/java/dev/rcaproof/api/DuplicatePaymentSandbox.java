package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.rcaproof.demo.contract.Order;
import dev.rcaproof.demo.provider.ProviderApplication;
import dev.rcaproof.demo.worker.PaymentProcessor;
import dev.rcaproof.experiment.Observation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.net.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static dev.rcaproof.api.VerificationModels.Check;
import static dev.rcaproof.api.IncidentModels.*;

@Component
public class DuplicatePaymentSandbox implements SandboxFactory, AutoCloseable {
    public static final String VERSION = "duplicate-payment-v2";
    public static final List<String> FACTORS = List.of("WORKER_CRASH", "MESSAGE_REDELIVERY", "MISSING_IDEMPOTENCY");
    public static final String SCOPE = "LocalStack SQS/DynamoDB; actual PaymentProcessor and loopback HTTP provider mock; controlled post-charge exception; two-delivery safety only; " + VERSION;
    private final SqsClient sqs;
    private final DynamoDbClient db;
    private final ObjectMapper json;
    public DuplicatePaymentSandbox(ObjectMapper json, @Value("${rcaproof.sandbox-endpoint}") String endpoint) {
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || !Set.of("localhost","127.0.0.1","localstack","host.docker.internal").contains(uri.getHost()))
            throw new IllegalArgumentException("Sandbox endpoint must be a local LocalStack host");
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test","test"));
        this.sqs = SqsClient.builder().endpointOverride(uri).region(Region.US_EAST_1).credentialsProvider(credentials)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(30))).build();
        this.db = DynamoDbClient.builder().endpointOverride(uri).region(Region.US_EAST_1).credentialsProvider(credentials)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(30))).build();
        this.json = json;
    }
    public static int mask(Set<String> factors) {
        int mask = 0;
        for (int i=0;i<FACTORS.size();i++) if (factors.contains(FACTORS.get(i))) mask |= 1 << i;
        return mask;
    }
    public static Set<String> factors(int mask) {
        Set<String> result = new TreeSet<>();
        for (int i=0;i<FACTORS.size();i++) if ((mask & (1 << i)) != 0) result.add(FACTORS.get(i));
        return Set.copyOf(result);
    }
    private String prefix(Job job, int attempt) { return "rp-" + job.id().toString().replace("-","") + "-" + attempt; }
    private void remove(String prefix) {
        for (String url : sqs.listQueues(r -> r.queueNamePrefix(prefix + "-")).queueUrls()) {
            try { sqs.deleteQueue(r -> r.queueUrl(url)); } catch (QueueDoesNotExistException ignored) {}
        }
        for (String suffix : List.of("-orders","-payments")) {
            try { db.deleteTable(r -> r.tableName(prefix + suffix)); } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException ignored) {}
        }
    }
    @Override public void cleanup(Job job) {
        for (int attempt=1; attempt<=job.attempts(); attempt++) remove(prefix(job,attempt));
    }
    @Override public Session open(Job job, Incident incident) throws Exception {
        for (int attempt=1; attempt<job.attempts(); attempt++) remove(prefix(job,attempt));
        LabSession session = new LabSession(prefix(job,job.attempts()), incident.order());
        try { session.initialize(); return session; }
        catch (Exception e) { try { session.close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); } throw e; }
    }
    @Override public void close() { sqs.close(); db.close(); }
    public void verify(UUID runId, int attempt, Order original, Consumer<Check> checkpoint, Runnable budget) throws Exception {
        String name="rv-"+runId.toString().replace("-","")+"-"+attempt;
        for (int old=1;old<attempt;old++) remove("rv-"+runId.toString().replace("-","")+"-"+old);
        try (var lab=new LabSession(name,original,true)) {
            lab.initialize();
            for (int repetition=0;repetition<2;repetition++) {
                budget.run();
                var baseline=lab.run(Set.copyOf(FACTORS),repetition);
                checkpoint.accept(new Check("BASELINE_"+repetition,baseline.observation().outcome()==Observation.Outcome.FAILURE,
                        baseline.measurements(),"Original implementation must reproduce duplicate effects"));
                budget.run();
                checkpoint.accept(lab.verifyBatch("REPLAY_"+repetition,1,1,1,false,false,false,false,budget));
            }
            for (int mask=0;mask<4;mask++) {
                budget.run();
                var result=lab.runCandidate(factors(mask),0);
                boolean saved=Boolean.TRUE.equals(result.measurements().get("paymentResultSaved"));
                boolean recoverable=(mask & 1)==0 || (mask & 2)!=0;
                checkpoint.accept(new Check("COUNTERFACTUAL_"+mask,result.observation().outcome()==Observation.Outcome.HEALTHY && (!recoverable || saved),
                        result.measurements(),"Safety under trigger/removal controls; completion required whenever delivery permits recovery"));
            }
            checkpoint.accept(lab.verifyBatch("DUPLICATES_10",1,10,0,false,false,false,false,budget));
            checkpoint.accept(lab.verifyBatch("CRASH_TWICE",1,1,2,false,false,false,false,budget));
            checkpoint.accept(lab.verifyBatch("PROVIDER_TIMEOUT",1,1,0,true,false,false,false,budget));
            checkpoint.accept(lab.verifyBatch("LEDGER_UNAVAILABLE",1,1,0,false,true,false,false,budget));
            checkpoint.accept(lab.verifyBatch("DELAYED_ACK",1,1,0,false,false,true,false,budget));
            checkpoint.accept(lab.verifyBatch("CONCURRENT_DUPLICATES",1,10,0,false,false,false,true,budget));
            checkpoint.accept(lab.verifyBatch("BOUNDED_LOAD",20,2,0,false,false,false,true,budget));
            for (String test:List.of("PROVIDER_RESTART","KEY_EXPIRY","RETENTION_BOUNDARY","LEDGER_RETENTION",
                    "LEDGER_CONFLICT","MIGRATION_COMPATIBILITY","ROLLBACK_COMPATIBILITY")) {
                budget.run(); checkpoint.accept(lab.certificationCase(test));
            }
        }
    }
    private static final class InjectedCrash extends RuntimeException {}
    private static final class LabClock extends Clock {
        private Instant now=Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public synchronized Instant instant() { return now; }
        synchronized void advance(Duration duration) { now=now.plus(duration); }
    }
    private final class LabSession implements Session {
        private final String prefix;
        private final Order original;
        private volatile ProviderApplication provider;
        private final LabClock clock=new LabClock();
        private final Path journal;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private HttpServer server;
        private RestClient http;
        private final Set<String> delayResponses=ConcurrentHashMap.newKeySet();
        LabSession(String prefix, Order original) { this(prefix,original,false); }
        LabSession(String prefix, Order original,boolean durable) {
            this.prefix=prefix; this.original=original;
            try { journal=durable ? Files.createTempDirectory("rcaproof-provider-").resolve("charges.json") : null; }
            catch (Exception e) { throw new IllegalStateException(e); }
            provider=new ProviderApplication(journal,clock,Duration.ofHours(24));
        }
        void initialize() throws Exception {
            for (String suffix : List.of("-orders","-payments")) {
                db.createTable(r -> r.tableName(prefix+suffix).billingMode(BillingMode.PAY_PER_REQUEST)
                        .keySchema(KeySchemaElement.builder().attributeName("orderId").keyType(KeyType.HASH).build())
                        .attributeDefinitions(AttributeDefinition.builder().attributeName("orderId").attributeType(ScalarAttributeType.S).build()));
                db.waiter().waitUntilTableExists(r -> r.tableName(prefix+suffix));
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(executor);
            server.createContext("/charges", exchange -> {
                try {
                    Order order = json.readValue(exchange.getRequestBody(),Order.class);
                    var charge = provider.charge(order, exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                    if (delayResponses.remove(order.orderId())) Thread.sleep(1500);
                    byte[] body = json.writeValueAsBytes(charge);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length);
                    exchange.getResponseBody().write(body);
                } catch (org.springframework.web.server.ResponseStatusException e) { exchange.sendResponseHeaders(e.getStatusCode().value(),-1); }
                catch (Exception e) { exchange.sendResponseHeaders(500,-1); }
                finally { exchange.close(); }
            });
            server.createContext("/charges/reconcile",exchange->{
                try {
                    String query=exchange.getRequestURI().getRawQuery();
                    if (query==null || !query.startsWith("key=")) throw new IllegalArgumentException("Missing key");
                    byte[] body=json.writeValueAsBytes(provider.reconcile(URLDecoder.decode(query.substring(4),java.nio.charset.StandardCharsets.UTF_8)));
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
                } catch (org.springframework.web.server.ResponseStatusException e) { exchange.sendResponseHeaders(e.getStatusCode().value(),-1); }
                catch (Exception e) { exchange.sendResponseHeaders(500,-1); }
                finally { exchange.close(); }
            });
            server.start();
            var requests = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
            requests.setReadTimeout(Duration.ofSeconds(15));
            http = RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).requestFactory(requests).build();
        }
        private Message receive(String queue) {
            var messages=sqs.receiveMessage(r -> r.queueUrl(queue).waitTimeSeconds(2).maxNumberOfMessages(1)).messages();
            if (messages.size()!=1) throw new IllegalStateException("Expected a message delivery");
            return messages.getFirst();
        }
        private PaymentProcessor processor(String queue, boolean idempotent, Runnable hook) {
            return new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",idempotent,hook);
        }
        private boolean candidateMode;
        Result runCandidate(Set<String> factors,int repetition) throws Exception {
            candidateMode=true;
            try { return run(factors,repetition); } finally { candidateMode=false; }
        }
        @Override public Result run(Set<String> factors,int repetition) throws Exception {
            int mask=mask(factors);
            String queue=sqs.createQueue(r -> r.queueName(prefix+"-"+mask+"-"+repetition)
                    .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT,"120"))).queueUrl();
            String orderId=UUID.randomUUID().toString();
            Order order=new Order(orderId,original.customerId(),original.amount());
            boolean crash=factors.contains(FACTORS.get(0)), redeliver=factors.contains(FACTORS.get(1)), idempotent=!factors.contains(FACTORS.get(2));
            try {
                db.putItem(r -> r.tableName(prefix+"-orders").item(Map.of("orderId",AttributeValue.builder().s(orderId).build())));
                String body=json.writeValueAsString(order);
                sqs.sendMessage(r -> r.queueUrl(queue).messageBody(body));
                Message first=receive(queue);
                boolean crashed=false;
                try { new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",idempotent,
                        () -> { if (crash) throw new InjectedCrash(); },candidateMode).process(first); }
                catch (InjectedCrash expected) { crashed=true; }
                if (crashed!=crash) throw new IllegalStateException("Crash intervention did not execute as requested");
                List<String> messageIds=new ArrayList<>(List.of(first.messageId()));
                if (redeliver) {
                    if (crash) sqs.changeMessageVisibility(r -> r.queueUrl(queue).receiptHandle(first.receiptHandle()).visibilityTimeout(0));
                    else sqs.sendMessage(r -> r.queueUrl(queue).messageBody(body));
                    Message second=receive(queue);
                    if (crash && !first.messageId().equals(second.messageId())) throw new IllegalStateException("Unexpected redelivery identity");
                    messageIds.add(second.messageId());
                    new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",idempotent,() -> {},candidateMode).process(second);
                }
                int orders=db.getItem(r -> r.tableName(prefix+"-orders").key(Map.of("orderId",AttributeValue.builder().s(orderId).build())).consistentRead(true)).hasItem()?1:0;
                var charges=provider.effects(orderId);
                boolean saved=db.getItem(r -> r.tableName(prefix+"-payments").key(Map.of("orderId",AttributeValue.builder().s(orderId).build())).consistentRead(true)).hasItem();
                return new Result(Observation.duplicatePayment(orders,charges.size()),Map.of(
                        "sandboxOrderId",orderId,"originalOrderId",original.orderId(),"sampledAt",Instant.now().toString(),
                        "messageIds",messageIds,"chargeIds",charges.stream().map(ProviderApplication.Charge::chargeId).toList(),
                        "ordersCreated",orders,"paymentEffects",charges.size(),"paymentResultSaved",saved,"crashInjected",crashed));
            } finally { sqs.deleteQueue(r -> r.queueUrl(queue)); }
        }
        Check verifyBatch(String name,int orderCount,int copies,int crashes,boolean timeout,boolean ledgerFailure,
                          boolean delayedAck,boolean concurrent,Runnable budget) throws Exception {
            budget.run();
            long start=System.nanoTime();
            String queue=sqs.createQueue(r->r.queueName(prefix+"-verify-"+name.toLowerCase(Locale.ROOT).replace('_','-'))
                    .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT,"120"))).queueUrl();
            List<Order> orders=new ArrayList<>();
            int injected=0;
            try {
                for (int i=0;i<orderCount;i++) {
                    Order order=new Order(UUID.randomUUID().toString(),original.customerId(),original.amount()); orders.add(order);
                    for (int copy=0;copy<copies;copy++) sqs.sendMessage(r->r.queueUrl(queue).messageBody(encodeOrder(order)));
                }
                var requests=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
                requests.setReadTimeout(Duration.ofMillis(500));
                RestClient shortTimeout=RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).requestFactory(requests).build();
                if (timeout) delayResponses.add(orders.getFirst().orderId());
                List<Future<?>> tasks=new ArrayList<>();
                int deliveries=orderCount*copies;
                for (int i=0;i<deliveries;i++) {
                    budget.run();
                    Message message=receive(queue);
                    if (concurrent) {
                        Message concurrentMessage=message;
                        tasks.add(executor.submit(()->{
                            try { new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",true,()->{},true).process(concurrentMessage); }
                            catch (Exception e) { throw new CompletionException(e); }
                        }));
                        continue;
                    }
                    for (int crash=0;crash<crashes;crash++) {
                        try { new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",true,()->{throw new InjectedCrash();},true).process(message); }
                        catch (InjectedCrash expected) { injected++; }
                        Message held=message;
                        sqs.changeMessageVisibility(r->r.queueUrl(queue).receiptHandle(held.receiptHandle()).visibilityTimeout(0));
                        message=receive(queue);
                    }
                    if (timeout || ledgerFailure) {
                        try {
                            new PaymentProcessor(sqs,db,timeout?shortTimeout:http,json,queue,
                                    ledgerFailure?prefix+"-unavailable":prefix+"-payments",true,()->{},true).process(message);
                            throw new IllegalStateException("Expected dependency fault was not observed");
                        } catch (org.springframework.web.client.ResourceAccessException e) {
                            if (!timeout || delayResponses.contains(orders.getFirst().orderId())
                                    || provider.effects(orders.getFirst().orderId()).size()!=1) throw e;
                            injected++;
                        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
                            if (!ledgerFailure) throw e; injected++;
                        }
                        Message held=message;
                        sqs.changeMessageVisibility(r->r.queueUrl(queue).receiptHandle(held.receiptHandle()).visibilityTimeout(0));
                        message=receive(queue);
                    }
                    if (delayedAck) {
                        Message held=message;
                        // Second consumer runs after visibility expires while the first consumer is paused before ACK.
                        new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",true,()->{
                            sqs.changeMessageVisibility(r->r.queueUrl(queue).receiptHandle(held.receiptHandle()).visibilityTimeout(0));
                            try { new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",true,()->{},true).process(receive(queue)); }
                            catch (Exception e) { throw new CompletionException(e); }
                        },true).process(message);
                        injected++;
                    } else new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",true,()->{},true).process(message);
                }
                for (Future<?> task:tasks) { budget.run(); task.get(45,TimeUnit.SECONDS); }
                int effects=0, saved=0;
                for (Order order:orders) {
                    var charges=provider.effects(order.orderId()); effects+=charges.size();
                    var record=db.getItem(r->r.tableName(prefix+"-payments").key(Map.of("orderId",AttributeValue.builder().s(order.orderId()).build())).consistentRead(true)).item();
                    if (charges.size()==1 && record.containsKey("chargeId") && record.get("chargeId").s().equals(charges.getFirst().chargeId())) saved++;
                }
                var attrs=sqs.getQueueAttributes(r->r.queueUrl(queue).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
                int remaining=Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                        +Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
                long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                boolean passed=effects==orderCount && saved==orderCount && remaining==0 && elapsed<120000
                        && injected==(crashes+(timeout?1:0)+(ledgerFailure?1:0)+(delayedAck?1:0))*deliveries;
                return new Check(name,passed,Map.of("orders",orderCount,"deliveries",deliveries,"effects",effects,"savedPayments",saved,
                        "remainingMessages",remaining,"injectedFaults",injected,"durationMs",elapsed,"candidateMode",true),
                        "Require exactly one effect and matching persisted result per order, drained queue, exercised faults and completion within 120s; local bounded workload");
            } finally { sqs.deleteQueue(r->r.queueUrl(queue)); }
        }
        String encodeOrder(Order order) {
            try { return json.writeValueAsString(order); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        Check certificationCase(String name) throws Exception {
            String queue=sqs.createQueue(r->r.queueName(prefix+"-cert-"+name.toLowerCase(Locale.ROOT).replace('_','-'))).queueUrl();
            Order order=new Order(UUID.randomUUID().toString(),original.customerId(),original.amount());
            var key=Map.of("orderId",AttributeValue.builder().s(order.orderId()).build());
            boolean crash=Set.of("PROVIDER_RESTART","KEY_EXPIRY","RETENTION_BOUNDARY").contains(name);
            boolean intervention=false;
            try {
                sqs.sendMessage(r->r.queueUrl(queue).messageBody(encodeOrder(order)));
                Message first=receive(queue);
                try { new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",true,()->{if(crash) throw new InjectedCrash();},true).process(first); }
                catch (InjectedCrash expected) { intervention=true; }
                if (crash) sqs.changeMessageVisibility(r->r.queueUrl(queue).receiptHandle(first.receiptHandle()).visibilityTimeout(0));
                else sqs.sendMessage(r->r.queueUrl(queue).messageBody(encodeOrder(order)));
                Message retry=receive(queue);
                if (name.equals("PROVIDER_RESTART")) provider=new ProviderApplication(journal,clock,Duration.ofHours(24));
                if (name.equals("KEY_EXPIRY") || name.equals("RETENTION_BOUNDARY") || name.equals("LEDGER_RETENTION")) {
                    if (name.equals("LEDGER_RETENTION")) { db.deleteItem(r->r.tableName(prefix+"-payments").key(key)); intervention=true; }
                    clock.advance(name.equals("RETENTION_BOUNDARY")?Duration.ofHours(24):Duration.ofHours(25));
                    // An expired key must refuse a new charge; reconciliation remains authoritative.
                    boolean expired=false;
                    try { provider.charge(order,"payment:"+order.orderId()); }
                    catch (org.springframework.web.server.ResponseStatusException e) { expired=e.getStatusCode().value()==409; }
                    intervention &= expired;
                }
                if (name.equals("LEDGER_CONFLICT") || name.equals("MIGRATION_COMPATIBILITY")) {
                    var good=db.getItem(r->r.tableName(prefix+"-payments").key(key).consistentRead(true)).item();
                    Map<String,AttributeValue> bad=new HashMap<>(good);
                    if (name.equals("LEDGER_CONFLICT")) bad.put("customerId",AttributeValue.builder().s("different-customer").build());
                    else { bad.remove("customerId"); bad.remove("amount"); }
                    db.putItem(r->r.tableName(prefix+"-payments").item(bad));
                    try { new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",true,()->{},true).process(retry); }
                    catch (IllegalStateException expected) { intervention=provider.effects(order.orderId()).size()==1; }
                    // Model an operator backfill from the known authoritative result, never guess missing intent.
                    db.putItem(r->r.tableName(prefix+"-payments").item(good));
                    Message heldRetry=retry;
                    sqs.changeMessageVisibility(r->r.queueUrl(queue).receiptHandle(heldRetry.receiptHandle()).visibilityTimeout(0));
                    retry=receive(queue);
                }
                boolean candidate=!name.equals("ROLLBACK_COMPATIBILITY");
                if (!candidate) intervention=true; // rollback preserves provider keys; disabling keys is forbidden
                new PaymentProcessor(sqs,db,http,json,queue,prefix+"-payments",true,()->{},candidate).process(retry);
                var charges=provider.effects(order.orderId());
                var saved=db.getItem(r->r.tableName(prefix+"-payments").key(key).consistentRead(true)).item();
                var attrs=sqs.getQueueAttributes(r->r.queueUrl(queue).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
                int pending=attrs.values().stream().mapToInt(Integer::parseInt).sum();
                boolean pass=intervention && charges.size()==1 && saved.containsKey("chargeId") && saved.get("chargeId").s().equals(charges.getFirst().chargeId()) && pending==0;
                return new Check(name,pass,Map.of("effects",charges.size(),"savedPayments",saved.isEmpty()?0:1,"remainingMessages",pending,
                        "interventionObserved",intervention,"providerJournalDurable",journal!=null),
                        "Scoped durable-provider/reconciliation contract; legacy/conflicting records require authoritative backfill; rollback retains provider keys within retention");
            } finally { sqs.deleteQueue(r->r.queueUrl(queue)); }
        }
        @Override public void close() {
            if (server!=null) server.stop(0);
            executor.close();
            remove(prefix);
            if (journal!=null) {
                try { Files.deleteIfExists(journal.resolveSibling("charges.json.tmp")); Files.deleteIfExists(journal); Files.deleteIfExists(journal.getParent()); }
                catch (Exception e) { throw new IllegalStateException("Provider journal cleanup failed",e); }
            }
        }
    }
}
