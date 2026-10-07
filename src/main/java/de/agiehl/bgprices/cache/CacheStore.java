package de.agiehl.bgprices.cache;

import de.agiehl.bgprices.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CacheStore {
    private final SnapshotRepository snapshots;
    private final AttemptRepository attempts;
    private final Clock clock;
    private final MeterRegistry metrics;

    public CacheStore(SnapshotRepository snapshots, AttemptRepository attempts, Clock clock, MeterRegistry metrics) {
        this.snapshots = snapshots;
        this.attempts = attempts;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public Optional<PriceSnapshot> valid(Lookup lookup) {
        Instant now = clock.instant();
        return snapshots.findByLookupKey(lookup.key()).stream().map(SnapshotEntity::snapshot)
                .filter(snapshot -> {
                    if (!snapshot.validAt(now)) metrics.counter("bg_prices_cache_expired").increment();
                    return snapshot.validAt(now);
                })
                .filter(snapshot -> lookup.bggId() == null || Objects.equals(lookup.bggId(), snapshot.matchedBggId()))
                .max(Comparator.comparing(PriceSnapshot::complete).thenComparing(PriceSnapshot::fetchedAt));
    }

    @Transactional(readOnly = true)
    public Optional<AttemptEntity> attempt(String key) { return attempts.findById(key); }

    @Transactional
    public void record(Lookup lookup, PriceSnapshot snapshot, Instant attemptedAt, FailureCode failure, Instant retryAt) {
        if (snapshot != null) {
            if (lookup.bggId() != null && !Objects.equals(lookup.bggId(), snapshot.matchedBggId()))
                throw new IllegalArgumentException("Snapshot identity mismatch");
            snapshots.save(new SnapshotEntity(lookup, snapshot));
        }
        attempts.save(new AttemptEntity(lookup.key(), attemptedAt, failure, retryAt));
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    public void cleanup() {
        Instant cutoff = clock.instant().minus(java.time.Duration.ofDays(90));
        snapshots.deleteByExpiresAtBefore(cutoff);
        attempts.deleteByAttemptedAtBefore(cutoff);
    }
}
