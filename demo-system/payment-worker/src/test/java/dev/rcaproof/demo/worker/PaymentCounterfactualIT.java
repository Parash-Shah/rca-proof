package dev.rcaproof.demo.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.rcaproof.causal.CounterfactualEngine;
import dev.rcaproof.demo.contract.Order;
import dev.rcaproof.experiment.Observation;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.*;

@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class PaymentCounterfactualIT {
    @Container static final LocalStackContainer aws = new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.7.0"))
            .withServices(SQS, DYNAMODB);
    static SqsClient sqs;
    static DynamoDbClient db;
    static HttpServer provider;
    static final ObjectMapper json = new ObjectMapper();
    static final Map<String, AtomicInteger> effects = new ConcurrentHashMap<>();
    static final Map<String, PaymentProcessor.Charge> keys = new ConcurrentHashMap<>();
    static RestClient http;
    static final String CRASH = "WORKER_CRASH", REDELIVERY = "MESSAGE_REDELIVERY", MISSING = "MISSING_IDEMPOTENCY";

    @BeforeAll static void setup() throws Exception {
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(aws.getAccessKey(), aws.getSecretKey()));
        sqs = SqsClient.builder().endpointOverride(aws.getEndpointOverride(SQS)).region(Region.US_EAST_1).credentialsProvider(creds).build();
        db = DynamoDbClient.builder().endpointOverride(aws.getEndpointOverride(DYNAMODB)).region(Region.US_EAST_1).credentialsProvider(creds).build();
        for (String table : List.of("orders", "payments")) {
            db.createTable(r -> r.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                    .keySchema(KeySchemaElement.builder().attributeName("orderId").keyType(KeyType.HASH).build())
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("orderId").attributeType(ScalarAttributeType.S).build()));
            db.waiter().waitUntilTableExists(r -> r.tableName(table));
        }
        provider = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        provider.createContext("/charges", exchange -> {
            Order order = json.readValue(exchange.getRequestBody(), Order.class);
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            PaymentProcessor.Charge charge;
            if (key == null) charge = newCharge(order);
            else charge = keys.computeIfAbsent(key, ignored -> newCharge(order));
            byte[] body = json.writeValueAsBytes(charge);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) { response.write(body); }
        });
        provider.start();
        http = RestClient.create("http://localhost:" + provider.getAddress().getPort());
    }
    static PaymentProcessor.Charge newCharge(Order order) {
        effects.computeIfAbsent(order.orderId(), ignored -> new AtomicInteger()).incrementAndGet();
        return new PaymentProcessor.Charge(UUID.randomUUID().toString(), order.orderId());
    }
    @AfterAll static void cleanup() {
        if (provider != null) provider.stop(0);
        if (sqs != null) sqs.close();
        if (db != null) db.close();
    }
    static final class InjectedCrash extends RuntimeException {}
    record Fixture(String queue, Order order, String body) {}
    Fixture fixture() throws Exception {
        String id = UUID.randomUUID().toString();
        String queue = sqs.createQueue(r -> r.queueName("experiment-" + id)).queueUrl();
        Order order = new Order(id, "synthetic-customer", new BigDecimal("129.95"));
        db.putItem(r -> r.tableName("orders").item(Map.of("orderId", AttributeValue.builder().s(id).build())));
        String body = json.writeValueAsString(order);
        sqs.sendMessage(r -> r.queueUrl(queue).messageBody(body));
        return new Fixture(queue, order, body);
    }
    Message receive(String queue) {
        var messages = sqs.receiveMessage(r -> r.queueUrl(queue).waitTimeSeconds(2).maxNumberOfMessages(1)).messages();
        if (messages.size() != 1) throw new IllegalStateException("Expected a delivered message");
        return messages.getFirst();
    }
    PaymentProcessor processor(Fixture f, boolean idempotent, Runnable hook) {
        return new PaymentProcessor(sqs, db, http, json, f.queue(), "payments", idempotent, hook);
    }
    Observation run(Set<String> factors, int repetition) throws Exception {
        Fixture f = fixture();
        try {
            Message first = receive(f.queue());
            boolean crash = factors.contains(CRASH), redeliver = factors.contains(REDELIVERY);
            var worker = processor(f, !factors.contains(MISSING), () -> { if (crash) throw new InjectedCrash(); });
            if (crash) assertThrows(InjectedCrash.class, () -> worker.process(first));
            else worker.process(first);
            if (redeliver) {
                if (crash) sqs.changeMessageVisibility(r -> r.queueUrl(f.queue()).receiptHandle(first.receiptHandle()).visibilityTimeout(0));
                else sqs.sendMessage(r -> r.queueUrl(f.queue()).messageBody(f.body()));
                Message second = receive(f.queue());
                if (crash) assertEquals(first.messageId(), second.messageId(), "Real SQS redelivery must retain message identity");
                processor(f, !factors.contains(MISSING), () -> {}).process(second);
            }
            var order = db.getItem(r -> r.tableName("orders").key(Map.of("orderId", AttributeValue.builder().s(f.order().orderId()).build())).consistentRead(true));
            return Observation.duplicatePayment(order.hasItem() ? 1 : 0, effects.get(f.order().orderId()).get());
        } finally { sqs.deleteQueue(r -> r.queueUrl(f.queue())); }
    }
    @Test void discoversMinimalCauseFromActualPaymentSideEffects() throws Exception {
        var report = new CounterfactualEngine().investigate(List.of(CRASH, REDELIVERY, MISSING), 10,
                "LocalStack SQS/DynamoDB + HTTP provider; controlled post-charge exception; up to two deliveries; duplicate-payment safety only", this::run);
        Path artifact = Path.of("target", "counterfactual-proof.json");
        Files.createDirectories(artifact.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(artifact.toFile(), report);
        assertEquals(CounterfactualEngine.ProofState.PROVEN, report.state());
        assertEquals(List.of(Set.of(REDELIVERY, MISSING)), report.minimalCausalSets());
        assertEquals(80, report.trials().size());
    }
    @Test void providerKeySurvivesCrashAndTenFurtherDeliveries() throws Exception {
        Fixture f = fixture();
        try {
            Message first = receive(f.queue());
            assertThrows(InjectedCrash.class, () -> processor(f, true, () -> { throw new InjectedCrash(); }).process(first));
            assertFalse(db.getItem(r -> r.tableName("payments").key(Map.of("orderId", AttributeValue.builder().s(f.order().orderId()).build())).consistentRead(true)).hasItem());
            sqs.changeMessageVisibility(r -> r.queueUrl(f.queue()).receiptHandle(first.receiptHandle()).visibilityTimeout(0));
            processor(f, true, () -> {}).process(receive(f.queue()));
            for (int i = 0; i < 10; i++) {
                sqs.sendMessage(r -> r.queueUrl(f.queue()).messageBody(f.body()));
                processor(f, true, () -> {}).process(receive(f.queue()));
            }
            assertEquals(1, effects.get(f.order().orderId()).get());
            assertTrue(db.getItem(r -> r.tableName("payments").key(Map.of("orderId", AttributeValue.builder().s(f.order().orderId()).build())).consistentRead(true)).hasItem());
            assertTrue(sqs.receiveMessage(r -> r.queueUrl(f.queue())).messages().isEmpty());
        } finally { sqs.deleteQueue(r -> r.queueUrl(f.queue())); }
    }
}
