package dev.rcaproof.experiment;

import java.util.List;

public final class RetryContract {
    private RetryContract() {}
    public static final String VERSION="http-retry-storm-v1";
    public static final List<String> FACTORS=List.of("DEPENDENCY_HTTP_500","EXCESSIVE_RETRIES","ALTERNATE_DEPLOYMENT");
    public static final String SCOPE="LOCAL_HTTP_LAB: actual Java HTTP client and separate WireMock dependency; correlated client/server request counts; bounded GET retries; not production load or non-idempotent operation certification; "+VERSION;
    public static final int MAX_DEPENDENCY_CALLS=3;
}
