package uk.gov.moj.cpp.prisoncourtregister.service;

/**
 * Thrown when the event could not be delivered to the downstream service.
 *
 * <p>Propagated out of the function so the Event Grid delivery is retried and ultimately
 * dead-lettered rather than silently dropped.
 */
public class ForwardingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForwardingException(final String message) {
        super(message);
    }

    public ForwardingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
