package de.agiehl.bgprices;

import de.agiehl.bgprices.config.PriceProperties;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;

public final class TestSettings {
    private TestSettings() { }
    public static PriceProperties properties() { return properties(URI.create("https://www.brettspiel-angebote.de/"), 8, Duration.ofSeconds(5)); }
    public static PriceProperties properties(URI origin, int capacity, Duration total) {
        return properties(origin, capacity, Duration.ofSeconds(1), total);
    }
    public static PriceProperties properties(URI origin, int capacity, Duration browserTimeout, Duration total) {
        return new PriceProperties(origin, null, true, true, browserTimeout, total.dividedBy(2), total,
                capacity, 2, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(5), null, false,
                Path.of("target/diagnostics"), 10, false, false);
    }
    public static class MutableClock extends Clock {
        private volatile Instant now;
        public MutableClock(Instant now) { this.now = now; }
        public void set(Instant value) { now = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
