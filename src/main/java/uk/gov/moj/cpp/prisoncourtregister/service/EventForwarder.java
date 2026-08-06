package uk.gov.moj.cpp.prisoncourtregister.service;

import uk.gov.moj.cpp.prisoncourtregister.model.ForwardableEvent;

/**
 * Relays a Hearing_Resulted event to the PCR service's ingestion endpoint.
 */
public interface EventForwarder {

    /**
     * @throws ForwardingException when the event could not be delivered after all attempts.
     */
    void forward(ForwardableEvent event);
}
