package uk.gov.moj.cpp.prisoncourtregister.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static uk.gov.moj.cpp.prisoncourtregister.SystemVariables.FORWARD_MAX_ATTEMPTS;
import static uk.gov.moj.cpp.prisoncourtregister.SystemVariables.FORWARD_RETRY_DELAY_IN_SECONDS;
import static uk.gov.moj.cpp.prisoncourtregister.SystemVariables.HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS;
import static uk.gov.moj.cpp.prisoncourtregister.SystemVariables.HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS;
import static uk.gov.moj.cpp.prisoncourtregister.SystemVariables.PCR_SERVICE_CA_BUNDLE_PATH;
import static uk.gov.moj.cpp.prisoncourtregister.SystemVariables.PCR_SERVICE_INGESTION_ENDPOINT;
import static uk.gov.moj.cpp.prisoncourtregister.SystemVariables.PCR_SERVICE_INGRESS_HEADER_NAME;
import static uk.gov.moj.cpp.prisoncourtregister.SystemVariables.PCR_SERVICE_INGRESS_HEADER_VALUE;
import static uk.gov.moj.cpp.prisoncourtregister.util.EnvVarUtil.getIntEnv;
import static uk.gov.moj.cpp.prisoncourtregister.util.EnvVarUtil.getOptionalEnv;
import static uk.gov.moj.cpp.prisoncourtregister.util.EnvVarUtil.getRequiredEnv;
import static uk.gov.moj.cpp.prisoncourtregister.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cpp.prisoncourtregister.model.ForwardableEvent;
import uk.gov.moj.cpp.prisoncourtregister.util.AdditiveTrust;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POSTs the event to the PCR service's {@code /internal/hearing-results} endpoint.
 *
 * <p>The body is a single-element JSON <strong>array</strong> containing the untouched Event Grid
 * envelope, matching that operation's {@code requestBody} (EventGridSchema, delivered as an array).
 * Relaying verbatim means the PCR service needs no new contract for this app — it is the same
 * request Event Grid itself would have made.
 *
 * <p>Status handling follows the endpoint's documented semantics:
 * <ul>
 *   <li>{@code 200} — accepted.</li>
 *   <li>{@code 503} — hearing details not complete yet; retryable, the service expects redelivery.</li>
 *   <li>{@code 400} — malformed or unrecognised {@code eventType}; a permanent failure that the
 *       contract explicitly says must not be retried.</li>
 * </ul>
 */
