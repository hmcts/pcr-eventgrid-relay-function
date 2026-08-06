package uk.gov.moj.cpp.prisoncourtregister;

import static uk.gov.moj.cpp.prisoncourtregister.util.ObjectMapperFactory.getObjectMapper;

import uk.gov.moj.cpp.prisoncourtregister.model.ForwardableEvent;
import uk.gov.moj.cpp.prisoncourtregister.service.EventForwarder;
import uk.gov.moj.cpp.prisoncourtregister.service.EventParsingException;
import uk.gov.moj.cpp.prisoncourtregister.service.HttpEventForwarder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventGridTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin Event Grid relay: receives a {@code Hearing_Resulted} event and POSTs it, untouched, to the
 * PCR service's {@code /internal/hearing-results} endpoint.
 *
 * <p><strong>Why this app exists.</strong> The accepted PCR design (ADR-007 / AMP-892) has Event
 * Grid delivering straight to a webhook on {@code service-cp-crime-results-pcr}. That makes the
 * service own an Event Grid-shaped surface: a public-ish HTTPS endpoint, the
 * {@code SubscriptionValidationEvent} handshake, and network isolation in place of application
 * auth. Putting this Function App in front moves that surface out of the service — the
 * {@code @EventGridTrigger} binding performs the validation handshake itself, so no handshake code
 * is needed anywhere, and the service is left receiving an ordinary internal HTTP call.
 *
 * <p><strong>This replaces nothing.</strong> The JavaScript durable-functions chain in
 * {@code cpp-context-azure-legalaidagency} (trigger &rarr; orchestrator &rarr; cache query &rarr;
 * SetPrisonCourtRegister &rarr; subscriptions &rarr; outbound &rarr; process-outbound) keeps running
 * and keeps its own Event Grid subscription: it produces the PDF/email PCR distribution, which the
 * PCR API-marketplace work explicitly does not change or retire (design doc &sect;13 non-goals —
 * "additional channel, not a replacement"). This relay is a <em>second, independent consumer</em> of
 * the same {@code Hearing_Resulted} event, feeding the new pull-based read channel alongside it.
 *
 * <p>So the two pipelines coexist, and the PCR service — not this app — owns the Redis lookup,
 * completeness retries and persistence for its side.
 *
 * <p>The event is relayed <em>verbatim</em>, wrapped in the single-element array the endpoint
 * expects. This app deliberately knows nothing about the payload beyond {@code data.hearingId},
 * which it uses to skip pointless deliveries and to correlate logs.
 */
public class PrisonCourtRegisterHearingResultedFunction {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PrisonCourtRegisterHearingResultedFunction.class);

    private final EventForwarder eventForwarder;
    private final ObjectMapper objectMapper;

    public PrisonCourtRegisterHearingResultedFunction() {
        this(new HttpEventForwarder(), getObjectMapper());
    }

    /* default */ PrisonCourtRegisterHearingResultedFunction(final EventForwarder eventForwarder,
                                              final ObjectMapper objectMapper) {
        this.eventForwarder = eventForwarder;
        this.objectMapper = objectMapper;
    }

    @FunctionName("PrisonCourtRegisterHearingResulted")
    public void run(
            @EventGridTrigger(name = "event") final String event,
            final ExecutionContext context) {

        final JsonNode envelope = parse(event);

        // The Java binding delivers one event per invocation, but tolerate an array in case the
        // host ever hands over a batch.
        if (envelope.isArray()) {
            envelope.forEach(this::relay);
            return;
        }

        relay(envelope);
    }

    private void relay(final JsonNode envelope) {
        final String hearingId = envelope.path("data").path("hearingId").asText(null);
        final String subject = envelope.path("subject").asText(null);

        if (hearingId == null || hearingId.isBlank()) {
            // Covers deliberately-ignored events, including a SubscriptionValidationEvent should one
            // ever reach the handler rather than being answered by the binding. Not an error.
            LOGGER.warn("[Hearing ID: undefined, Subject: {}, Event type: {}] No hearingId on event - skipping",
                    subject, envelope.path("eventType").asText(null));
            return;
        }

        LOGGER.info("[Hearing ID: {}, Subject: {}, Event type: {}, Time: {}] Relaying Hearing_Resulted event",
                hearingId, subject, envelope.path("eventType").asText(null),
                envelope.path("eventTime").asText(null));

        eventForwarder.forward(new ForwardableEvent(hearingId, envelope));

        LOGGER.info("[Hearing ID: {}] Hearing_Resulted event relayed successfully", hearingId);
    }

    private JsonNode parse(final String event) {
        try {
            final JsonNode parsed = objectMapper.readTree(event);
            if (parsed == null || parsed.isNull() || parsed.isMissingNode()) {
                throw new EventParsingException("Event Grid payload deserialised to null", null);
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw new EventParsingException("Could not deserialise Event Grid payload", e);
        }
    }
}
