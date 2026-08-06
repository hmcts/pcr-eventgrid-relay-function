package uk.gov.moj.cpp.prisoncourtregister.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.moj.cpp.prisoncourtregister.integration.config.FunctionsHostClient.deliverNotification;
import static uk.gov.moj.cpp.prisoncourtregister.integration.config.FunctionsHostClient.deliverSubscriptionValidation;
import static uk.gov.moj.cpp.prisoncourtregister.integration.config.PcrServiceStub.relayCount;
import static uk.gov.moj.cpp.prisoncourtregister.integration.config.PcrServiceStub.relayedRequests;
import static uk.gov.moj.cpp.prisoncourtregister.integration.config.PcrServiceStub.respondWith;

import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end through the real Azure Functions host: Event Grid webhook in, HTTP POST out.
 *
 * <p>Covers what unit tests structurally cannot — the host, the Event Grid extension, the
 * {@code @EventGridTrigger} binding, and whether the packaged artefact actually loads.
 *
 * <p>Status codes asserted here were confirmed empirically against the real host: a successful or
 * deliberately-skipped invocation returns {@code 202}, a failed one {@code 500}, and the
 * subscription-validation handshake {@code 200}.
 */
class EventGridRelayIntegrationTest extends IntegrationTestBase {

    private static final String HEARING_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final String USER_ID = "7aee5dea-b0de-4604-b49b-86c7788cfc4b";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the packaged app loads and the Event Grid function is callable in the real host")
    void packagedAppIsLoadedAndCallable() {
        // An empty batch invokes nothing, so this isolates one thing: the host found and registered
        // the function from the packaged function.json. An unknown functionName answers 404, so a 2xx
        // here means the artefact loaded — catching lib/ prune regressions, a scriptFile that does
        // not match the versioned jar, and Java 25 runtime problems.
        final HttpResponse<String> response = deliverNotification("[]");

        assertThat(response.statusCode()).isBetween(200, 299);
    }

    @Test
    @DisplayName("a Hearing_Resulted notification is relayed verbatim to the PCR endpoint")
    void relaysNotificationVerbatim() throws Exception {
        respondWith(200);

        final HttpResponse<String> response = deliverNotification("[" + hearingResultedEvent() + "]");

        assertThat(response.statusCode()).isBetween(200, 299);
        awaitRelayCount(1);

        // The endpoint's contract is an array of EventGridSchema events, and the app must not reshape
        // the envelope on the way through.
        final JsonNode relayed = MAPPER.readTree(relayedRequests().getFirst().getBodyAsString());
        assertThat(relayed.isArray()).isTrue();
        assertThat(relayed).hasSize(1);
        assertThat(relayed.get(0)).isEqualTo(MAPPER.readTree(hearingResultedEvent()));
    }

    @Test
    @DisplayName("the host answers the Event Grid subscription-validation handshake itself")
    void answersSubscriptionValidationHandshake() {
        respondWith(200);

        final String validationCode = "512d38b6-c7b8-40c8-89fe-f46f9e9622b6";
        final String validationEvent = """
                [{
                  "id": "evt-validation",
                  "topic": "/subscriptions/x/topics/hearing",
                  "subject": "",
                  "eventType": "Microsoft.EventGrid.SubscriptionValidationEvent",
                  "eventTime": "2026-08-04T09:15:00Z",
                  "dataVersion": "1.0",
                  "metadataVersion": "1",
                  "data": { "validationCode": "%s" }
                }]
                """.formatted(validationCode);

        final HttpResponse<String> response = deliverSubscriptionValidation(validationEvent);

        // The load-bearing claim of this app's design: because the binding performs the handshake,
        // neither this app nor the PCR service needs any handshake code. Do not delete this test.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(validationCode);

        // And the handshake must never reach the PCR service.
        assertThat(relayedRequests()).isEmpty();
    }

    @Test
    @DisplayName("an event without a hearingId is skipped without calling the PCR endpoint")
    void skipsEventWithoutHearingId() {
        respondWith(200);

        final String event = """
                [{
                  "id": "evt-no-hearing",
                  "subject": "hearing/resulted",
                  "eventType": "Hearing_Resulted",
                  "eventTime": "2026-08-04T09:15:00Z",
                  "data": { "hearingDay": "2026-08-04", "userId": "%s" }
                }]
                """.formatted(USER_ID);

        final HttpResponse<String> response = deliverNotification(event);

        assertThat(response.statusCode()).isBetween(200, 299);
        assertThat(relayedRequests()).isEmpty();
    }

    @Test
    @DisplayName("a 503 from the PCR service is retried, then fails the invocation so Event Grid redelivers")
    void retriesThenFailsOnServiceUnavailable() {
        respondWith(503);

        final HttpResponse<String> response = deliverNotification("[" + hearingResultedEvent() + "]");

        // FORWARD_MAX_ATTEMPTS=2 in docker-compose.yml.
        awaitRelayCount(2);

        assertThat(response.statusCode())
                .as("a failed invocation must not report success, or Event Grid would not redeliver")
                .isEqualTo(500);
    }

    @Test
    @DisplayName("a 400 from the PCR service is not retried")
    void doesNotRetryBadRequest() {
        respondWith(400);

        final HttpResponse<String> response = deliverNotification("[" + hearingResultedEvent() + "]");

        assertThat(response.statusCode()).isEqualTo(500);

        // Settle first, so a wrongly-issued second attempt would be caught rather than raced past.
        Awaitility.await("no retry after a permanent failure")
                .pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(relayCount())
                        .as("400 is permanent per the PCR contract — the retry budget must not be spent")
                        .isEqualTo(1));
    }

    private static void awaitRelayCount(final int expected) {
        Awaitility.await("relayed request count")
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(relayCount()).isEqualTo(expected));
    }

    private static String hearingResultedEvent() {
        return """
                {
                  "id": "evt-1",
                  "topic": "/subscriptions/x/topics/hearing",
                  "subject": "hearing/resulted",
                  "eventType": "Hearing_Resulted",
                  "eventTime": "2026-08-04T09:15:00Z",
                  "dataVersion": "1.0",
                  "metadataVersion": "1",
                  "data": {
                    "hearingId": "%s",
                    "hearingDay": "2026-08-04",
                    "userId": "%s"
                  }
                }
                """.formatted(HEARING_ID, USER_ID);
    }
}
