package dev.rcaproof.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import dev.rcaproof.experiment.ScenarioLab;
import java.util.*;

/** Durable counts: no request IDs as labels, no invented accuracy or timing measurements. */
@RestController
@RequestMapping("/v1")
public class OperationsController {
    private final JdbcTemplate db;
    private final IncidentStore incidents;
    public OperationsController(JdbcTemplate db,IncidentStore incidents) { this.db=db; this.incidents=incidents; }
    @GetMapping("/scenarios") public Map<String,Object> scenarios() {
        return Map.of("behavioralModels",ScenarioLab.definitions().stream().filter(d->!d.signature().equals("RETRY_STORM")).toList(),"behavioralScope",ScenarioLab.SCOPE,
                "httpRetryLab",Map.of("signature","RETRY_STORM","version",dev.rcaproof.experiment.RetryContract.VERSION,"scope",dev.rcaproof.experiment.RetryContract.SCOPE,"factors",dev.rcaproof.experiment.RetryContract.FACTORS),
                "paymentLab",Map.of("signature","DUPLICATE_PAYMENT","version",DuplicatePaymentSandbox.VERSION,"scope",DuplicatePaymentSandbox.SCOPE,"factors",DuplicatePaymentSandbox.FACTORS),
                "abstention",Map.of("signature","INTERMITTENT_TIMEOUT","result","INCONCLUSIVE without distinguishing telemetry"));
    }
    @GetMapping("/overview") public Map<String,Object> overview() {
        return Map.of("outcomes",db.queryForList("SELECT status, count(*) AS count FROM incidents GROUP BY status ORDER BY status"),
                "incidents",db.queryForList("SELECT id,title,status,failure_signature,created_at FROM incidents ORDER BY created_at DESC LIMIT 50"),
                "verifications",db.queryForList("SELECT id,incident_id,candidate_id,status,created_at FROM verification_runs ORDER BY created_at DESC LIMIT 20"),
                "reviews",db.queryForList("SELECT id,verification_id,status,pull_url,updated_at FROM change_requests ORDER BY updated_at DESC LIMIT 20"));
    }
    @GetMapping("/incidents/{id}/hypotheses") public Map<String,Object> hypotheses(@PathVariable UUID id) {
        var incident=incidents.incident(id); var scenario=ScenarioDefinition.forIncident(incident);
        // Completed proof is the evidence source; an unrun hypothesis never receives an evidence state.
        var proof=incidents.proof(id);
        var hypotheses=scenario.factors().stream().map(factor->{
            var included=proof.report().trials().stream().filter(t->t.factors().contains(factor)).toList();
            var removed=proof.report().trials().stream().filter(t->!t.factors().contains(factor)).toList();
            return Map.of("factor",factor,"status",proof.report().minimalCausalSets().stream().anyMatch(s->s.contains(factor))?"SUPPORTED_AS_SET_MEMBER":"INCONCLUSIVE",
                    "injectionTrials",included,"removalTrials",removed,"scope",scenario.scope());
        }).toList();
        return Map.of("incidentId",id,"hypotheses",hypotheses,"minimalCausalSets",proof.report().minimalCausalSets(),"proofState",proof.report().state());
    }
    @GetMapping(value="/metrics",produces="text/plain; version=0.0.4; charset=utf-8") public String metrics() {
        StringBuilder out=new StringBuilder();
        metric(out,"rcaproof_incidents_total","Persisted incidents",count("SELECT count(*) FROM incidents"));
        metric(out,"rcaproof_incidents_proven","Persisted scoped proven incidents",count("SELECT count(*) FROM incidents WHERE status='PROVEN'"));
        metric(out,"rcaproof_incidents_inconclusive","Persisted inconclusive incidents",count("SELECT count(*) FROM incidents WHERE status='INCONCLUSIVE'"));
        metric(out,"rcaproof_experiments_total","Persisted experiment attempts including abandoned attempts",count("SELECT count(*) FROM experiment_runs"));
        metric(out,"rcaproof_remediations_generated","Persisted verification candidates",count("SELECT count(*) FROM verification_runs"));
        metric(out,"rcaproof_remediations_rejected","Failed verification runs",count("SELECT count(*) FROM verification_runs WHERE status='FAILED'"));
        metric(out,"rcaproof_certificates_issued_total","Historical certificates issued; not a count of currently valid certificates",count("SELECT count(*) FROM remediation_certificates"));
        metric(out,"rcaproof_reviews_total","Persisted review records",count("SELECT count(*) FROM change_requests"));
        for (var row:db.queryForList("SELECT payload->'decision'->>'category' AS category,count(*) AS n FROM classifications GROUP BY category"))
            out.append("rcaproof_classifications{category=\"").append(row.get("category")).append("\"} ").append(row.get("n")).append('\n');
        durations(out,"rcaproof_experiment_duration", "experiment_runs", "started_at", "completed_at");
        durations(out,"rcaproof_rca_duration", "investigations", "created_at", "completed_at");
        return out.toString();
    }
    private long count(String sql) { return db.queryForObject(sql,Long.class); }
    private void metric(StringBuilder out,String name,String help,long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(" gauge\n").append(name).append(' ').append(value).append('\n');
    }
    private void durations(StringBuilder out,String name,String table,String start,String end) {
        // Identifiers are private compile-time constants, never request parameters.
        var row=db.queryForMap("SELECT count(*) AS n,coalesce(sum(extract(epoch FROM ("+end+"-"+start+"))),0) AS seconds FROM "+table+" WHERE "+end+" IS NOT NULL");
        out.append("# TYPE ").append(name).append("_seconds summary\n").append(name).append("_seconds_count ").append(row.get("n")).append('\n')
                .append(name).append("_seconds_sum ").append(row.get("seconds")).append('\n');
    }
}
