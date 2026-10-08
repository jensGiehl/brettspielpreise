package de.agiehl.bgprices.browser;

import com.microsoft.playwright.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import de.agiehl.bgprices.BgPricesApplication;
import de.agiehl.bgprices.cache.CacheStore;
import de.agiehl.bgprices.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class BrowserSmoke {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(System.getenv().getOrDefault("SMOKE_DATA_PATH", "/app/data"));
        Files.createDirectories(data);
        Path probe = data.resolve("browser-smoke.txt");
        Files.writeString(probe, "UTF-8: Glasstraße", StandardCharsets.UTF_8);
        if (!Files.readString(probe, StandardCharsets.UTF_8).equals("UTF-8: Glasstraße")) throw new IllegalStateException("Volume verification failed");
        Files.delete(probe);
        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(Boolean.parseBoolean(System.getenv().getOrDefault("PRICES_HEADLESS", "true")))
                        .setChromiumSandbox(true).setTimeout(15_000));
             BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setContent("<html><body><div id='price'></div><script>setTimeout(() => document.querySelector('#price').textContent = '44.90', 50)</script></body></html>");
            page.waitForFunction("() => document.querySelector('#price').textContent === '44.90'", null,
                    new Page.WaitForFunctionOptions().setTimeout(5000));
            System.out.println("CHROMIUM_SMOKE_OK browser=" + browser.version() + " architecture=" + System.getProperty("os.arch")
                    + " headless=" + System.getenv().getOrDefault("PRICES_HEADLESS", "true"));
        }
        verifyPersistentCache(data);
    }

    private static void verifyPersistentCache(Path data) {
        String key = UUID.randomUUID().toString();
        var lookup = new Lookup("smoke", "smoke", 123L, key);
        Instant fetched = Instant.now();
        var snapshot = new PriceSnapshot("https://www.brettspiel-angebote.de/spiele/smoke/1/", 123L, BigDecimal.ONE,
                BigDecimal.ONE, fetched, fetched.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        String database = "--spring.datasource.url=jdbc:h2:file:" + data.resolve("smoke-cache").toAbsolutePath();
        try (var context = new SpringApplicationBuilder(BgPricesApplication.class).web(WebApplicationType.NONE)
                .run(database, "--logging.level.root=WARN")) {
            context.getBean(CacheStore.class).record(lookup, snapshot, fetched, null, null);
        }
        try (var context = new SpringApplicationBuilder(BgPricesApplication.class).web(WebApplicationType.NONE)
                .run(database, "--logging.level.root=WARN")) {
            if (context.getBean(CacheStore.class).valid(lookup).isEmpty()) throw new IllegalStateException("Cache restart verification failed");
        }
        System.out.println("PERSISTENT_CACHE_SMOKE_OK uid=" + System.getProperty("user.name"));
    }
}
