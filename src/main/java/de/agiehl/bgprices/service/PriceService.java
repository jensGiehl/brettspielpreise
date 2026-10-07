package de.agiehl.bgprices.service;

import de.agiehl.bgprices.browser.*;
import de.agiehl.bgprices.cache.*;
import de.agiehl.bgprices.config.PriceProperties;
import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.health.SourceStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class PriceService {
    private static final Logger LOG = LoggerFactory.getLogger(PriceService.class);
    private final GameNameNormalizer normalizer;
    private final LookupFactory lookups;
    private final BrowserWorker worker;
    private final PriceClient client;
    private final CacheStore cache;
    private final Clock clock;
    private final PriceProperties properties;
    private final MeterRegistry metrics;
    private final SourceStatus source;
    private final Map<String, CompletableFuture<PriceResponse>> inFlight = new HashMap<>();
    private final ConcurrentMap<String, Instant> startedAttempts = new ConcurrentHashMap<>();

    public PriceService(GameNameNormalizer normalizer, LookupFactory lookups, BrowserWorker worker, PriceClient client,
                         CacheStore cache, Clock clock, PriceProperties properties, MeterRegistry metrics, SourceStatus source) {
        this.normalizer = normalizer;
        this.lookups = lookups;
        this.worker = worker;
        this.client = client;
        this.cache = cache;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
        this.source = source;
    }

    public PriceResponse lookup(String name, Long bggId) {
        Deadline requestDeadline = Deadline.after(properties.totalTimeout());
        Lookup lookup = lookups.create(name, bggId);
        if (normalizer.isBundle(name) && bggId == null)
            return PriceResponse.empty(lookup, LookupStatus.SKIPPED, FailureCode.BUNDLE_WITHOUT_ID, null, null);
        if (lookup.normalizedName().isBlank())
            return PriceResponse.empty(lookup, LookupStatus.NOT_FOUND, FailureCode.EMPTY_NAME, null, null);
        Optional<PriceSnapshot> knownSnapshot = valid(lookup);
        Instant knownAttempt = lastAttempt(lookup);
        CompletableFuture<PriceResponse> future = coalesce(lookup);
        try {
            PriceResponse result = future.get(requestDeadline.remainingMillis(), TimeUnit.MILLISECONDS).forLookup(lookup);
            if (result.status() == LookupStatus.FOUND && !clock.instant().isBefore(result.expiresAt()))
                return PriceResponse.empty(lookup, LookupStatus.ERROR, FailureCode.TOTAL_TIMEOUT, result.lastAttemptAt(), null);
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return knownFallback(lookup, knownSnapshot, FailureCode.SHUTTING_DOWN, knownAttempt);
        } catch (TimeoutException | UpstreamException exception) {
            return knownFallback(lookup, knownSnapshot, FailureCode.TOTAL_TIMEOUT, knownAttempt);
        } catch (ExecutionException exception) {
            FailureCode code = exception.getCause() instanceof UpstreamException upstream ? upstream.code() : FailureCode.CACHE_UNAVAILABLE;
            return knownFallback(lookup, knownSnapshot, code, knownAttempt);
        }
    }

    private synchronized CompletableFuture<PriceResponse> coalesce(Lookup lookup) {
        var existing = inFlight.get(lookup.key());
        if (existing != null) return existing;
        var future = worker.submit(deadline -> execute(lookup, deadline));
        inFlight.put(lookup.key(), future);
        future.whenComplete((result, failure) -> remove(lookup.key(), future));
        return future;
    }

    private synchronized void remove(String key, CompletableFuture<PriceResponse> future) { inFlight.remove(key, future); }

    private PriceResponse knownFallback(Lookup lookup, Optional<PriceSnapshot> snapshot, FailureCode reason, Instant previousAttempt) {
        Instant attempt = startedAttempts.getOrDefault(lookup.key(), previousAttempt);
        if (snapshot.filter(value -> value.validAt(clock.instant())).isPresent())
            return fromCache(lookup, snapshot.get(), attempt, reason);
        return PriceResponse.empty(lookup, LookupStatus.ERROR, reason, attempt, clock.instant().plusSeconds(5));
    }

    private PriceResponse execute(Lookup lookup, Deadline deadline) {
        Optional<AttemptEntity> previous;
        try { previous = cache.attempt(lookup.key()); }
        catch (DataAccessException exception) { previous = Optional.empty(); logDatabase(exception); }
        Instant now = clock.instant();
        if (previous.isPresent() && previous.get().retryAt() != null && now.isBefore(previous.get().retryAt())) {
            AttemptEntity attempt = previous.get();
            return fallback(lookup, attempt.failureCode() == null ? FailureCode.COOLDOWN : attempt.failureCode(),
                    attempt.attemptedAt(), attempt.retryAt(), LookupStatus.ERROR);
        }
        Instant attemptedAt = clock.instant();
        startedAttempts.put(lookup.key(), attemptedAt);
        LiveResult live;
        try { live = client.fetch(lookup, deadline); }
        catch (UpstreamException exception) { live = LiveResult.failed(exception.code()); }
        catch (RuntimeException exception) {
            LOG.error("Unexpected browser failure; type={}", exception.getClass().getSimpleName());
            live = LiveResult.failed(FailureCode.BROWSER_CRASH);
        }
        source.record(live, clock.instant());
        startedAttempts.remove(lookup.key(), attemptedAt);
        if (live.status() == LookupStatus.FOUND) {
            Instant fetchedAt = clock.instant();
            PriceSnapshot snapshot = new PriceSnapshot(live.url(), live.matchedBggId(), live.availablePrice(), live.bestPrice(),
                    fetchedAt, fetchedAt.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
            if (!snapshot.complete()) {
                var old = valid(lookup);
                save(lookup, snapshot, attemptedAt, FailureCode.PARTIAL_RESULT, null);
                if (old.filter(PriceSnapshot::complete).filter(value -> value.validAt(clock.instant())).isPresent())
                    return fromCache(lookup, old.get(), attemptedAt, FailureCode.PARTIAL_RESULT);
            } else save(lookup, snapshot, attemptedAt, null, null);
            metrics.counter("bg_prices_live_success", "complete", Boolean.toString(snapshot.complete())).increment();
            return PriceResponse.found(lookup, snapshot, PriceResponse.DataSource.LIVE, attemptedAt, null);
        }
        FailureCode reason = live.reason();
        Instant retryAt = reason == FailureCode.NO_MATCH ? null : clock.instant().plus(properties.cooldown());
        save(lookup, null, attemptedAt, reason, retryAt);
        metrics.counter("bg_prices_live_failure", "reason", reason.name()).increment();
        return fallback(lookup, reason, attemptedAt, retryAt, live.status());
    }

    private PriceResponse fallback(Lookup lookup, FailureCode reason, Instant attemptedAt, Instant retryAt, LookupStatus status) {
        var snapshot = valid(lookup);
        if (snapshot.isPresent()) return fromCache(lookup, snapshot.get(), attemptedAt, reason);
        return PriceResponse.empty(lookup, status, reason, attemptedAt, retryAt);
    }

    private PriceResponse fromCache(Lookup lookup, PriceSnapshot snapshot, Instant attemptedAt, FailureCode reason) {
        if (!snapshot.validAt(clock.instant())) return PriceResponse.empty(lookup, LookupStatus.ERROR, reason, attemptedAt, null);
        metrics.counter("bg_prices_cache_fallback", "reason", reason.name()).increment();
        LOG.info("Cache fallback key={} ageSeconds={} fallbackReason={}", lookup.key(), Duration.between(snapshot.fetchedAt(), clock.instant()).toSeconds(), reason);
        return PriceResponse.found(lookup, snapshot, PriceResponse.DataSource.CACHE, attemptedAt, reason);
    }

    private Optional<PriceSnapshot> valid(Lookup lookup) {
        try { return cache.valid(lookup); }
        catch (DataAccessException exception) { logDatabase(exception); return Optional.empty(); }
    }

    private Instant lastAttempt(Lookup lookup) {
        try { return cache.attempt(lookup.key()).map(AttemptEntity::attemptedAt).orElse(null); }
        catch (DataAccessException exception) { logDatabase(exception); return null; }
    }

    private void save(Lookup lookup, PriceSnapshot snapshot, Instant attemptedAt, FailureCode code, Instant retryAt) {
        try { cache.record(lookup, snapshot, attemptedAt, code, retryAt); }
        catch (DataAccessException exception) { logDatabase(exception); }
    }

    private void logDatabase(DataAccessException exception) {
        metrics.counter("bg_prices_cache_errors").increment();
        LOG.error("Persistent cache operation failed; type={}", exception.getClass().getSimpleName());
    }
}
