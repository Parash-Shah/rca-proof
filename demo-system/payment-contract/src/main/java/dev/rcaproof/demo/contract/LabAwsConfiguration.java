package dev.rcaproof.demo.contract;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import java.net.URI;

@Configuration
public class LabAwsConfiguration {
    public static URI localEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || !java.util.Set.of("localhost", "127.0.0.1", "localstack").contains(uri.getHost()))
            throw new IllegalArgumentException("Failure lab only permits local HTTP AWS endpoints");
        return uri;
    }
    @Bean public SqsClient sqs(@Value("${lab.aws-endpoint:http://localhost:4566}") String endpoint) {
        return SqsClient.builder().endpointOverride(localEndpoint(endpoint)).region(Region.US_EAST_1)
                .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofSeconds(30)))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }
    @Bean public DynamoDbClient dynamo(@Value("${lab.aws-endpoint:http://localhost:4566}") String endpoint) {
        return DynamoDbClient.builder().endpointOverride(localEndpoint(endpoint)).region(Region.US_EAST_1)
                .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofSeconds(30)))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
    }
}
