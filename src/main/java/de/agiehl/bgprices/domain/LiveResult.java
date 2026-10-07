package de.agiehl.bgprices.domain;

import java.math.BigDecimal;

public record LiveResult(LookupStatus status, String url, Long matchedBggId,
                         BigDecimal availablePrice, BigDecimal bestPrice, FailureCode reason) {
    public static LiveResult failed(FailureCode code) {
        return new LiveResult(LookupStatus.ERROR, null, null, null, null, code);
    }
    public static LiveResult missing(FailureCode code) {
        return new LiveResult(LookupStatus.NOT_FOUND, null, null, null, null, code);
    }
    public boolean complete() { return availablePrice != null && bestPrice != null; }
}
