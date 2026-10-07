package de.agiehl.bgprices.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceResponse(LookupStatus status, String name, String normalizedName, Long requestedBggId,
                            Long matchedBggId, String url, String currency, BigDecimal availablePrice,
                            BigDecimal bestPrice, boolean complete, DataSource dataSource, boolean stale,
                            Instant fetchedAt, Instant expiresAt, Instant lastAttemptAt,
                            FailureCode fallbackReason, FailureCode errorCode, Instant retryAt) {
    public enum DataSource { LIVE, CACHE, NONE }

    public static PriceResponse found(Lookup lookup, PriceSnapshot snapshot, DataSource source,
                                      Instant lastAttempt, FailureCode reason) {
        return new PriceResponse(LookupStatus.FOUND, lookup.originalName(), lookup.normalizedName(), lookup.bggId(),
                snapshot.matchedBggId(), snapshot.url(), "EUR", snapshot.availablePrice(), snapshot.bestPrice(),
                snapshot.complete(), source, source == DataSource.CACHE, snapshot.fetchedAt(), snapshot.expiresAt(),
                lastAttempt, reason, null, null);
    }
    public static PriceResponse empty(Lookup lookup, LookupStatus status, FailureCode reason,
                                      Instant lastAttempt, Instant retryAt) {
        return new PriceResponse(status, lookup.originalName(), lookup.normalizedName(), lookup.bggId(), null,
                null, "EUR", null, null, false, DataSource.NONE, false, null, null, lastAttempt, null, reason, retryAt);
    }
    public PriceResponse forLookup(Lookup lookup) {
        return new PriceResponse(status, lookup.originalName(), lookup.normalizedName(), requestedBggId, matchedBggId, url, currency,
                availablePrice, bestPrice, complete, dataSource, stale, fetchedAt, expiresAt, lastAttemptAt,
                fallbackReason, errorCode, retryAt);
    }
}
