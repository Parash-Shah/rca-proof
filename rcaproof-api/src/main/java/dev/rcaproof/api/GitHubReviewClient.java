package dev.rcaproof.api;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Bounded GitHub transport. No force-push, merge, deployment, or caller-selected URL. */
@Component
public class GitHubReviewClient {
    record Pull(int number,String url,String state,boolean merged,String head,String base,String body) {}
    private final ObjectMapper json;
    private final String token;
    private final URI endpoint;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @org.springframework.beans.factory.annotation.Autowired
    public GitHubReviewClient(ObjectMapper json,@Value("${rcaproof.github.token:}") String token) {
        this(json,token,URI.create("https://api.github.com"));
    }
    // Package-private loopback override exists only for transport integration tests.
    GitHubReviewClient(ObjectMapper json,String token,URI endpoint) { this.json=json; this.token=token; this.endpoint=endpoint; }
    public boolean configured() { return token!=null && !token.isBlank(); }
    private JsonNode request(String method,String path,Object body,boolean missingAllowed) {
        if (!configured()) throw new IllegalStateException("GitHub credentials are not configured");
        try {
            var builder=HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(15))
                    .header("Authorization","Bearer "+token).header("Accept","application/vnd.github+json")
                    .header("X-GitHub-Api-Version","2026-03-10").header("User-Agent","RCAProof");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (body!=null) builder.header("Content-Type","application/json");
            var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if (missingAllowed && response.statusCode()==404) return null;
            if (response.statusCode()<200 || response.statusCode()>299)
                throw new IllegalStateException("GitHub returned HTTP "+response.statusCode()+"; reconcile and retry after resolving access, rate limits, or branch conflicts");
            return json.readTree(response.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("GitHub request interrupted"); }
        catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("GitHub response unavailable; retry reconciles the deterministic branch and PR"); }
    }
    static String segment(String value) { return URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20"); }
    private String root(String repo) {
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid configured GitHub repository");
        return "/repos/"+repo;
    }
    String ref(String repo,String branch) {
        var response=request("GET",root(repo)+"/git/ref/heads/"+segment(branch),null,true);
        return response==null?null:response.path("object").path("sha").asText();
    }
    void validateSubject(String repo,String baseBranch,BuildProvenance.Source source) {
        if (!source.baseCommit().equals(ref(repo,baseBranch))) throw new IllegalStateException("Target branch changed or already contains the certified patch; rebase and recertify");
        var commit=request("GET",root(repo)+"/git/commits/"+source.commit(),null,false);
        if (!source.tree().equals(commit.path("tree").path("sha").asText())) throw new IllegalStateException("Remote commit tree differs from certified source");
        var comparison=request("GET",root(repo)+"/compare/"+source.baseCommit()+"..."+source.commit(),null,false);
        if (!"ahead".equals(comparison.path("status").asText()) || comparison.path("ahead_by").asInt()<1
                || !source.baseCommit().equals(comparison.path("merge_base_commit").path("sha").asText()))
            throw new IllegalStateException("Certified commit must descend from the exact target base");
    }
    void ensureBranch(String repo,String branch,String sha) {
        var existing=ref(repo,branch);
        if (existing==null) {
            try { request("POST",root(repo)+"/git/refs",Map.of("ref","refs/heads/"+branch,"sha",sha),false); }
            catch (IllegalStateException e) { if (!sha.equals(ref(repo,branch))) throw e; }
        }
        if (!sha.equals(ref(repo,branch))) throw new IllegalStateException("Review branch differs from certified commit; no branch was overwritten");
    }
    Pull find(String repo,String branch,String base) {
        var rows=request("GET",root(repo)+"/pulls?state=all&head="+segment(repo.split("/")[0]+":"+branch)+"&base="+segment(base)+"&per_page=100",null,false);
        if (!rows.isArray() || rows.size()>1) throw new IllegalStateException("Ambiguous review history requires human reconciliation");
        return rows.isEmpty()?null:pull(repo,rows.get(0).path("number").asInt());
    }
    Pull pull(String repo,int number) { return parse(request("GET",root(repo)+"/pulls/"+number,null,false)); }
    Pull create(String repo,String branch,String base,String title,String body) {
        return parse(request("POST",root(repo)+"/pulls",Map.of("head",branch,"base",base,"title",title,"body",body,"draft",true,"maintainer_can_modify",false),false));
    }
    private Pull parse(JsonNode node) {
        return new Pull(node.path("number").asInt(),node.path("html_url").asText(),node.path("state").asText(),node.path("merged").asBoolean(),
                node.path("head").path("sha").asText(),node.path("base").path("sha").asText(),node.path("body").asText());
    }
}
