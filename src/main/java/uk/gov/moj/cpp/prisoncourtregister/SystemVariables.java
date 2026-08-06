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

    /**
     * Path to a PEM bundle of extra CA certificates to trust, on top of the JVM defaults.
     *
     * <p>The PCR service's internal ingress presents a certificate from a private CA that the JVM does
     * not ship. Unset it and TLS to that host fails; the Node sibling apps solve the same problem with
     * {@code NODE_EXTRA_CA_CERTS}, which has no JVM equivalent.
     *
     * <p>Optional — when unset or pointing at a missing file, the default SSL context is used
     * unchanged, so local runs and the integration tests (plain HTTP) are unaffected.
     *
     * <p>The bundle is deliberately **not** committed to this repository: it contains internal CA
     * certificates and internal domain names, and this repo is public. Provision it onto the Function
     * App instead — e.g. via {@code WEBSITE_LOAD_CERTIFICATES}, which exposes certificates under
     * {@code /var/ssl/certs} — and point this variable at it.
     */
    public static final String PCR_SERVICE_CA_BUNDLE_PATH = "PCR_SERVICE_CA_BUNDLE_PATH";

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
