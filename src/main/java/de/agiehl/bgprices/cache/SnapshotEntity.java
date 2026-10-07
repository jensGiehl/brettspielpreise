package de.agiehl.bgprices.cache;

import de.agiehl.bgprices.domain.Lookup;
import de.agiehl.bgprices.domain.PriceSnapshot;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_snapshot")
public class SnapshotEntity {
    @Id @Column(length = 72) private String id;
    @Column(nullable = false, length = 64) private String lookupKey;
    @Column(nullable = false, length = 300) private String normalizedName;
    private Long requestedBggId;
    private Long matchedBggId;
    @Column(nullable = false, length = 2048) private String url;
    @Column(precision = 12, scale = 2) private BigDecimal availablePrice;
    @Column(precision = 12, scale = 2) private BigDecimal bestPrice;
    @Column(nullable = false) private boolean complete;
    @Column(nullable = false) private Instant fetchedAt;
    @Column(nullable = false) private Instant expiresAt;

    protected SnapshotEntity() { }

    public SnapshotEntity(Lookup lookup, PriceSnapshot snapshot) {
        id = lookup.key() + (snapshot.complete() ? ":full" : ":part");
        lookupKey = lookup.key();
        normalizedName = lookup.normalizedName();
        requestedBggId = lookup.bggId();
        matchedBggId = snapshot.matchedBggId();
        url = snapshot.url();
        availablePrice = snapshot.availablePrice();
        bestPrice = snapshot.bestPrice();
        complete = snapshot.complete();
        fetchedAt = snapshot.fetchedAt();
        expiresAt = snapshot.expiresAt();
    }

    public PriceSnapshot snapshot() { return new PriceSnapshot(url, matchedBggId, availablePrice, bestPrice, fetchedAt, expiresAt); }
}
