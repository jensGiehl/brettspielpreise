package de.agiehl.bgprices.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("prices")
public record PriceProperties(
        @DefaultValue("https://www.brettspiel-angebote.de/") URI baseUrl,
        Path browserPath,
        @DefaultValue("true") boolean headless,
        @DefaultValue("true") boolean sandbox,
        @DefaultValue("45s") Duration browserTimeout,
        @DefaultValue("10s") Duration queueTimeout,
        @DefaultValue("45s") Duration totalTimeout,
        @DefaultValue("8") @Min(1) @Max(100) int queueCapacity,
        @DefaultValue("2") @Min(1) @Max(3) int attempts,
        @DefaultValue("1500ms") Duration minimumInterval,
        @DefaultValue("750ms") Duration retryPause,
        @DefaultValue("5m") Duration cooldown,
        URI proxy,
        @DefaultValue("false") boolean diagnosticsEnabled,
        @DefaultValue("/app/diagnostics") Path diagnosticsPath,
        @DefaultValue("20") @Min(1) @Max(100) int diagnosticFiles,
        @DefaultValue("true") boolean sourceCheckEnabled,
        @DefaultValue("false") boolean sourceCheckAtStartup) {

    @AssertTrue(message = "base-url must be an HTTPS origin without credentials, query or fragment")
    public boolean isOriginValid() {
        return baseUrl != null && "https".equals(baseUrl.getScheme()) && baseUrl.getHost() != null
                && baseUrl.getUserInfo() == null && baseUrl.getQuery() == null && baseUrl.getFragment() == null
                && (baseUrl.getPath().isEmpty() || baseUrl.getPath().equals("/"));
    }

    @AssertTrue(message = "timeouts must be positive and bounded; cooldown at most 15 minutes")
    public boolean isTimingValid() {
        return positive(browserTimeout, Duration.ofSeconds(45)) && positive(queueTimeout, Duration.ofSeconds(30))
                && positive(totalTimeout, Duration.ofSeconds(60)) && browserTimeout.compareTo(totalTimeout) <= 0
                && queueTimeout.compareTo(totalTimeout) <= 0 && nonnegative(minimumInterval, Duration.ofSeconds(10))
                && nonnegative(retryPause, Duration.ofSeconds(5)) && nonnegative(cooldown, Duration.ofMinutes(15));
    }

    @AssertTrue(message = "proxy must be an HTTP or SOCKS5 loopback URL without credentials")
    public boolean isProxyValid() {
        return proxy == null || (java.util.Set.of("http", "socks5").contains(proxy.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(proxy.getHost())
                && proxy.getPort() > 0 && proxy.getUserInfo() == null && proxy.getQuery() == null
                && proxy.getFragment() == null && (proxy.getPath().isEmpty() || proxy.getPath().equals("/")));
    }

    private boolean positive(Duration value, Duration maximum) {
        return value != null && !value.isZero() && nonnegative(value, maximum);
    }

    private boolean nonnegative(Duration value, Duration maximum) {
        return value != null && !value.isNegative() && value.compareTo(maximum) <= 0;
    }
}
