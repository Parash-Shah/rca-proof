package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rcaproof.demo.contract.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static dev.rcaproof.api.IncidentModels.*;
import static org.springframework.http.HttpStatus.*;

/** Accepted telemetry is durable; incident evidence is frozen when queued. */
@Service
public class TelemetryStore {
    public record Submission(@Valid Order order, @NotNull @Valid Evidence event) {}
    public record View(String streamKey, int eventCount, UUID incidentId, String status) {}
    private record Stream(String key, String signature, Order order, Instant detected, UUID incident) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final IncidentStore incidents;
    public TelemetryStore(JdbcTemplate db, ObjectMapper json, IncidentStore incidents) {
        this.db=db; this.json=json; this.incidents=incidents;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode telemetry",e); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value,type); }
        catch (Exception e) { throw new IllegalStateException("Cannot decode telemetry",e); }
    }
    private Stream stream(String key, boolean lock) {
        return db.query("SELECT * FROM telemetry_streams WHERE stream_key=?"+(lock?" FOR UPDATE":""), (r,n)-> {
            var detected=r.getTimestamp("detected_at");
            return new Stream(key,r.getString("signature"),decode(r.getString("order_snapshot"),Order.class),
                    detected==null?null:detected.toInstant(),r.getObject("incident_id",UUID.class));
        },key).stream().findFirst().orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Telemetry stream not found"));
    }
    private List<TimelineEvent> events(String key) {
        return db.query("SELECT * FROM telemetry_events WHERE stream_key=? ORDER BY sequence",
                (r,n)->new TimelineEvent(r.getLong("sequence"),r.getTimestamp("received_at").toInstant(),decode(r.getString("payload"),Evidence.class)),key);
    }
    @Transactional public View accept(Submission submission) {
        Evidence e=submission.event();
        boolean retry=e.eventType()==EventType.HTTP_DEPENDENCY_ATTEMPT;
        String identity=retry?e.requestId():e.orderId();
        if (identity==null || identity.isBlank() || identity.length()>200)
            throw new ResponseStatusException(BAD_REQUEST,"A bounded correlation ID is required");
        if (retry ? submission.order()!=null : submission.order()==null || !identity.equals(submission.order().orderId()))
            throw new ResponseStatusException(BAD_REQUEST,"Order snapshot must match payment telemetry; HTTP telemetry has no order");
        if (!retry && !Set.of(EventType.ORDER_CREATED,EventType.PAYMENT_SUCCEEDED,EventType.WORKER_CRASHED,
                EventType.MESSAGE_RECEIVED,EventType.MESSAGE_ACKNOWLEDGED,EventType.TELEMETRY_GAP).contains(e.eventType()))
            throw new ResponseStatusException(BAD_REQUEST,"Unsupported telemetry event");
        String key=(retry?"retry:":"payment:")+identity;
        db.update("INSERT INTO telemetry_streams(stream_key,signature,order_snapshot) VALUES(?,?,?::jsonb) ON CONFLICT DO NOTHING",
                key,retry?"RETRY_STORM":"DUPLICATE_PAYMENT",encode(submission.order()));
        Stream s=stream(key,true);
        if (!Objects.equals(s.order(),submission.order())) throw new ResponseStatusException(CONFLICT,"Correlation ID already has a different order");
        // Global ID uniqueness also protects against moving an event to another stream.
        int inserted=db.update("INSERT INTO telemetry_events(stream_key,event_id,payload) VALUES(?,?,?::jsonb) ON CONFLICT(event_id) DO NOTHING",key,e.eventId(),encode(e));
        if (inserted==0) {
            boolean identical=db.queryForObject("SELECT stream_key=? AND payload=?::jsonb FROM telemetry_events WHERE event_id=?",Boolean.class,key,encode(e),e.eventId());
            if (!identical) throw new ResponseStatusException(CONFLICT,"Event ID already contains different telemetry");
        }
        var timeline=events(key);
        if (timeline.size()>1000) throw new ResponseStatusException(BAD_REQUEST,"A telemetry stream supports at most 1000 events");
        boolean detected=retry ? timeline.stream().map(TimelineEvent::event).filter(x->"500".equals(x.result())).count()>=6
                : timeline.stream().map(TimelineEvent::event).filter(x->x.eventType()==EventType.PAYMENT_SUCCEEDED && "SUCCESS".equals(x.result()))
                .map(x->x.metadata().get("chargeId")).filter(x->x!=null&&!x.isBlank()).distinct().count()>=2;
        if (detected && s.detected()==null) db.update("UPDATE telemetry_streams SET detected_at=clock_timestamp() WHERE stream_key=?",key);
        return view(key);
    }
    public View view(String key) {
        Stream s=stream(key,false);
        return new View(key,db.queryForObject("SELECT count(*) FROM telemetry_events WHERE stream_key=?",Integer.class,key),s.incident(),
                s.incident()!=null?incidents.incident(s.incident()).status().name():s.detected()!=null?"DETECTED":"COLLECTING");
    }
    public List<String> pending() {
        return db.query("SELECT stream_key FROM telemetry_streams WHERE detected_at IS NOT NULL AND incident_id IS NULL ORDER BY detected_at LIMIT 20",(r,n)->r.getString(1));
    }
    @Transactional public View materialize(String key) {
        Stream s=stream(key,true);
        if (s.incident()!=null || s.detected()==null) return view(key);
        var timeline=events(key);
        String missing=s.signature().equals("RETRY_STORM")?ScenarioDefinition.retryEvidenceMissing(timeline):EvidenceAssessment.missing(timeline);
        // Allow delayed delivery, then let the normal investigation abstain on incomplete evidence.
        if (missing!=null && Instant.now().isBefore(s.detected().plusSeconds(30))) return view(key);
        Instant started=timeline.stream().map(t->t.event().timestamp()).min(Instant::compareTo).orElseThrow();
        var incident=incidents.create(new CreateIncident("Automatically detected "+s.signature(),started,s.signature(),s.order()));
        incidents.addEvidence(incident.id(),timeline.stream().map(TimelineEvent::event).toList());
        incidents.enqueue(incident.id(),2);
        db.update("UPDATE telemetry_streams SET incident_id=? WHERE stream_key=?",incident.id(),key);
        return view(key);
    }
}
