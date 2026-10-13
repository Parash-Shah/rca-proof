package dev.rcaproof.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;

@Component
public class BuildProvenance {
    public record Source(String commit, String baseCommit, String tree, String patchSha256) {}
    public record Binding(Source source,String applicationSha256,Map<String,String> dependencies,
                          Map<String,String> environment,String problem) {}
    private final Binding binding;
    public BuildProvenance(ObjectMapper json) {
        Binding loaded;
        var environment=Map.of("java",System.getProperty("java.runtime.version"),"os",System.getProperty("os.name"),
                "architecture",System.getProperty("os.arch"),"contract","local-payment-lab-v2",
                "providerRetention","24h cache; durable reconciliation journal retained",
                "loadTarget","20 orders / 40 deliveries / 120000ms","rollback","retain provider keys; drain before retention expires");
        try (var manifest=getClass().getResourceAsStream("/build-evidence/source.json");
             var patch=getClass().getResourceAsStream("/build-evidence/candidate.patch")) {
            if (manifest==null || patch==null) throw new IllegalStateException("No committed source/patch manifest was packaged");
            Source source=json.readValue(manifest,Source.class);
            if (!hex(source.commit(),40) || !hex(source.baseCommit(),40) || !hex(source.tree(),40)
                    || !hex(source.patchSha256(),64) || !sha256(patch.readAllBytes()).equals(source.patchSha256()))
                throw new IllegalStateException("Invalid source identity or patch digest");
            var applicationSource=new ApplicationHome(ApiApplication.class).getSource();
            Path jar=applicationSource==null ? null : applicationSource.toPath();
            if (jar==null || !Files.isRegularFile(jar)) jar=Path.of("target/rcaproof-api-0.1.0-SNAPSHOT-exec.jar");
            var dependencies=new TreeMap<String,String>();
            try (var archive=new JarFile(jar.toFile())) {
                for (var entry:Collections.list(archive.entries())) if (entry.getName().startsWith("BOOT-INF/lib/") && entry.getName().endsWith(".jar"))
                    try (var input=archive.getInputStream(entry)) { dependencies.put(entry.getName(),sha256(input.readAllBytes())); }
            }
            if (dependencies.isEmpty()) throw new IllegalStateException("Executable archive dependency inventory is missing");
            loaded=new Binding(source,sha256(Files.readAllBytes(jar)),Map.copyOf(dependencies),environment,null);
        } catch (Exception e) { loaded=new Binding(null,null,Map.of(),environment,e.getMessage()); }
        binding=loaded;
    }
    public Binding binding() { return binding; }
    static boolean hex(String value,int length) { return value!=null && value.matches("[0-9a-f]{"+length+"}"); }
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
