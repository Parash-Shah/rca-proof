package dev.rcaproof.api;

import dev.rcaproof.remediation.RemediationPlanner.Organization;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class OrganizationProfile {
    public record Snapshot(String sha256, String sourceYaml, Organization organization) {}
    private final Snapshot snapshot;
    public OrganizationProfile() {
        try {
            var resource=new ClassPathResource("organization/demo-org.yaml");
            byte[] bytes;
            try (var input=resource.getInputStream()) { bytes=input.readAllBytes(); }
            var loader=new YamlPropertiesFactoryBean(); loader.setResources(resource);
            Properties p=Objects.requireNonNull(loader.getObject());
            var org=new Organization(required(p,"name"),list(p,"languages"),list(p,"messaging"),list(p,"databases"),
                    flag(p,"constraints.new_managed_service.requires_architecture_review"),
                    flag(p,"constraints.payment_side_effects.require_idempotency"),
                    flag(p,"constraints.production_changes.require_human_approval"),flag(p,"constraints.auto_merge"),flag(p,"constraints.auto_deploy"));
            snapshot=new Snapshot(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    new String(bytes,StandardCharsets.UTF_8),org);
        } catch (Exception e) { throw new IllegalStateException("Invalid bundled organization profile; planning must fail closed",e); }
    }
    private static String required(Properties p,String key) {
        String value=p.getProperty("organization."+key);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing organization field: "+key);
        return value;
    }
    private static boolean flag(Properties p,String key) {
        String value=required(p,key);
        if (!Set.of("true","false").contains(value)) throw new IllegalArgumentException("Invalid organization boolean: "+key);
        return Boolean.parseBoolean(value);
    }
    private static Set<String> list(Properties p,String key) {
        Set<String> values=new TreeSet<>();
        for (int i=0;p.containsKey("organization."+key+"["+i+"]");i++) values.add(required(p,key+"["+i+"]"));
        if (values.isEmpty()) throw new IllegalArgumentException("Missing organization list: "+key);
        return Set.copyOf(values);
    }
    public Snapshot snapshot() { return snapshot; }
}
