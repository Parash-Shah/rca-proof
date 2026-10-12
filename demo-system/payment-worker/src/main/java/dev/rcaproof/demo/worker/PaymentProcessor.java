package dev.rcaproof.demo.worker;

import dev.rcaproof.demo.contract.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deliberately vulnerable when providerIdempotency is false. Local failure lab only. */
public final class PaymentProcessor {
    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);
    private final SqsClient sqs;
    private final DynamoDbClient db;
    private final RestClient provider;
    private final ObjectMapper json;
    private final String queueUrl;
    private final String paymentsTable;
    private final boolean providerIdempotency;
    private final Runnable afterCharge;
    private final boolean conditionalLedger;
    public PaymentProcessor(SqsClient sqs, DynamoDbClient db, RestClient provider, ObjectMapper json,
                            String queueUrl, String paymentsTable, boolean providerIdempotency, Runnable afterCharge) {
        this(sqs,db,provider,json,queueUrl,paymentsTable,providerIdempotency,afterCharge,false);
    }
    /** Candidate mode is selected only by the verification sandbox; the vulnerable lab default is preserved. */
    public PaymentProcessor(SqsClient sqs, DynamoDbClient db, RestClient provider, ObjectMapper json,
                            String queueUrl, String paymentsTable, boolean providerIdempotency, Runnable afterCharge, boolean conditionalLedger) {
        this.sqs = sqs; this.db = db; this.provider = provider; this.json = json;
        this.queueUrl = queueUrl; this.paymentsTable = paymentsTable;
        this.providerIdempotency = providerIdempotency; this.afterCharge = afterCharge;
        this.conditionalLedger = conditionalLedger;
        if (conditionalLedger && !providerIdempotency) throw new IllegalArgumentException("Ledger candidate requires provider idempotency");
    }
    public void process(Message message) throws Exception {
        Order order = json.readValue(message.body(), Order.class);
        log.info("event=WORKER_RECEIVED orderId={} messageId={}", order.orderId(), message.messageId());
        if (conditionalLedger) {
            var saved=db.getItem(r->r.tableName(paymentsTable).key(Map.of("orderId",AttributeValue.builder().s(order.orderId()).build())).consistentRead(true)).item();
            if (!saved.isEmpty()) {
                if (!saved.containsKey("chargeId") || !saved.containsKey("customerId") || !saved.containsKey("amount")
                        || !order.customerId().equals(saved.get("customerId").s())
                        || order.amount().compareTo(new java.math.BigDecimal(saved.get("amount").n()))!=0)
                    throw new IllegalStateException("Conflicting or legacy ledger record requires reconciliation before replay");
                sqs.deleteMessage(r->r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                return;
            }
        }
        var request = provider.post().uri("/charges").body(order);
        if (providerIdempotency) request.header("Idempotency-Key", "payment:" + order.orderId());
        Charge charge;
        try { charge=request.retrieve().body(Charge.class); }
        catch (org.springframework.web.client.HttpClientErrorException.Conflict conflict) {
            if (!conditionalLedger) throw conflict;
            var reconciled=provider.get().uri(builder->builder.path("/charges/reconcile").queryParam("key","payment:"+order.orderId()).build())
                    .retrieve().body(Reconciliation.class);
            if (reconciled==null || !order.orderId().equals(reconciled.orderId()) || !order.customerId().equals(reconciled.customerId())
                    || reconciled.amount()==null || order.amount().compareTo(reconciled.amount())!=0)
                throw new IllegalStateException("Provider reconciliation does not match payment intent");
            charge=new Charge(reconciled.chargeId(),reconciled.orderId());
        }
        if (charge == null || charge.chargeId() == null) throw new IllegalStateException("Missing provider charge result");
        log.info("event=PROVIDER_CHARGED orderId={} messageId={} chargeId={}", order.orderId(), message.messageId(), charge.chargeId());
        afterCharge.run(); // Fault boundary: external effect committed, no local record, no SQS ACK.
        var item=Map.of(
                "orderId", AttributeValue.builder().s(order.orderId()).build(),
                "chargeId", AttributeValue.builder().s(charge.chargeId()).build(),
                "idempotencyKey", AttributeValue.builder().s("payment:"+order.orderId()).build(),
                "customerId", AttributeValue.builder().s(order.customerId()).build(),
                "amount", AttributeValue.builder().n(order.amount().toPlainString()).build());
        db.putItem(r -> {
            r.tableName(paymentsTable).item(item);
            if (conditionalLedger) r.conditionExpression("attribute_not_exists(orderId) OR (chargeId = :charge AND customerId = :customer AND amount = :amount)")
                    .expressionAttributeValues(Map.of(":charge",item.get("chargeId"),":customer",item.get("customerId"),":amount",item.get("amount")));
        });
        sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        log.info("event=MESSAGE_ACKNOWLEDGED orderId={} messageId={}", order.orderId(), message.messageId());
    }
    public record Charge(String chargeId, String orderId) {}
    public record Reconciliation(String chargeId,String orderId,String customerId,java.math.BigDecimal amount) {}
}
