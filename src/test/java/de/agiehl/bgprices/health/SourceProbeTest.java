package de.agiehl.bgprices.health;

import de.agiehl.bgprices.browser.*;
import de.agiehl.bgprices.config.PriceProperties;
import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceProbeTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void defaultsToDailyLiveScytheCheckAnd45SecondTimeout() throws Exception {
        PriceProperties properties = properties(true);
        assertThat(properties.browserTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.totalTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.isTimingValid()).isTrue();
        var client = mock(PriceClient.class);
        var result = new LiveResult(LookupStatus.FOUND, "https://www.brettspiel-angebote.de/spiele/scythe/100/",
                169786L, BigDecimal.TEN, BigDecimal.ONE, null);
        when(client.fetch(any(), any())).thenReturn(result);
        var status = new SourceStatus();
        var worker = new BrowserWorker(properties, client, new SimpleMeterRegistry());
        try {
            probe(properties, worker, client, status).daily();
            verify(client, timeout(2000)).fetch(argThat(lookup -> lookup.normalizedName().equals("Scythe")
                    && lookup.bggId().equals(169786L)), any());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status.current())
                    .isEqualTo(new SourceStatus.State("UP", clock.instant(), clock.instant(), null, true)));
        } finally { worker.shutdown(); }
        Scheduled scheduled = SourceProbe.class.getMethod("daily").getAnnotation(Scheduled.class);
        ZoneId zone = ZoneId.of(scheduled.zone());
        var cron = CronExpression.parse(scheduled.cron());
        assertThat(cron.next(ZonedDateTime.of(2026, 10, 7, 8, 0, 0, 0, zone)))
                .isEqualTo(ZonedDateTime.of(2026, 10, 8, 8, 0, 0, 0, zone));
        assertThat(cron.next(ZonedDateTime.of(2026, 10, 24, 8, 0, 0, 0, zone)).toInstant())
                .isEqualTo(Instant.parse("2026-10-25T07:00:00Z"));
    }

    @Test
    void historicalPriceAloneDoesNotMakeTheScheduledConnectionCheckSuccessful() throws Exception {
        PriceProperties properties = properties(true);
        var client = mock(PriceClient.class);
        var result = new LiveResult(LookupStatus.FOUND, "https://www.brettspiel-angebote.de/spiele/scythe/100/",
                169786L, null, BigDecimal.ONE, null);
        when(client.fetch(any(), any())).thenReturn(result);
        var status = new SourceStatus();
        var worker = new BrowserWorker(properties, client, new SimpleMeterRegistry());
        try {
            probe(properties, worker, client, status).daily();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status.current())
                    .isEqualTo(new SourceStatus.State("DOWN", clock.instant(), null, FailureCode.NO_PRICE_DATA, false)));
        } finally { worker.shutdown(); }
    }

    @Test
    void remainsExplicitlyDisableable() throws Exception {
        PriceProperties properties = properties(false);
        var client = mock(PriceClient.class);
        var worker = new BrowserWorker(properties, client, new SimpleMeterRegistry());
        try {
            probe(properties, worker, client, new SourceStatus()).daily();
            verifyNoInteractions(client);
        } finally { worker.shutdown(); }
    }

    private PriceProperties properties(boolean enabled) {
        Map<String, Object> values = enabled ? Map.of() : Map.of("prices.source-check-enabled", false);
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("prices", PriceProperties.class);
    }

    private SourceProbe probe(PriceProperties properties, BrowserWorker worker, PriceClient client, SourceStatus status) {
        return new SourceProbe(properties, worker, client, new LookupFactory(new GameNameNormalizer(), properties), status, clock);
    }
}
