package dev.rcaproof.api;

import dev.rcaproof.experiment.Observation;
import java.util.*;
import static dev.rcaproof.api.IncidentModels.*;

public interface SandboxFactory {
    Session open(Job job, Incident incident) throws Exception;
    default void cleanup(Job job) throws Exception {}
    record Result(Observation observation, Map<String,Object> measurements) {}
    interface Session extends AutoCloseable {
        Result run(Set<String> factors, int repetition) throws Exception;
        @Override void close() throws Exception;
    }
}
