package dev.rcaproof.demo.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.rcaproof.demo.contract.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryClientTest {
    @Test void retriesUseIdenticalEventAndPermanentFailureDoesNotBreakPayment() throws Exception {
        var json=new ObjectMapper();
        var bodies=new CopyOnWriteArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/telemetry/events",exchange->{
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(bodies.size()==1?503:202,-1); exchange.close();
        });
        server.start();
        try {
            var client=new TelemetryClient("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/telemetry/events",json);
            var order=new Order("order","customer",BigDecimal.ONE);
            client.emit(order,"worker","PAYMENT_SUCCEEDED","message",Map.of("chargeId","charge"));
            assertEquals(2,bodies.size()); assertEquals(bodies.get(0),bodies.get(1));
            var payload=json.readTree(bodies.getFirst());
            assertEquals("charge",payload.at("/event/metadata/chargeId").asText());
            assertDoesNotThrow(()->UUID.fromString(payload.at("/event/eventId").asText()));
            server.removeContext("/v1/telemetry/events");
            server.createContext("/v1/telemetry/events",exchange->{bodies.add("rejected"); exchange.sendResponseHeaders(409,-1); exchange.close();});
            assertDoesNotThrow(()->client.emit(order,"worker","MESSAGE_RECEIVED","message",Map.of()));
            assertEquals(3,bodies.size());
        } finally { server.stop(0); }
    }
    @Test void disabledTelemetryIsNoopAndExternalEndpointsAreRejected() {
        assertDoesNotThrow(()->new TelemetryClient("",new ObjectMapper()).emit(null,null,null,null,null));
        for (String url:List.of("https://example.com/v1/telemetry/events","http://localhost/other","http://user@localhost/v1/telemetry/events"))
            assertThrows(IllegalArgumentException.class,()->new TelemetryClient(url,new ObjectMapper()));
    }
}
