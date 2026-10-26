package dev.rcaproof.demo.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Best-effort local lab sender. Retries reuse an ID; failure never changes payment behavior. */
public final class TelemetryClient {
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient client;
    public TelemetryClient(String url,ObjectMapper json) {
        this.json=json;
        endpoint=url==null || url.isBlank()?null:URI.create(url);
        if (endpoint!=null && (!"http".equals(endpoint.getScheme())
                || !Set.of("localhost","127.0.0.1","rcaproof-api").contains(endpoint.getHost())
                || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || !"/v1/telemetry/events".equals(endpoint.getPath())))
            throw new IllegalArgumentException("Telemetry must target the local lab event inbox");
        client=endpoint==null?null:HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public void emit(Order order,String service,String type,String messageId,Map<String,String> metadata) {
        if (endpoint==null) return;
        try {
            Map<String,Object> event=new LinkedHashMap<>();
            event.put("eventId",UUID.randomUUID().toString()); event.put("timestamp",Instant.now().toString());
            event.put("service",service); event.put("eventType",type); event.put("orderId",order.orderId());
            event.put("messageId",messageId); event.put("result","SUCCESS"); event.put("metadata",metadata);
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("order",order,"event",event)))).build();
            for (int attempt=0;attempt<2;attempt++) {
                try {
                    int status=client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (status>=200 && status<300) return;
                    if (status>=400 && status<500) break;
                } catch (java.io.IOException e) { /* Same event ID on retry. */ }
            }
            LoggerFactory.getLogger(getClass()).warn("Telemetry delivery failed for order {} event {}; evidence may be incomplete",order.orderId(),type);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { LoggerFactory.getLogger(getClass()).warn("Telemetry unavailable; evidence may be incomplete",e); }
    }
}
