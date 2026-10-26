package dev.rcaproof.demo.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rcaproof.demo.contract.*;
import java.nio.file.*;
import java.util.Map;

/** Reports the injected crash only after observing the child process actually exit with code 86. */
public final class WorkerSupervisor {
    public record CrashContext(Order order,String messageId) {}
    static void record(ObjectMapper json,Order order,String messageId) {
        String file=System.getenv("RCAPROOF_CRASH_RECORD");
        if (file==null) return;
        try { Files.writeString(Path.of(file),json.writeValueAsString(new CrashContext(order,messageId))); }
        catch (Exception e) { org.slf4j.LoggerFactory.getLogger(WorkerSupervisor.class).warn("Crash context unavailable; no crash evidence will be emitted",e); }
    }
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var telemetry=new TelemetryClient(System.getenv("LAB_TELEMETRY_URL"),json);
        Path context=Files.createTempFile("rcaproof-worker-",".json");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar","/app/app.jar").inheritIO();
        builder.environment().put("RCAPROOF_CRASH_RECORD",context.toString());
        Process child=builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
        int exit=child.waitFor();
        try {
            if (exit==86 && Files.size(context)>0) {
                var crash=json.readValue(Files.readString(context),CrashContext.class);
                if (crash.order()!=null && crash.messageId()!=null && !crash.messageId().isBlank())
                    telemetry.emit(crash.order(),"worker-supervisor","WORKER_CRASHED",crash.messageId(),Map.of("exitCode","86","observedBy","worker-supervisor"));
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(WorkerSupervisor.class).warn("Crash evidence unavailable",e);
        } finally {
            try { Files.deleteIfExists(context); }
            catch (Exception e) { org.slf4j.LoggerFactory.getLogger(WorkerSupervisor.class).warn("Could not remove crash context",e); }
        }
        System.exit(exit);
    }
}
