package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.domain.FailureCode;
import java.time.Duration;

public record Deadline(long endNanos) {
    public static Deadline after(Duration duration) { return new Deadline(System.nanoTime() + duration.toNanos()); }
    public long remainingMillis() {
        long remaining = endNanos - System.nanoTime();
        if (remaining <= 0) throw new UpstreamException(FailureCode.TOTAL_TIMEOUT);
        return Math.max(1, Duration.ofNanos(remaining).toMillis());
    }
    public double timeout(Duration maximum) { return Math.min(remainingMillis(), maximum.toMillis()); }
    public void pause(Duration duration) throws InterruptedException {
        if (duration.toMillis() >= remainingMillis()) throw new UpstreamException(FailureCode.TOTAL_TIMEOUT);
        Thread.sleep(duration);
        remainingMillis();
    }
}
