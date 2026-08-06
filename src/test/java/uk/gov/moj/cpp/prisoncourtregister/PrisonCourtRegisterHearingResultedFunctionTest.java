package uk.gov.moj.cpp.prisoncourtregister;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.moj.cpp.prisoncourtregister.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cpp.prisoncourtregister.model.ForwardableEvent;
import uk.gov.moj.cpp.prisoncourtregister.service.EventParsingException;
import uk.gov.moj.cpp.prisoncourtregister.service.ForwardingException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrisonCourtRegisterHearingResultedFunctionTest {

    private static final String HEARING_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final String USER_ID = "7aee5dea-b0de-4604-b49b-86c7788cfc4b";

    private RecordingEventForwarder forwarder;
    private PrisonCourtRegisterHearingResultedFunction function;

    @BeforeEach
    void setUp() {
        forwarder = new RecordingEventForwarder();
        function = new PrisonCourtRegisterHearingResultedFunction(forwarder, getObjectMapper());
    }

    @Test
    @DisplayName("relays the event with its hearingId extracted for correlation")
    void relaysEvent() {
        function.run(eventJson(HEARING_ID, "2026-08-04", USER_ID), new TestExecutionContext());

        assertThat(forwarder.forwardedHearingIds()).containsExactly(HEARING_ID);
    }

    @Test
    @DisplayName("relays the envelope untouched, preserving fields it does not model")
    void relaysEnvelopeVerbatim() {
        final String event = """
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
                    "userId": "%s",
                    "somethingNew": { "nested": true }
                  }
                }
                """.formatted(HEARING_ID, USER_ID);

        function.run(event, new TestExecutionContext());

        final ForwardableEvent relayed = forwarder.forwarded().getFirst();
        assertThat(relayed.envelope().get("topic").asText())
                .isEqualTo("/subscriptions/x/topics/hearing");
        assertThat(relayed.envelope().get("dataVersion").asText()).isEqualTo("1.0");
        assertThat(relayed.envelope().get("metadataVersion").asText()).isEqualTo("1");
        assertThat(relayed.envelope().path("data").path("somethingNew").path("nested").asBoolean())
                .isTrue();
        assertThat(relayed.envelope().path("data").path("hearingDay").asText()).isEqualTo("2026-08-04");
        assertThat(relayed.envelope().path("data").path("userId").asText()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("relays every event when the host delivers a batch array")
    void relaysEachEventInAnArray() {
        final String otherHearingId = "9c858901-8a57-4791-81fe-4c455b099bc9";
        final String batch = "[%s,%s]".formatted(
                eventJson(HEARING_ID, "2026-08-04", USER_ID),
                eventJson(otherHearingId, "2026-08-05", USER_ID));

        function.run(batch, new TestExecutionContext());

        assertThat(forwarder.forwardedHearingIds()).containsExactly(HEARING_ID, otherHearingId);
    }

    @Test
    @DisplayName("skips the event when hearingId is absent")
    void skipsEventWithoutHearingId() {
        final String event = """
                {
                  "subject": "hearing/resulted",
                  "data": { "hearingDay": "2026-08-04", "userId": "%s" }
                }
                """.formatted(USER_ID);

        function.run(event, new TestExecutionContext());

        assertThat(forwarder.forwarded()).isEmpty();
    }

    @Test
    @DisplayName("skips the event when hearingId is blank")
    void skipsEventWithBlankHearingId() {
        function.run(eventJson("   ", "2026-08-04", USER_ID), new TestExecutionContext());

        assertThat(forwarder.forwarded()).isEmpty();
    }

    @Test
    @DisplayName("skips the event when the data block is absent")
    void skipsEventWithoutDataBlock() {
        function.run("{\"subject\":\"hearing/resulted\"}", new TestExecutionContext());

        assertThat(forwarder.forwarded()).isEmpty();
    }

    @Test
    @DisplayName("skips a subscription validation event without calling the service")
    void skipsSubscriptionValidationEvent() {
        final String event = """
                {
                  "id": "evt-validation",
                  "eventType": "Microsoft.EventGrid.SubscriptionValidationEvent",
                  "subject": "",
                  "data": { "validationCode": "512d38b6-c7b8-40c8-89fe-f46f9e9622b6" }
                }
                """;

        function.run(event, new TestExecutionContext());

        assertThat(forwarder.forwarded()).isEmpty();
    }

    @Test
    @DisplayName("propagates a parsing failure so the delivery dead-letters")
    void throwsOnMalformedJson() {
        assertThatThrownBy(() -> function.run("not json", new TestExecutionContext()))
                .isInstanceOf(EventParsingException.class)
                .hasMessageContaining("Could not deserialise");

        assertThat(forwarder.forwarded()).isEmpty();
    }

    @Test
    @DisplayName("propagates a relay failure so Event Grid retries")
    void propagatesForwardingFailure() {
        forwarder.failWith(new ForwardingException("PCR service unavailable"));

        assertThatThrownBy(() ->
                function.run(eventJson(HEARING_ID, "2026-08-04", USER_ID), new TestExecutionContext()))
                .isInstanceOf(ForwardingException.class)
                .hasMessageContaining("PCR service unavailable");
    }

    private static String eventJson(final String hearingId, final String hearingDay, final String userId) {
        return """
                {
                  "id": "evt-1",
                  "subject": "hearing/resulted",
                  "eventType": "Hearing_Resulted",
                  "eventTime": "2026-08-04T09:15:00Z",
                  "data": {
                    "hearingId": "%s",
                    "hearingDay": "%s",
                    "userId": "%s"
                  }
                }
                """.formatted(hearingId, hearingDay, userId);
    }
}
