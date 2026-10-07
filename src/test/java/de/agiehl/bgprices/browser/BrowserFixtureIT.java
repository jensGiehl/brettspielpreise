package de.agiehl.bgprices.browser;

import com.microsoft.playwright.Browser;
import com.sun.net.httpserver.*;
import de.agiehl.bgprices.TestSettings;
import de.agiehl.bgprices.config.PriceProperties;
import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_BROWSER_TESTS", matches = "true")
class BrowserFixtureIT {
    private HttpsServer server;
    private ExecutorService serverExecutor;
    private PlaywrightPriceClient client;
    private LookupFactory lookups;
    private BrowserConnection connection;
    private volatile String mode;
    private final List<String> searches = new CopyOnWriteArrayList<>();
    private final List<String> cookies = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger challengePosts = new java.util.concurrent.atomic.AtomicInteger();
    @TempDir Path diagnosticDirectory;

    @BeforeEach
    void setup() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = getClass().getResourceAsStream("/fixture.p12")) { store.load(input, "fixture-password".toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "fixture-password".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("::1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.createContext("/", this::respond);
        server.start();
        var settings = TestSettings.properties(URI.create("https://[::1]:" + server.getAddress().getPort() + "/"),
                8, Duration.ofSeconds(5), Duration.ofSeconds(30));
        var urls = new UrlPolicy(settings);
        var names = new GameNameNormalizer();
        connection = new BrowserConnection();
        client = new PlaywrightPriceClient(settings, urls, new RenderedPageParser(urls, names), new DiagnosticCapture(settings), connection, new SimpleMeterRegistry()) {
            @Override protected Browser.NewContextOptions contextOptions() { return super.contextOptions().setIgnoreHTTPSErrors(true); }
        };
        lookups = new LookupFactory(names, settings);
        mode = "direct";
    }

    @AfterEach
    void close() {
        try { client.close(); }
        finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void followsDirectRedirectExecutesJavaScriptAndPreservesCookiesOverIpv6() {
        var result = fetch("Die Glasstraße", 123L);
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.availablePrice()).isEqualByComparingTo("44.90");
        assertThat(result.bestPrice()).isEqualByComparingTo("32.50");
        assertThat(searches).contains("Die Glasstraße");
        assertThat(cookies).allMatch(cookie -> cookie.contains("bunny_shield_fixture=present"));
        assertThat(connection.latest().ipv6()).isTrue();
        assertThat(connection.latest().remoteAddress()).contains(":");
        client.close();
        assertThat(fetch("Die Glasstraße", 123L).status()).isEqualTo(LookupStatus.FOUND);
    }

    @Test
    void recoversFromAnActualBrowserProcessCrash() throws Exception {
        var previous = ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        assertThat(fetch("Die Glasstraße", 123L).status()).isEqualTo(LookupStatus.FOUND);
        var processes = ProcessHandle.current().descendants().filter(process -> !previous.contains(process.pid()))
                .filter(process -> process.info().command().orElse("").toLowerCase(Locale.ROOT).contains("headless"))
                .toList();
        assertThat(processes).isNotEmpty();
        for (var process : processes) process.destroyForcibly();
        for (var process : processes) process.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(fetch("Die Glasstraße", 123L).status()).isEqualTo(LookupStatus.FOUND);
    }

    @Test
    void allowsA403ChallengeToFinishBeforeParsing() {
        mode = "resolving";
        var result = fetch("Die Glasstraße", 123L);
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
    }

    @Test
    void preservesHttpOnlyVerificationCookiesAcrossFetchAndReload() {
        mode = "cookie-challenge";
        var settings = TestSettings.properties(URI.create("https://[::1]:" + server.getAddress().getPort() + "/"),
                8, Duration.ofSeconds(5), Duration.ofSeconds(30));
        var diagnosticSettings = new PriceProperties(settings.baseUrl(), settings.browserPath(), settings.headless(),
                settings.sandbox(), settings.browserTimeout(), settings.queueTimeout(), settings.totalTimeout(),
                settings.queueCapacity(), settings.attempts(), settings.minimumInterval(), settings.retryPause(),
                settings.cooldown(), settings.proxy(), true, diagnosticDirectory, settings.diagnosticFiles(),
                settings.sourceCheckEnabled(), settings.sourceCheckAtStartup());
        var observed = new java.util.concurrent.atomic.AtomicReference<DiagnosticCapture.Observation>();
        var diagnostics = new DiagnosticCapture(diagnosticSettings) {
            @Override public Observation observe(com.microsoft.playwright.Page page) {
                var observation = super.observe(page);
                observed.set(observation);
                return observation;
            }
        };
        var urls = new UrlPolicy(diagnosticSettings);
        client.close();
        client = new PlaywrightPriceClient(diagnosticSettings, urls, new RenderedPageParser(urls, new GameNameNormalizer()),
                diagnostics, connection, new SimpleMeterRegistry()) {
            @Override protected Browser.NewContextOptions contextOptions() { return super.contextOptions().setIgnoreHTTPSErrors(true); }
        };
        assertThat(fetch("Die Glasstraße", 123L).status()).isEqualTo(LookupStatus.FOUND);
        assertThat(challengePosts.get()).isEqualTo(1);
        assertThat(cookies).allMatch(cookie -> cookie.contains("verification_fixture=accepted"));
        assertThat(observed.get().report()).anySatisfy(line -> assertThat(line).contains("response-cookies", "setCookieNames=[verification_fixture]"));
        assertThat(observed.get().report()).anySatisfy(line -> assertThat(line).contains("request-cookies", "verification_fixture", "blockedReasons=[]"));
        assertThat(String.join("\n", observed.get().report())).doesNotContain("accepted");
    }

    @Test
    void waitsForGenericLoadingPagesOnHomeAndSearchUntilExpectedContentArrives() {
        mode = "loading-home";
        assertThat(fetch("Die Glasstraße", 123L).status()).isEqualTo(LookupStatus.FOUND);
        mode = "loading-search";
        assertThat(fetch("Die Glasstraße", 123L).status()).isEqualTo(LookupStatus.FOUND);
        assertThat(cookies).allMatch(cookie -> cookie.contains("bunny_shield_fixture=present"));
    }

    @Test
    void selectsListByBggAndRevalidatesFinalIdentity() {
        mode = "list";
        assertThat(fetch("Die Glasstraße", 123L).status()).isEqualTo(LookupStatus.FOUND);
        mode = "wrong";
        assertThat(fetch("Die Glasstraße", 123L).reason()).isEqualTo(FailureCode.IDENTITY_MISMATCH);
        mode = "missing-id";
        assertThat(fetch("Die Glasstraße", 123L).reason()).isEqualTo(FailureCode.IDENTITY_UNCONFIRMED);
    }

    @Test
    void distinguishesChallengeEmptyUnknownAndPartialStates() {
        mode = "blocked";
        assertThat(fetch("Die Glasstraße", 123L).reason()).isEqualTo(FailureCode.UPSTREAM_BLOCKED);
        mode = "empty";
        assertThat(fetch("Die Glasstraße", 123L).reason()).isEqualTo(FailureCode.NO_MATCH);
        mode = "unknown";
        assertThat(fetch("Die Glasstraße", 123L).reason()).isEqualTo(FailureCode.PARSER_ERROR);
        mode = "partial";
        var result = fetch("Die Glasstraße", 123L);
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.availablePrice()).isNull();
        assertThat(result.bestPrice()).isEqualByComparingTo("32.50");
        mode = "external";
        assertThat(fetch("Die Glasstraße", 123L).reason()).isEqualTo(FailureCode.UNSAFE_NAVIGATION);
    }

    private LiveResult fetch(String name, Long id) { return client.fetch(lookups.create(name, id), Deadline.after(Duration.ofSeconds(30))); }

    private void respond(HttpExchange exchange) {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String html;
            if (path.equals("/fixture-verify") && exchange.getRequestMethod().equals("POST")) {
                challengePosts.incrementAndGet();
                exchange.getResponseHeaders().add("Set-Cookie", "verification_fixture=accepted; Path=/; Secure; HttpOnly; SameSite=Lax");
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            if (path.equals("/")) {
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                if (mode.equals("cookie-challenge") && (cookie == null || !cookie.contains("verification_fixture=accepted"))) {
                    byte[] body = ("<h1>Establishing a secure connection</h1><script>"
                            + "fetch('/fixture-verify', {method:'POST', redirect:'manual'})"
                            + ".then(() => location.reload())</script>").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(403, body.length);
                    exchange.getResponseBody().write(body);
                    return;
                }
                if (mode.equals("loading-home") && exchange.getRequestURI().getQuery() == null) {
                    loading(exchange, "/?ready=true");
                    return;
                }
                html = "<html><body><script>document.cookie='bunny_shield_fixture=present; path=/; secure'</script><form action='/suche/'><input name='s'></form></body></html>";
            } else if (path.equals("/suche/") && exchange.getRequestURI().getRawQuery() != null) {
                searches.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring(2), StandardCharsets.UTF_8));
                cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
                if (mode.equals("loading-search")) {
                    loading(exchange, "/spiele/glasstrasse/100/");
                    return;
                }
                exchange.getResponseHeaders().add("Location", mode.equals("external") ? "https://example.invalid/" : mode.equals("list")
                        || mode.equals("empty") || mode.equals("unknown") ? "/suche/results/" : "/spiele/glasstrasse/100/");
                exchange.sendResponseHeaders(302, -1);
                return;
            } else if (path.equals("/suche/results/")) {
                html = mode.equals("empty") ? "<div class='no-results'>Keine Treffer</div>" : mode.equals("unknown") ? "<h1>Unexpected markup</h1>"
                        : "<div class='item-box'><a href='/spiele/wrong/99/'>wrong</a><a href='https://boardgamegeek.com/boardgame/1234'>BGG</a></div>"
                        + "<div class='item-box'><a href='/spiele/glasstrasse/100/'>Die Glasstraße</a><a href='https://boardgamegeek.com/boardgame/123'>BGG</a></div>";
            } else if (path.startsWith("/spiele/")) {
                cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
                if (mode.equals("resolving") && !exchange.getRequestURI().toString().contains("resolved")) {
                    byte[] body = "<h1>Establishing a secure connection</h1><script>setTimeout(() => location.href='/spiele/glasstrasse/100/?resolved=true', 80)</script>".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(403, body.length);
                    exchange.getResponseBody().write(body);
                    return;
                }
                html = "<h1>Die Glasstraße</h1>" + (mode.equals("missing-id") ? "" : "<a href='https://boardgamegeek.com/boardgame/" + (mode.equals("wrong") ? "1234" : "123") + "'>BGG</a>")
                        + (mode.equals("blocked") ? "<h2>Hold tight</h2>" : "")
                        + "<div itemprop='offers'><meta itemprop='lowPrice'></div><div id='history'></div><script>setTimeout(() => {"
                        + (mode.equals("partial") ? "" : "document.querySelector('[itemprop=lowPrice]').content='44.90';")
                        + "document.querySelector('#history').setAttribute('data-absolute-bestprice','32.50');}, 80)</script>";
            } else { exchange.sendResponseHeaders(404, -1); return; }
            byte[] body = ("<!doctype html><html><head><title>Fixture</title></head><body>" + html + "</body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private void loading(HttpExchange exchange, String target) throws java.io.IOException {
        byte[] body = ("<h1>Loading...</h1><script>setTimeout(() => location.href='" + target + "', 150)</script>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }
}
