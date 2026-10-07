package de.agiehl.bgprices.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceSnapshot(String url, Long matchedBggId, BigDecimal availablePrice, BigDecimal bestPrice,
                            Instant fetchedAt, Instant expiresAt) {
    public PriceSnapshot {
        if (availablePrice == null && bestPrice == null) throw new IllegalArgumentException("A price is required");
        if ((availablePrice != null && availablePrice.signum() < 0) || (bestPrice != null && bestPrice.signum() < 0))
            throw new IllegalArgumentException("Negative price");
        if (!expiresAt.isAfter(fetchedAt)
                || expiresAt.isAfter(fetchedAt.atZone(java.time.ZoneOffset.UTC).plusMonths(1).toInstant()))
            throw new IllegalArgumentException("Invalid expiry");
    }

    public boolean complete() { return availablePrice != null && bestPrice != null; }
    public boolean validAt(Instant now) { return !now.isBefore(fetchedAt) && now.isBefore(expiresAt); }
}
