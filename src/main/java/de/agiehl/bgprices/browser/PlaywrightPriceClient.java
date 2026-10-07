package de.agiehl.bgprices.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;
import de.agiehl.bgprices.config.PriceProperties;
import de.agiehl.bgprices.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PlaywrightPriceClient implements PriceClient {
    private static final Logger LOG = LoggerFactory.getLogger(PlaywrightPriceClient.class);
    private static final String EXPECTED_CONTENT = """
            step => {
              const text = (document.title + ' ' + (document.body?.innerText || '')).toLowerCase();
              if (['establishing a secure connection', 'hold tight', 'enable javascript and cookies to continue',
                'verify you are human'].some(value => text.includes(value))
                || document.querySelector('#challenge-form, .cf-challenge, [data-sitekey]')) return false;
              if (step === 'home') return !!document.querySelector(
                'input[name="s"], form[action*="/suche"], a[href*="/suche"]');
              if (/^\\/spiele\\/[^/]+\\/[0-9]+\\/?$/.test(location.pathname)) {
                return !!document.querySelector('h1') && (!!document.querySelector(
                  '[itemprop=offers], [data-absolute-bestprice]') || Array.from(document.querySelectorAll('a[href]'))
                  .some(link => {
                    const url = new URL(link.href, location.href);
                    return ['boardgamegeek.com', 'www.boardgamegeek.com'].includes(url.hostname)
                      && /^\\/boardgame\\/[1-9][0-9]*(?:\\/|$)/.test(url.pathname);
                  }));
              }
              return !!document.querySelector('.item-box, [data-search-empty], .search-no-results, .no-results')
                || /keine (suchergebnisse|treffer|spiele gefunden)|no results found/.test(text);
            }
            """;
    private static final String COMPLETE_PRICES = """
            () => !!document.querySelector('[itemprop=offers] meta[itemprop=lowPrice]')?.getAttribute('content')
              && !!document.querySelector('[data-absolute-bestprice]')?.getAttribute('data-absolute-bestprice')
            """;
    private static final Duration PRICE_SETTLE_TIMEOUT = Duration.ofSeconds(12);
    private final PriceProperties properties;
    private final UrlPolicy urls;
    private final RenderedPageParser parser;
    private final DiagnosticCapture diagnostics;
    private final BrowserConnection connection;
    private final MeterRegistry metrics;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private boolean started;
    private final AtomicBoolean unsafeNavigation = new AtomicBoolean();
    private Page activePage;

    public PlaywrightPriceClient(PriceProperties properties, UrlPolicy urls, RenderedPageParser parser,
                                 DiagnosticCapture diagnostics, BrowserConnection connection, MeterRegistry metrics) {
        this.properties = properties;
        this.urls = urls;
        this.parser = parser;
        this.diagnostics = diagnostics;
        this.connection = connection;
        this.metrics = metrics;
    }

    @Override
    public LiveResult fetch(Lookup lookup, Deadline deadline) {
        FailureCode last = FailureCode.BROWSER_CRASH;
        for (int attempt = 1; attempt <= properties.attempts(); attempt++) {
            Page page = null;
            DiagnosticCapture.Observation observation = null;
            try {
                deadline.remainingMillis();
                ensureBrowser(deadline);
                page = context.newPage();
                observation = diagnostics.observe(page);
                activePage = page;
                unsafeNavigation.set(false);
                attachNetworkDiagnostic(page);
                if (!started) {
                    navigate(page, properties.baseUrl().toString(), "home", deadline, unsafeNavigation);
                    started = true;
                }
                String search = properties.baseUrl().resolve("suche/?s="
                        + URLEncoder.encode(lookup.normalizedName(), StandardCharsets.UTF_8)).toString();
                navigate(page, search, "search", deadline, unsafeNavigation);
                Document document = parser.document(page.content(), page.url());
                if (!urls.detail(page.url())) {
                    String detail = parser.selectDetail(document, lookup);
                    if (detail == null) return LiveResult.missing(FailureCode.NO_MATCH);
                    navigate(page, detail, "detail", deadline, unsafeNavigation);
                    document = parser.document(page.content(), page.url());
                }
                LiveResult result = parser.detail(document, lookup);
                LOG.info("Browser lookup attempt={} status={} complete={}", attempt, result.status(), result.complete());
                return result;
            } catch (UpstreamException exception) {
                last = exception.code();
                if (page != null && observation != null) diagnostics.capture(page, deadline, observation);
            } catch (TimeoutError exception) {
                last = FailureCode.NAVIGATION_TIMEOUT;
                if (page != null && observation != null) diagnostics.capture(page, deadline, observation);
            } catch (PlaywrightException exception) {
                last = browser == null || !browser.isConnected() ? FailureCode.BROWSER_CRASH : FailureCode.NETWORK_ERROR;
                if (page != null && observation != null) diagnostics.capture(page, deadline, observation);
            } finally {
                if (page != null) try { page.close(); } catch (PlaywrightException ignored) { started = false; }
                activePage = null;
            }
            LOG.warn("Browser lookup attempt={} errorCode={}", attempt, last);
            if (!retryable(last) || attempt == properties.attempts()) break;
            close();
            metrics.counter("bg_prices_browser_restarts").increment();
            try { deadline.pause(properties.retryPause().plus(properties.minimumInterval())); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return LiveResult.failed(FailureCode.SHUTTING_DOWN); }
            catch (UpstreamException exception) { return LiveResult.failed(exception.code()); }
        }
        return LiveResult.failed(last);
    }

    private void ensureBrowser(Deadline deadline) {
        if (browser != null && browser.isConnected()) return;
        close();
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        var options = new BrowserType.LaunchOptions().setHeadless(properties.headless())
                .setChromiumSandbox(properties.sandbox()).setTimeout(deadline.timeout(properties.browserTimeout()));
        if (properties.browserPath() != null) options.setExecutablePath(properties.browserPath());
        if (properties.proxy() != null) options.setProxy(new Proxy(properties.proxy().toString()));
        browser = playwright.chromium().launch(options);
        context = browser.newContext(contextOptions());
        context.route("**/*", route -> {
            Request request = route.request();
            if (request.isNavigationRequest() && request.frame().parentFrame() == null
                    && (!urls.allowed(request.url()) || request.frame().page() != activePage)) {
                unsafeNavigation.set(true);
                route.abort();
            } else route.resume();
        });
        context.onPage(popup -> popup.onPopup(Page::close));
    }

    protected Browser.NewContextOptions contextOptions() {
        return new Browser.NewContextOptions().setLocale("de-DE").setTimezoneId("Europe/Berlin")
                .setAcceptDownloads(false).setServiceWorkers(com.microsoft.playwright.options.ServiceWorkerPolicy.BLOCK)
                .setExtraHTTPHeaders(Map.of("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7"));
    }

    private void navigate(Page page, String url, String step, Deadline deadline, AtomicBoolean unsafe) {
        urls.checked(url);
        long start = System.nanoTime();
        page.setDefaultTimeout(deadline.timeout(properties.browserTimeout()));
        AtomicInteger finalStatus = new AtomicInteger();
        java.util.function.Consumer<Response> statusListener = received -> {
            if (received.request().isNavigationRequest() && received.request().frame() == page.mainFrame())
                finalStatus.set(received.status());
        };
        page.onResponse(statusListener);
        try {
            Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(deadline.timeout(properties.browserTimeout())));
            urls.checked(page.url());
            LOG.info("Navigation step={} url={} httpStatus={} finalUrl={} elapsedMs={} title={}", step, safe(url),
                    response == null ? 0 : response.status(), safe(page.url()), Duration.ofNanos(System.nanoTime() - start).toMillis(), safe(page.title()));
            try {
                page.waitForFunction(EXPECTED_CONTENT, step,
                        new Page.WaitForFunctionOptions().setTimeout(deadline.timeout(properties.browserTimeout())));
            } catch (TimeoutError exception) {
                if (unsafe.get()) throw new UpstreamException(FailureCode.UNSAFE_NAVIGATION);
                if (parser.challenge(org.jsoup.Jsoup.parse(page.content())) || finalStatus.get() == 403 || finalStatus.get() == 429)
                    throw new UpstreamException(FailureCode.UPSTREAM_BLOCKED);
                if (finalStatus.get() < 200 || finalStatus.get() >= 400)
                    throw new UpstreamException(FailureCode.UPSTREAM_HTTP_ERROR);
                throw new UpstreamException(FailureCode.PARSER_ERROR);
            }
            urls.checked(page.url());
            int status = finalStatus.get();
            LOG.info("Navigation settled step={} httpStatus={} finalUrl={} elapsedMs={}", step, status, safe(page.url()),
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
            if (status == 403 || status == 429) throw new UpstreamException(FailureCode.UPSTREAM_BLOCKED);
            if (status < 200 || status >= 400) throw new UpstreamException(FailureCode.UPSTREAM_HTTP_ERROR);
            if (urls.detail(page.url())) {
                Duration priceTimeout = properties.browserTimeout().compareTo(PRICE_SETTLE_TIMEOUT) < 0
                        ? properties.browserTimeout() : PRICE_SETTLE_TIMEOUT;
                try {
                    page.waitForFunction(COMPLETE_PRICES, null,
                            new Page.WaitForFunctionOptions().setTimeout(deadline.timeout(priceTimeout)));
                } catch (TimeoutError ignored) { }
            }
            urls.checked(page.url());
            if (unsafe.get()) throw new UpstreamException(FailureCode.UNSAFE_NAVIGATION);
            parser.document(page.content(), page.url());
            deadline.remainingMillis();
        } catch (PlaywrightException exception) {
            if (unsafe.get()) throw new UpstreamException(FailureCode.UNSAFE_NAVIGATION);
            throw exception;
        } finally { page.offResponse(statusListener); }
    }

    private void attachNetworkDiagnostic(Page page) {
        CDPSession session = context.newCDPSession(page);
        String mainFrame = session.send("Page.getFrameTree").getAsJsonObject("frameTree")
                .getAsJsonObject("frame").get("id").getAsString();
        com.google.gson.JsonObject fetchOptions = new com.google.gson.JsonObject();
        com.google.gson.JsonArray patterns = new com.google.gson.JsonArray();
        com.google.gson.JsonObject pattern = new com.google.gson.JsonObject();
        pattern.addProperty("urlPattern", "*");
        pattern.addProperty("resourceType", "Document");
        pattern.addProperty("requestStage", "Request");
        patterns.add(pattern);
        fetchOptions.add("patterns", patterns);
        session.on("Fetch.requestPaused", event -> {
            String target = event.getAsJsonObject("request").get("url").getAsString();
            var command = new com.google.gson.JsonObject();
            command.addProperty("requestId", event.get("requestId").getAsString());
            if (mainFrame.equals(event.get("frameId").getAsString()) && !urls.allowed(target)) {
                unsafeNavigation.set(true);
                command.addProperty("errorReason", "BlockedByClient");
                session.send("Fetch.failRequest", command);
            } else session.send("Fetch.continueRequest", command);
        });
        session.send("Fetch.enable", fetchOptions);
        session.send("Network.enable");
        session.on("Network.responseReceived", event -> {
            var response = event.getAsJsonObject("response");
            if (response.has("url") && urls.allowed(response.get("url").getAsString()) && response.has("remoteIPAddress")) {
                String address = response.get("remoteIPAddress").getAsString();
                connection.observed(address, properties.proxy() != null);
                LOG.debug("Chromium connection remoteAddress={} proxyConfigured={}", address, properties.proxy() != null);
            }
        });
    }

    private boolean retryable(FailureCode code) {
        return java.util.Set.of(FailureCode.BROWSER_CRASH, FailureCode.NETWORK_ERROR, FailureCode.NAVIGATION_TIMEOUT).contains(code);
    }

    private String safe(String value) {
        if (value.startsWith("https://")) {
            var uri = java.net.URI.create(value);
            value = uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
        }
        value = value.replaceAll("[\\r\\n\\t]", " ");
        return value.substring(0, Math.min(value.length(), 200));
    }

    @Override
    public void close() {
        started = false;
        if (context != null) try { context.close(); } catch (PlaywrightException ignored) { }
        if (browser != null) try { browser.close(); } catch (PlaywrightException ignored) { }
        if (playwright != null) try { playwright.close(); } catch (PlaywrightException ignored) { }
        context = null;
        browser = null;
        playwright = null;
    }
}
