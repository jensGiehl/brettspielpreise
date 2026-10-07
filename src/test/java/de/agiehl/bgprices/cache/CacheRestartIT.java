package de.agiehl.bgprices.cache;

import de.agiehl.bgprices.BgPricesApplication;
import de.agiehl.bgprices.TestSettings;
import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.service.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

class CacheRestartIT {
    @TempDir Path directory;
    private static final TestSettings.MutableClock CLOCK = new TestSettings.MutableClock(Instant.parse("2026-01-31T10:00:00Z"));

    @Configuration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary Clock fixedClock() { return CLOCK; }
    }

    @Test
    void persistsAcrossRestartPreservesFullAndExpiresExactly() {
        CLOCK.set(Instant.parse("2026-01-31T10:00:00Z"));
        Instant fetched = CLOCK.instant();
        Instant expiry = fetched.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        Lookup lookup;
        try (var context = start()) {
            lookup = context.getBean(LookupFactory.class).create("Scythe", 169786L);
            context.getBean(CacheStore.class).record(lookup, snapshot(fetched, expiry, true), fetched, null, null);
        }
        try (var context = start()) {
            var cache = context.getBean(CacheStore.class);
            assertThat(cache.valid(lookup)).isPresent();
            CLOCK.set(fetched.plusSeconds(60));
            cache.record(lookup, snapshot(CLOCK.instant(), CLOCK.instant().atZone(ZoneOffset.UTC).plusMonths(1).toInstant(), false), CLOCK.instant(), FailureCode.PARTIAL_RESULT, null);
            cache.record(lookup, null, CLOCK.instant(), FailureCode.UPSTREAM_BLOCKED, CLOCK.instant().plusSeconds(300));
            var result = cache.valid(lookup).orElseThrow();
            assertThat(result.complete()).isTrue();
            assertThat(result.fetchedAt()).isEqualTo(fetched);
            assertThat(result.expiresAt()).isEqualTo(expiry);
            assertThat(cache.valid(context.getBean(LookupFactory.class).create("Scythe", 123L))).isEmpty();
            assertThat(cache.valid(context.getBean(LookupFactory.class).create("Scythe", null))).isEmpty();
            CLOCK.set(expiry.minusNanos(1));
            assertThat(cache.valid(lookup).orElseThrow().complete()).isTrue();
            CLOCK.set(expiry);
            assertThat(cache.valid(lookup).orElseThrow().complete()).isFalse();
            CLOCK.set(expiry.plusSeconds(60));
            assertThat(cache.valid(lookup)).isEmpty();
        }
    }

    @Test
    void failsOnUnreadableDatabaseInsteadOfReplacingIt() throws Exception {
        Path database = directory.resolve("prices.mv.db");
        byte[] invalid = "This is an intentionally invalid database".repeat(200).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(database, invalid);
        assertThatThrownBy(() -> { try (var ignored = start()) { } }).isInstanceOf(RuntimeException.class);
        assertThat(java.nio.file.Files.readAllBytes(database)).isEqualTo(invalid);
    }

    private PriceSnapshot snapshot(Instant fetched, Instant expiry, boolean full) {
        return new PriceSnapshot("https://www.brettspiel-angebote.de/spiele/scythe/100/", 169786L,
                new BigDecimal("44.90"), full ? new BigDecimal("32.50") : null, fetched, expiry);
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(BgPricesApplication.class, FixedTime.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:h2:file:" + directory.resolve("prices").toAbsolutePath().toString().replace('\\', '/'),
                        "--logging.level.root=WARN", "--prices.minimum-interval=0ms");
    }
}
