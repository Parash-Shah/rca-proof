package dev.rcaproof.demo.order;

import dev.rcaproof.demo.contract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;

@SpringBootApplication
@Import(LabAwsConfiguration.class)
@RestController
public class OrderApplication {
    private final SqsClient sqs;
    private final DynamoDbClient db;
    private final ObjectMapper json;
    private final String queue;
    private final String table;
    public OrderApplication(SqsClient sqs, DynamoDbClient db, ObjectMapper json,
                            @Value("${lab.queue-name:payment-orders}") String queue,
                            @Value("${lab.orders-table:orders}") String table) {
        this.sqs = sqs; this.db = db; this.json = json; this.queue = queue; this.table = table;
    }
    public static void main(String[] args) { SpringApplication.run(OrderApplication.class, args); }

    @PostMapping("/orders") @ResponseStatus(HttpStatus.CREATED)
    public Order create(@Valid @RequestBody Order order) throws Exception {
        String body = json.writeValueAsString(order);
        try {
            db.putItem(r -> r.tableName(table).item(Map.of("orderId", s(order.orderId()), "body", s(body)))
                    .conditionExpression("attribute_not_exists(orderId)"));
        } catch (ConditionalCheckFailedException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order ID already exists");
        }
        String url = sqs.getQueueUrl(r -> r.queueName(queue)).queueUrl();
        sqs.sendMessage(r -> r.queueUrl(url).messageBody(body));
        return order;
    }
    @GetMapping("/orders/{id}")
    public Order get(@PathVariable String id) throws Exception {
        var item = db.getItem(r -> r.tableName(table).key(Map.of("orderId", s(id))).consistentRead(true)).item();
        if (item.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return json.readValue(item.get("body").s(), Order.class);
    }
    private static AttributeValue s(String value) { return AttributeValue.builder().s(value).build(); }
}
