package dev.rcaproof.api;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component @Primary
public class SandboxRouter implements SandboxFactory {
    private final DuplicatePaymentSandbox payment;
    private final HttpRetrySandbox retry;
    public SandboxRouter(DuplicatePaymentSandbox payment,HttpRetrySandbox retry) { this.payment=payment; this.retry=retry; }
    public Session open(IncidentModels.Job job,IncidentModels.Incident incident) throws Exception {
        return switch (incident.failureSignature()) {
            case "DUPLICATE_PAYMENT" -> payment.open(job,incident);
            case "RETRY_STORM" -> retry.open(job,incident);
            default -> throw new IllegalArgumentException("No external sandbox for this signature");
        };
    }
    public void cleanup(IncidentModels.Job job) throws Exception { payment.cleanup(job); }
}
