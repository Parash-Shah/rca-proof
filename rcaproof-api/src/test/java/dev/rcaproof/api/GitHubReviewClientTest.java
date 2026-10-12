package dev.rcaproof.api;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GitHubReviewClientTest {
    final ObjectMapper json=new ObjectMapper();
    final String base="a".repeat(40),head="b".repeat(40),tree="c".repeat(40);
    HttpServer server; GitHubReviewClient client;
    Map<String,String> refs; AtomicInteger writes; boolean loseBranchReply; boolean wrongTree;
    @BeforeEach void start() throws Exception {
        refs=new HashMap<>(); refs.put("main",base); writes=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/repos/test/repo",exchange->{
            String path=exchange.getRequestURI().getPath(); Object result=Map.of(); int status=200;
            if (!"Bearer fixture".equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401,-1); exchange.close(); return; }
            if (exchange.getRequestMethod().equals("POST")) {
                writes.incrementAndGet(); var body=json.readTree(exchange.getRequestBody());
                if (path.endsWith("/git/refs")) {
                    refs.put(body.path("ref").asText().replace("refs/heads/",""),body.path("sha").asText());
                    status=loseBranchReply?500:201;
                } else {
                    assertTrue(body.path("draft").asBoolean()); assertFalse(body.path("maintainer_can_modify").asBoolean());
                    result=pull(body.path("body").asText()); status=201;
                }
            } else if (path.contains("/git/ref/heads/")) {
                String branch=path.substring(path.indexOf("/heads/")+7);
                if (!refs.containsKey(branch)) status=404; else result=Map.of("object",Map.of("sha",refs.get(branch)));
            } else if (path.contains("/git/commits/")) result=Map.of("tree",Map.of("sha",wrongTree?base:tree));
            else if (path.contains("/compare/")) result=Map.of("status","ahead","ahead_by",1,"merge_base_commit",Map.of("sha",base));
            else if (path.endsWith("/pulls")) result=List.of();
            else if (path.endsWith("/pulls/1")) result=pull("fixture");
            else status=404;
            byte[] bytes=json.writeValueAsBytes(result); exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); client=new GitHubReviewClient(json,"fixture",URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
    }
    Map<String,Object> pull(String body) { return Map.of("number",1,"html_url","https://github.com/test/repo/pull/1","state","open","merged",false,"head",Map.of("sha",head),"base",Map.of("sha",base),"body",body); }
    BuildProvenance.Source source() { return new BuildProvenance.Source(head,base,tree,"d".repeat(64)); }
    @AfterEach void stop() { server.stop(0); }
    @Test void exactCommitBranchIsIdempotentAndPullIsDraft() {
        client.validateSubject("test/repo","main",source());
        client.ensureBranch("test/repo","rcaproof/incident",head); client.ensureBranch("test/repo","rcaproof/incident",head);
        assertEquals(1,writes.get()); assertNull(client.find("test/repo","rcaproof/incident","main"));
        var pull=client.create("test/repo","rcaproof/incident","main","title","fixture"); assertEquals(head,pull.head()); assertEquals(1,pull.number());
    }
    @Test void lostBranchResponseIsReconciledWithoutForcePush() {
        loseBranchReply=true; client.ensureBranch("test/repo","rcaproof/incident",head); assertEquals(1,writes.get());
    }
    @Test void movedBaseWrongTreeAndForeignBranchRefuseWrites() {
        refs.put("main",head); assertThrows(IllegalStateException.class,()->client.validateSubject("test/repo","main",source()));
        refs.put("main",base); wrongTree=true; assertThrows(IllegalStateException.class,()->client.validateSubject("test/repo","main",source()));
        refs.put("rcaproof/incident",base); assertThrows(IllegalStateException.class,()->client.ensureBranch("test/repo","rcaproof/incident",head)); assertEquals(0,writes.get());
    }
    @Test void missingCredentialsCannotWrite() {
        var disabled=new GitHubReviewClient(json,"",URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        assertThrows(IllegalStateException.class,()->disabled.ensureBranch("test/repo","branch",head)); assertEquals(0,writes.get());
    }
}
