package dev.rcaproof.experiment;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.io.IOException;
import java.util.concurrent.*;

/** Bounded GET retry implementation. Transport failures propagate instead of becoming HTTP evidence. */
public final class RetryingHttpClient implements AutoCloseable {
    public record Result(List<Integer> statuses,long elapsedMillis) {
        public Result { statuses=List.copyOf(statuses); }
        public int finalStatus() { return statuses.getLast(); }
    }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public record Attempt(String outcome, int status, long remainingMs, long timeoutMs) {}
    public record TimedResult(List<Attempt> attempts, String outcome, long elapsedMillis) {
        public TimedResult { attempts=List.copyOf(attempts); }
    }
    /** A relative budget is propagated for this hop; the caller enforces it with a monotonic clock.
     * Transport failures are surfaced without application retries. Reads include the response body.
     */
    public TimedResult getWithin(URI uri,String requestId,String deployment,int maxAttempts,Duration perAttempt,
                                 Duration totalBudget,boolean enforceDeadline,Runnable budget) throws Exception {
        if (maxAttempts<1 || maxAttempts>6 || perAttempt.isZero() || perAttempt.isNegative()
                || perAttempt.compareTo(Duration.ofSeconds(10))>0 || totalBudget.isNegative()
                || totalBudget.compareTo(Duration.ofSeconds(10))>0) throw new IllegalArgumentException("Invalid HTTP deadline budget");
        long started=System.nanoTime(), deadline=started+totalBudget.toNanos();
        List<Attempt> observations=new ArrayList<>(); String outcome="ATTEMPTS_EXHAUSTED";
        for (int i=1;i<=maxAttempts;i++) {
            budget.run();
            if (i>1) {
                long delay=enforceDeadline?Math.min(Duration.ofMillis(25).toNanos(),Math.max(0,deadline-System.nanoTime())):Duration.ofMillis(25).toNanos();
                if(delay>0) Thread.sleep(Duration.ofNanos(delay));
            }
            long remaining=deadline-System.nanoTime();
            if (enforceDeadline && remaining<=0) { outcome="DEADLINE_EXCEEDED"; break; }
            long allowed=enforceDeadline?Math.min(perAttempt.toNanos(),remaining):perAttempt.toNanos();
            boolean limitedByDeadline=enforceDeadline && remaining<=perAttempt.toNanos();
            long remainingMs=enforceDeadline?Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)):totalBudget.toMillis();
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(allowed))
                    .header("X-RCAProof-Request-Id",requestId).header("X-RCAProof-Attempt",Integer.toString(i)).header("X-Lab-Deployment",deployment)
                    .header("X-RCAProof-Remaining-Ms",Long.toString(remainingMs)).GET().build();
            var pending=http.sendAsync(request,HttpResponse.BodyHandlers.discarding());
            try {
                // Future timeout also covers stalled bodies, not just receipt of response headers.
                long wait=enforceDeadline?Math.min(allowed,deadline-System.nanoTime()):allowed;
                if (wait<=0) throw new TimeoutException();
                int status=pending.get(wait,TimeUnit.NANOSECONDS).statusCode();
                observations.add(new Attempt("HTTP",status,remainingMs,Math.max(1,TimeUnit.NANOSECONDS.toMillis(allowed))));
                outcome="HTTP_"+status;
                if (status<500 || status>599) break;
            } catch (TimeoutException e) {
                pending.cancel(true);
                outcome=limitedByDeadline?"DEADLINE_EXCEEDED":"ATTEMPT_TIMEOUT";
                observations.add(new Attempt(outcome,0,remainingMs,Math.max(1,TimeUnit.NANOSECONDS.toMillis(allowed))));
                break;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof HttpTimeoutException) outcome=limitedByDeadline?"DEADLINE_EXCEEDED":"ATTEMPT_TIMEOUT";
                else if (e.getCause() instanceof IOException) outcome="TRANSPORT_ERROR";
                else throw e;
                observations.add(new Attempt(outcome,0,remainingMs,Math.max(1,TimeUnit.NANOSECONDS.toMillis(allowed))));
                break;
            } catch (InterruptedException e) { pending.cancel(true); Thread.currentThread().interrupt(); throw e; }
        }
        return new TimedResult(observations,outcome,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
    }
    public Result get(URI uri,String requestId,String deployment,int attempts,Duration backoff,Duration timeout,Runnable budget) throws Exception {
        if (attempts<1 || attempts>6 || backoff.isNegative() || backoff.compareTo(Duration.ofSeconds(1))>0
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10))>0) throw new IllegalArgumentException("Retry lab budget exceeded");
        List<Integer> statuses=new ArrayList<>(); long started=System.nanoTime();
        for (int attempt=1;attempt<=attempts;attempt++) {
            budget.run();
            var request=HttpRequest.newBuilder(uri).timeout(timeout).header("X-RCAProof-Request-Id",requestId)
                    .header("X-RCAProof-Attempt",Integer.toString(attempt)).header("X-Lab-Deployment",deployment).GET().build();
            int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(); statuses.add(status);
            if (status<500 || status>599) break;
            if (attempt<attempts && !backoff.isZero()) Thread.sleep(backoff);
        }
        return new Result(statuses,(System.nanoTime()-started)/1_000_000);
    }
    @Override public void close() { http.shutdownNow(); }
}
