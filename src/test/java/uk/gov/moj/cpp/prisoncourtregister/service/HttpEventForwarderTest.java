package uk.gov.moj.cpp.prisoncourtregister.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import uk.gov.moj.cpp.prisoncourtregister.model.ForwardableEvent;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpEventForwarderTest {

    private static final String HEARING_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ENVELOPE_JSON = """
            {
              "id": "evt-1",
              "topic": "/subscriptions/x/topics/hearing",
              "subject": "hearing/resulted",
              "eventType": "Hearing_Resulted",
              "eventTime": "2026-08-04T09:15:00Z",
              "dataVersion": "1.0",
              "data": {
                "hearingId": "%s",
                "hearingDay": "2026-08-04",
                "userId": "7aee5dea-b0de-4604-b49b-86c7788cfc4b"
              }
            }
            """.formatted(HEARING_ID);

    private HttpServer server;
    private URI endpoint;
    private ForwardableEvent event;

    private final List<String> receivedBodies = new ArrayList<>();
    private final List<String> receivedContentTypes = new ArrayList<>();
    private final List<String> receivedIngressHeaders = new ArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    /** Statuses returned in order; the last one repeats once exhausted. */
    private List<Integer> scriptedStatuses = List.of(200);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/hearing-results", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/internal/hearing-results");
        event = new ForwardableEvent(HEARING_ID, MAPPER.readTree(ENVELOPE_JSON));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("POSTs the envelope wrapped in a single-element array, verbatim")
    void postsEnvelopeAsSingleElementArray() throws Exception {
        scriptedStatuses = List.of(200);

        forwarder(3, Duration.ZERO).forward(event);

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(receivedContentTypes).containsExactly("application/json");

        final JsonNode body = MAPPER.readTree(receivedBodies.getFirst());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);

        final JsonNode relayed = body.get(0);
        assertThat(relayed.get("eventType").asText()).isEqualTo("Hearing_Resulted");
        assertThat(relayed.get("topic").asText()).isEqualTo("/subscriptions/x/topics/hearing");
        assertThat(relayed.get("dataVersion").asText()).isEqualTo("1.0");
        assertThat(relayed.path("data").path("hearingId").asText()).isEqualTo(HEARING_ID);
        assertThat(relayed.path("data").path("hearingDay").asText()).isEqualTo("2026-08-04");
        assertThat(relayed).isEqualTo(MAPPER.readTree(ENVELOPE_JSON));
    }

    @Test
    @DisplayName("retries a 503 - the PCR service's documented not-ready signal")
    void retriesServiceUnavailableThenSucceeds() {
        scriptedStatuses = List.of(503, 503, 200);

        forwarder(3, Duration.ZERO).forward(event);

        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("does not retry a 400 - the contract calls it permanent")
    void doesNotRetryBadRequest() {
        scriptedStatuses = List.of(400);

        assertThatThrownBy(() -> forwarder(3, Duration.ZERO).forward(event))
                .isInstanceOf(ForwardingException.class)
                .hasMessageContaining("Non-retryable status 400");

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("retries 429 and 408")
    void retriesThrottlingAndTimeout() {
        scriptedStatuses = List.of(429, 408, 200);

        forwarder(3, Duration.ZERO).forward(event);

        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("fails after exhausting the retry budget")
    void failsAfterExhaustingAttempts() {
        scriptedStatuses = List.of(503);

        assertThatThrownBy(() -> forwarder(3, Duration.ZERO).forward(event))
                .isInstanceOf(ForwardingException.class)
                .hasMessageContaining("after 3 attempt(s)");

        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("sends the ingress header only when both name and value are configured")
    void sendsIngressHeaderWhenConfigured() {
        scriptedStatuses = List.of(200);

        new HttpEventForwarder(HttpClient.newHttpClient(), endpoint,
                Optional.of("X-Ingress-Token"), Optional.of("secret"),
                1, Duration.ZERO, Duration.ofSeconds(5)).forward(event);

        assertThat(receivedIngressHeaders).containsExactly("secret");
    }

    @Test
    @DisplayName("omits the ingress header when only the name is configured")
    void omitsIngressHeaderWhenValueMissing() {
        scriptedStatuses = List.of(200);

        new HttpEventForwarder(HttpClient.newHttpClient(), endpoint,
                Optional.of("X-Ingress-Token"), Optional.empty(),
                1, Duration.ZERO, Duration.ofSeconds(5)).forward(event);

        assertThat(receivedIngressHeaders).containsExactly("<absent>");
    }

    @Test
    @DisplayName("omits the ingress header by default")
    void omitsIngressHeaderByDefault() {
        scriptedStatuses = List.of(200);

        forwarder(1, Duration.ZERO).forward(event);

        assertThat(receivedIngressHeaders).containsExactly("<absent>");
    }

    @Test
    @DisplayName("wraps a connection failure as ForwardingException")
    void wrapsConnectionFailure() {
        final URI unreachable = URI.create("http://127.0.0.1:1/internal/hearing-results");

        assertThatThrownBy(() -> new HttpEventForwarder(HttpClient.newHttpClient(), unreachable,
                Optional.empty(), Optional.empty(), 2, Duration.ZERO, Duration.ofSeconds(2))
                .forward(event))
                .isInstanceOf(ForwardingException.class)
                .hasMessageContaining("after 2 attempt(s)");
    }

    @Test
    @DisplayName("treats a non-positive attempt count as a single attempt")
    void clampsAttemptsToAtLeastOne() {
        scriptedStatuses = List.of(503);

        assertThatThrownBy(() -> forwarder(0, Duration.ZERO).forward(event))
                .isInstanceOf(ForwardingException.class)
                .hasMessageContaining("after 1 attempt(s)");

        assertThat(callCount.get()).isEqualTo(1);
    }

    private HttpEventForwarder forwarder(final int maxAttempts, final Duration retryDelay) {
        return new HttpEventForwarder(HttpClient.newHttpClient(), endpoint,
                Optional.empty(), Optional.empty(), maxAttempts, retryDelay, Duration.ofSeconds(5));
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final int index = callCount.getAndIncrement();

        try (InputStream in = exchange.getRequestBody()) {
            receivedBodies.add(new String(in.readAllBytes(), UTF_8));
        }
        receivedContentTypes.add(headerOrAbsent(exchange, "Content-Type"));
        receivedIngressHeaders.add(headerOrAbsent(exchange, "X-Ingress-Token"));

        final int status = scriptedStatuses.get(Math.min(index, scriptedStatuses.size() - 1));
        final byte[] response = ("{\"status\":" + status + "}").getBytes(UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static String headerOrAbsent(final HttpExchange exchange, final String name) {
        final String value = exchange.getRequestHeaders().getFirst(name);
        return value == null ? "<absent>" : value;
    }
}
