package uk.gov.moj.cpp.prisoncourtregister;

/**
 * Names of the environment variables (Function App settings) this app reads.
 */
public final class SystemVariables {

    /**
     * Absolute URL of the PCR service's ingestion endpoint — the
     * {@code POST /internal/hearing-results} operation. Required.
     */
    public static final String PCR_SERVICE_INGESTION_ENDPOINT = "PCR_SERVICE_INGESTION_ENDPOINT";

    /**
     * Optional header name required by the ingress fronting the PCR service. The endpoint itself is
     * {@code security: []} (network-isolated), so this is normally unset. Only applied when both
     * name and value are set.
     */
    public static final String PCR_SERVICE_INGRESS_HEADER_NAME = "PCR_SERVICE_INGRESS_HEADER_NAME";

    /** Value for {@link #PCR_SERVICE_INGRESS_HEADER_NAME}. */
    public static final String PCR_SERVICE_INGRESS_HEADER_VALUE = "PCR_SERVICE_INGRESS_HEADER_VALUE";

    /** Total attempts per event, including the first. Default 3. */
    public static final String FORWARD_MAX_ATTEMPTS = "FORWARD_MAX_ATTEMPTS";

    /** Delay between retry attempts, in seconds. Default 2. */
    public static final String FORWARD_RETRY_DELAY_IN_SECONDS = "FORWARD_RETRY_DELAY_IN_SECONDS";

    /** TCP connect timeout, in seconds. Default 10. */
    public static final String HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS = "HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS";

    /** Per-request response timeout, in seconds. Default 30. */
    public static final String HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS = "HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS";

    private SystemVariables() {
    }
}
