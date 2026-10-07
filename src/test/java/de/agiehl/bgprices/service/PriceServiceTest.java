package de.agiehl.bgprices.service;

import de.agiehl.bgprices.TestSettings;
import de.agiehl.bgprices.browser.*;
import de.agiehl.bgprices.cache.*;
import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.health.SourceStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PriceServiceTest {
    private final Instant now = Instant.parse("2026-10-07T10:00:00Z");
    private final TestSettings.MutableClock clock = new TestSettings.MutableClock(now);
    private final PriceClient client = mock(PriceClient.class);
    private final CacheStore cache = mock(CacheStore.class);
    private final SourceStatus source = new SourceStatus();
    private BrowserWorker worker;
    private PriceService service;

    @BeforeEach
    void setup() {
        var settings = TestSettings.properties();
        var metrics = new SimpleMeterRegistry();
        worker = new BrowserWorker(settings, client, metrics);
        var normalizer = new GameNameNormalizer();
        service = new PriceService(normalizer, new LookupFactory(normalizer, settings), worker, client, cache, clock, settings, metrics, source);
        when(cache.attempt(anyString())).thenReturn(Optional.empty());
        when(cache.valid(any())).thenReturn(Optional.empty());
    }

    @AfterEach void close() throws Exception { worker.shutdown(); }

    @Test
    void skipsBundleWithoutIdAndEmptyNamesWithoutBrowser() {
        assertThat(service.lookup("Scythe Bundle", null).status()).isEqualTo(LookupStatus.SKIPPED);
        assertThat(service.lookup("(2026 (edition)) ()", null).status()).isEqualTo(LookupStatus.NOT_FOUND);
        verifyNoInteractions(client);
        when(client.fetch(any(), any())).thenReturn(LiveResult.missing(FailureCode.NO_MATCH));
        service.lookup("Scythe Bundle", 169786L);
        verify(client).fetch(any(), any());
    }

    @Test
    void coalescesConcurrentSameIdentityRequests() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.fetch(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return complete();
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.lookup("Scythe", 169786L));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var remaining = new ArrayList<Future<PriceResponse>>();
            for (int index = 0; index < 6; index++) remaining.add(executor.submit(() -> service.lookup("Scythe", 169786L)));
            Thread.sleep(100);
            release.countDown();
            assertThat(first.get().dataSource()).isEqualTo(PriceResponse.DataSource.LIVE);
            for (var future : remaining) assertThat(future.get().status()).isEqualTo(LookupStatus.FOUND);
        } finally { release.countDown(); }
        verify(client, times(1)).fetch(any(), any());
    }

    @Test
    void returnsWholeCompleteFallbackForPartialAndKeepsSourceFailureVisible() {
        var snapshot = snapshot();
        when(cache.valid(any())).thenReturn(Optional.of(snapshot));
        when(client.fetch(any(), any())).thenReturn(new LiveResult(LookupStatus.FOUND, snapshot.url(), 169786L, null, BigDecimal.ONE, null));
        var result = service.lookup("Scythe", 169786L);
        assertThat(result.dataSource()).isEqualTo(PriceResponse.DataSource.CACHE);
        assertThat(result.stale()).isTrue();
        assertThat(result.fetchedAt()).isEqualTo(snapshot.fetchedAt());
        assertThat(result.expiresAt()).isEqualTo(snapshot.expiresAt());
        assertThat(result.fallbackReason()).isEqualTo(FailureCode.PARTIAL_RESULT);
        assertThat(result.availablePrice()).isEqualByComparingTo("44.90");
        assertThat(source.current().status()).isEqualTo("DOWN");
        verify(cache).record(any(), argThat(value -> !value.complete()), eq(now), eq(FailureCode.PARTIAL_RESULT), isNull());
    }

    @Test
    void blockedFallbackNeverChangesSuccessfulTimesAndCooldownAvoidsBrowser() {
        when(cache.valid(any())).thenReturn(Optional.of(snapshot()));
        when(cache.attempt(anyString())).thenReturn(Optional.of(new AttemptEntity("key", now.minusSeconds(10), FailureCode.UPSTREAM_BLOCKED, now.plusSeconds(60))));
        var result = service.lookup("Scythe", 169786L);
        assertThat(result.fallbackReason()).isEqualTo(FailureCode.UPSTREAM_BLOCKED);
        assertThat(result.lastAttemptAt()).isEqualTo(now.minusSeconds(10));
        verifyNoInteractions(client);
        verify(cache, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void deliversVerifiedLiveDataWhenCacheWriteFails() {
        when(client.fetch(any(), any())).thenReturn(complete());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture")).when(cache).record(any(), any(), any(), any(), any());
        assertThat(service.lookup("Scythe", 169786L).dataSource()).isEqualTo(PriceResponse.DataSource.LIVE);
    }

    private LiveResult complete() { return new LiveResult(LookupStatus.FOUND, "https://www.brettspiel-angebote.de/spiele/scythe/100/", 169786L, new BigDecimal("44.90"), new BigDecimal("32.50"), null); }
    private PriceSnapshot snapshot() { return new PriceSnapshot(complete().url(), 169786L, complete().availablePrice(), complete().bestPrice(), now.minusSeconds(3600), now.minusSeconds(3600).atZone(ZoneOffset.UTC).plusMonths(1).toInstant()); }
}
