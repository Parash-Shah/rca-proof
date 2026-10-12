package dev.rcaproof.demo.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderApplicationTest {
    private SqsClient sqs;
    private DynamoDbClient db;
    private MockMvc api;
    @BeforeEach void setup() {
        sqs = mock(SqsClient.class); db = mock(DynamoDbClient.class);
        api = MockMvcBuilders.standaloneSetup(new OrderApplication(sqs, db, new ObjectMapper(), "orders", "orders")).build();
    }
    @Test void invalidAmountCannotCreateOrPublishAnOrder() throws Exception {
        api.perform(post("/orders").contentType("application/json")
                .content("{\"orderId\":\"one\",\"customerId\":\"customer\",\"amount\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sqs, db);
    }
    @Test void duplicateOrderIdDoesNotPublishASecondMessage() throws Exception {
        when(db.putItem(org.mockito.ArgumentMatchers.<Consumer<PutItemRequest.Builder>>any()))
                .thenThrow(ConditionalCheckFailedException.builder().message("Already exists").build());
        api.perform(post("/orders").contentType("application/json")
                .content("{\"orderId\":\"one\",\"customerId\":\"customer\",\"amount\":129.95}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(sqs);
    }
    @Test void missingOrderReturns404() throws Exception {
        when(db.getItem(org.mockito.ArgumentMatchers.<Consumer<GetItemRequest.Builder>>any()))
                .thenReturn(GetItemResponse.builder().build());
        api.perform(get("/orders/missing")).andExpect(status().isNotFound());
    }
}
