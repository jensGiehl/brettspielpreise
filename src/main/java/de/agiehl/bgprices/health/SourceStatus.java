package de.agiehl.bgprices.health;

import de.agiehl.bgprices.domain.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class SourceStatus {
    public record State(String status, Instant checkedAt, Instant lastLiveAvailableAt, FailureCode errorCode,
                        boolean complete) { }
    private final AtomicReference<State> state = new AtomicReference<>(new State("UNKNOWN", null, null, null, false));

    public void record(LiveResult result, Instant now) {
        boolean success = result.status() == LookupStatus.FOUND && result.availablePrice() != null;
        state.updateAndGet(previous -> new State(success ? "UP" : "DOWN", now,
                success ? now : previous.lastLiveAvailableAt(), success ? null
                : result.reason() == null ? FailureCode.NO_PRICE_DATA : result.reason(), result.complete()));
    }

    public State current() { return state.get(); }
}
