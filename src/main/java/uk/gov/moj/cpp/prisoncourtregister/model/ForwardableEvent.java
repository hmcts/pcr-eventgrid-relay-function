package uk.gov.moj.cpp.prisoncourtregister.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One Hearing_Resulted event ready to relay.
 *
 * <p>The envelope is carried as a {@link JsonNode} rather than a typed record on purpose: this app
 * is a relay, and the PCR service's {@code /internal/hearing-results} contract accepts the
 * EventGridSchema envelope. Modelling only the fields we read would silently drop the ones we
 * don't ({@code topic}, {@code dataVersion}, {@code metadataVersion}, and anything the publisher
 * adds later), so the original node is forwarded untouched.
 *
 * @param hearingId Extracted from {@code data.hearingId} for logging and correlation only.
 * @param envelope  The event exactly as received.
 */
public record ForwardableEvent(String hearingId, JsonNode envelope) {
}
