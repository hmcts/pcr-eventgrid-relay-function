package uk.gov.moj.cpp.prisoncourtregister.service;

/**
 * Thrown when the inbound Event Grid payload cannot be deserialised.
 *
 * <p>A malformed event will never succeed on retry, but it is propagated rather than swallowed so
 * the delivery dead-letters and the loss is visible instead of silent.
 */
public class EventParsingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventParsingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