public class HttpEventForwarder implements EventForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpEventForwarder.class);

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_RETRY_DELAY_SECONDS = 2;
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_RESPONSE_TIMEOUT_SECONDS = 30;
    private static final int MAX_LOGGED_BODY_CHARS = 500;

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Optional<String> ingressHeaderName;
    private final Optional<String> ingressHeaderValue;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration responseTimeout;

    public HttpEventForwarder() {
        this(
                newHttpClient(),
                URI.create(getRequiredEnv(PCR_SERVICE_INGESTION_ENDPOINT)),
                getOptionalEnv(PCR_SERVICE_INGRESS_HEADER_NAME),
                getOptionalEnv(PCR_SERVICE_INGRESS_HEADER_VALUE),
                getIntEnv(FORWARD_MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS),
                Duration.ofSeconds(getIntEnv(FORWARD_RETRY_DELAY_IN_SECONDS, DEFAULT_RETRY_DELAY_SECONDS)),
                Duration.ofSeconds(getIntEnv(HTTP_CLIENT_RESPONSE_TIMEOUT_IN_SECONDS, DEFAULT_RESPONSE_TIMEOUT_SECONDS)));
    }

    /**
     * The client used in production: default timeouts, plus the private-CA trust the PCR service's
     * internal ingress requires. Without the bundle the JVM rejects that certificate and every relay
     * fails at the TLS handshake — see {@link AdditiveTrust}.
     */
    private static HttpClient newHttpClient() {
        final HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(
                        getIntEnv(HTTP_CLIENT_CONNECT_TIMEOUT_IN_SECONDS, DEFAULT_CONNECT_TIMEOUT_SECONDS)));

        AdditiveTrust.fromPemBundle(getOptionalEnv(PCR_SERVICE_CA_BUNDLE_PATH))
                .ifPresent(builder::sslContext);

        return builder.build();
    }

    /* default */ HttpEventForwarder(final HttpClient httpClient,
                       final URI endpoint,
                       final Optional<String> ingressHeaderName,
                       final Optional<String> ingressHeaderValue,
                       final int maxAttempts,
                       final Duration retryDelay,
                       final Duration responseTimeout) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.ingressHeaderName = ingressHeaderName;
        this.ingressHeaderValue = ingressHeaderValue;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelay = retryDelay;
        this.responseTimeout = responseTimeout;
    }

    @Override
    public void forward(final ForwardableEvent event) {
        final HttpRequest httpRequest = buildRequest(event);
        final String hearingId = event.hearingId();

        // Tracked as a description plus an optional cause rather than a pre-built exception, so no
        // throwable is constructed per attempt; the final exception is built once, below.
        // lastFailureWasIo decides whether lastIoFailure is still the *most recent* failure and so
        // fit to be the cause — a stale IOException from an earlier attempt must not be attached.
        String lastFailureDescription = null;
        IOException lastIoFailure = null;
        boolean lastFailureWasIo = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                final HttpResponse<String> response =
                        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(UTF_8));
                final int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    LOGGER.info("[Hearing ID: {}] Relayed to {} - status {} (attempt {}/{})",
                            hearingId, endpoint, status, attempt, maxAttempts);
                    return;
                }

                if (!isRetryable(status)) {
                    throw new ForwardingException(
                            "[Hearing ID: %s] Non-retryable status %d from %s: %s"
                                    .formatted(hearingId, status, endpoint, truncate(response.body())));
                }

                LOGGER.warn("[Hearing ID: {}] Retryable status {} from {} (attempt {}/{}): {}",
                        hearingId, status, endpoint, attempt, maxAttempts, truncate(response.body()));
                lastFailureDescription = "status %d: %s".formatted(status, truncate(response.body()));
                lastFailureWasIo = false;

            } catch (IOException e) {
                // Include the exception type: ConnectException / UnknownHostException carry a null
                // message, so logging getMessage() alone yields "I/O failure: null" — which says the
                // call failed but not whether it was DNS, routing or a refused connection. That
                // distinction is the whole diagnosis when the target is an internal ingress.
                final String cause = describe(e);
                LOGGER.warn("[Hearing ID: {}] I/O failure calling {} (attempt {}/{}): {}",
                        hearingId, endpoint, attempt, maxAttempts, cause);
                lastFailureDescription = "I/O failure: " + cause;
                lastIoFailure = e;
                lastFailureWasIo = true;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ForwardingException(
                        "[Hearing ID: %s] Interrupted while relaying to %s".formatted(hearingId, endpoint), e);
            }

            if (attempt < maxAttempts) {
                sleepBeforeRetry(hearingId);
            }
        }

        final String failureMessage =
                "[Hearing ID: %s] Failed to relay to %s after %d attempt(s) - last failure: %s"
                        .formatted(hearingId, endpoint, maxAttempts, lastFailureDescription);

        if (lastFailureWasIo) {
            throw new ForwardingException(failureMessage, lastIoFailure);
        }
        throw new ForwardingException(failureMessage);
    }

    private HttpRequest buildRequest(final ForwardableEvent event) {
        // The endpoint takes an array of EventGridSchema events; the Java EventGridTrigger binding
        // hands us one event at a time, so wrap it back up in a single-element array.
        final ArrayNode body = getObjectMapper().createArrayNode().add(event.envelope());

        final String json;
        try {
            json = getObjectMapper().writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ForwardingException(
                    "[Hearing ID: %s] Could not serialise event envelope".formatted(event.hearingId()), e);
        }

        final HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(responseTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, UTF_8));

        // The endpoint itself is `security: []` (network-isolated, per ADR-007). This optional
        // header exists only for an ingress that fronts it and needs one; unset by default.
        if (ingressHeaderName.isPresent() && ingressHeaderValue.isPresent()) {
            builder.header(ingressHeaderName.get(), ingressHeaderValue.get());
        }

        return builder.build();
    }

    private void sleepBeforeRetry(final String hearingId) {
        if (retryDelay.isZero() || retryDelay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForwardingException(
                    "[Hearing ID: %s] Interrupted while waiting to retry".formatted(hearingId), e);
        }
    }

    /**
     * 503 is the PCR service's documented "not ready, redeliver" signal. 400 is explicitly
     * permanent. Other 5xx and the usual transient statuses are retried too.
     */
    private static boolean isRetryable(final int status) {
        return status >= 500 || status == 408 || status == 429;
    }

    /**
     * {@code type: message}, or just the type when the message is null.
     *
     * <p>{@link java.net.ConnectException} and {@link java.net.UnknownHostException} routinely carry a
     * null message, and "I/O failure: null" does not tell you whether the target was unresolvable,
     * unroutable or refusing connections.
     */
    private static String describe(final Exception e) {
        final String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    private static String truncate(final String body) {
        final String safe = body == null ? "" : body;
        return safe.length() <= MAX_LOGGED_BODY_CHARS
                ? safe
                : safe.substring(0, MAX_LOGGED_BODY_CHARS) + "...";
    }
}
