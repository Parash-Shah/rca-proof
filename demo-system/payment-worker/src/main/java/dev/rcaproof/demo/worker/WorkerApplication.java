package dev.rcaproof.demo.worker;

import dev.rcaproof.demo.contract.LabAwsConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@SpringBootApplication
@Import(LabAwsConfiguration.class)
public class WorkerApplication {
    public static void main(String[] args) { SpringApplication.run(WorkerApplication.class, args); }
    @Bean ApplicationRunner worker(SqsClient sqs, DynamoDbClient db, ObjectMapper json,
            @Value("${lab.queue-name:payment-orders}") String queue,
            @Value("${lab.payments-table:payments}") String table,
            @Value("${lab.provider-url:http://localhost:8082}") String providerUrl,
            @Value("${lab.provider-idempotency:false}") boolean idempotency,
            @Value("${lab.crash-after-charge:false}") boolean crash) {
        URI provider = URI.create(providerUrl);
        if (!"http".equals(provider.getScheme()) || !java.util.Set.of("localhost", "127.0.0.1", "external-payment-mock").contains(provider.getHost()))
            throw new IllegalArgumentException("Provider must be a local failure-lab mock");
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return args -> {
            String url = sqs.getQueueUrl(r -> r.queueName(queue)).queueUrl();
            var processor = new PaymentProcessor(sqs, db, RestClient.builder().baseUrl(providerUrl).requestFactory(factory).build(),
                    json, url, table, idempotency, () -> {
                if (crash) {
                    LoggerFactory.getLogger(WorkerApplication.class).error("event=INJECTED_PROCESS_KILL point=AFTER_PAYMENT_BEFORE_ACK");
                    Runtime.getRuntime().halt(86);
                }
            });
            while (!Thread.currentThread().isInterrupted()) {
                var messages = sqs.receiveMessage(r -> r.queueUrl(url).waitTimeSeconds(10).maxNumberOfMessages(1)).messages();
                for (var message : messages) {
                    try { processor.process(message); }
                    catch (Exception e) { LoggerFactory.getLogger(WorkerApplication.class).error("Processing failed; message remains unacknowledged", e); }
                }
            }
        };
    }
}
