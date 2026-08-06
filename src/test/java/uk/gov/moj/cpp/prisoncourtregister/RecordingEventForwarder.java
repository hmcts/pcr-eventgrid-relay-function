package uk.gov.moj.cpp.prisoncourtregister;

import java.util.ArrayList;
import java.util.List;

import uk.gov.moj.cpp.prisoncourtregister.model.ForwardableEvent;
import uk.gov.moj.cpp.prisoncourtregister.service.EventForwarder;

/**
 * Test double that records what was relayed, and can be told to fail.
 */
final class RecordingEventForwarder implements EventForwarder {

    private final List<ForwardableEvent> forwarded = new ArrayList<>();
    private RuntimeException failure;

    void failWith(final RuntimeException failure) {
        this.failure = failure;
    }

    List<ForwardableEvent> forwarded() {
        return forwarded;
    }

    List<String> forwardedHearingIds() {
        return forwarded.stream().map(ForwardableEvent::hearingId).toList();
    }

    @Override
    public void forward(final ForwardableEvent event) {
        forwarded.add(event);
        if (failure != null) {
            throw failure;
        }
    }
}
