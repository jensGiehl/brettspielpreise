package de.agiehl.bgprices.domain;

import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class PriceSnapshotTest {
    @ParameterizedTest
    @CsvSource({"2024-01-31T10:00:00Z,2024-02-29T10:00:00Z", "2025-01-31T10:00:00Z,2025-02-28T10:00:00Z",
            "2026-03-31T10:00:00Z,2026-04-30T10:00:00Z", "2026-10-07T10:00:00Z,2026-11-07T10:00:00Z"})
    void expiresAtOneCalendarMonth(String collected, String expected) {
        Instant fetched = Instant.parse(collected);
        Instant expiry = fetched.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        var snapshot = new PriceSnapshot("https://example.org", 1L, BigDecimal.ONE, null, fetched, expiry);
        assertThat(expiry).isEqualTo(Instant.parse(expected));
        assertThat(snapshot.validAt(expiry.minusNanos(1))).isTrue();
        assertThat(snapshot.validAt(expiry)).isFalse();
        assertThat(snapshot.validAt(expiry.plusNanos(1))).isFalse();
        assertThatThrownBy(() -> new PriceSnapshot("u", null, BigDecimal.ONE, null, fetched, expiry.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
