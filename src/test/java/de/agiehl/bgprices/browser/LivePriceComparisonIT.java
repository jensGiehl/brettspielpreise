package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.TestSettings;
import de.agiehl.bgprices.domain.LookupStatus;
import de.agiehl.bgprices.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_PRICE_COMPARISON_TEST", matches = "true")
class LivePriceComparisonIT {
    @Test
    void verifiesLiveScythePriceWithoutCache() {
        var settings = org.springframework.boot.context.properties.bind.Binder.get(
                new org.springframework.core.env.StandardEnvironment()).bindOrCreate("prices", TestSettings.properties().getClass());
        var urls = new UrlPolicy(settings);
        var names = new GameNameNormalizer();
        try (var client = new PlaywrightPriceClient(settings, urls, new RenderedPageParser(urls, names),
                new DiagnosticCapture(settings), new BrowserConnection(), new SimpleMeterRegistry())) {
            var result = client.fetch(new LookupFactory(names, settings).create("Scythe", 169786L), Deadline.after(Duration.ofSeconds(45)));
            assertThat(result.status()).as("Live lookup failure: %s", result.reason()).isEqualTo(LookupStatus.FOUND);
            assertThat(result.matchedBggId()).isEqualTo(169786L);
            assertThat(result.availablePrice()).isNotNull().isPositive();
        }
    }
}
