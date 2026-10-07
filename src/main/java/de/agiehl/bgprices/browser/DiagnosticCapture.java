package de.agiehl.bgprices.browser;

import com.microsoft.playwright.Page;
import de.agiehl.bgprices.config.PriceProperties;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticCapture {
    private static final Logger LOG = LoggerFactory.getLogger(DiagnosticCapture.class);
    private final PriceProperties properties;

    public DiagnosticCapture(PriceProperties properties) { this.properties = properties; }

    public Observation observe(Page page) {
        var observation = new Observation();
        if (!properties.diagnosticsEnabled()) return observation;
        observation.add("browser headless=" + properties.headless() + " sandbox=" + properties.sandbox()
                + " proxyConfigured=" + (properties.proxy() != null));
        page.onResponse(response -> {
            if (response.status() >= 400) observation.httpErrors++;
            observation.add("response status=" + response.status() + " method=" + response.request().method()
                    + " type=" + response.request().resourceType() + " url=" + resourceUrl(response.url()));
        });
        page.onRequestFailed(request -> {
            observation.requestFailures++;
            observation.add("request-failed method=" + request.method() + " type=" + request.resourceType()
                    + " url=" + resourceUrl(request.url()) + " failure=" + request.failure());
        });
        page.onConsoleMessage(message -> {
            if (message.type().equals("error") || message.type().equals("warning")) {
                if (message.type().equals("error")) observation.consoleErrors++;
                observation.add("console type=" + message.type() + " text=" + message.text());
            }
        });
        page.onPageError(error -> {
            observation.javascriptErrors++;
            observation.add("javascript-error text=" + error);
        });
        return observation;
    }

    public void capture(Page page, Deadline deadline, Observation observation) {
        if (!properties.diagnosticsEnabled()) return;
        try {
            Files.createDirectories(properties.diagnosticsPath());
            cleanup();
            String stem = "failure-" + UUID.randomUUID();
            Files.write(properties.diagnosticsPath().resolve(stem + ".txt"), observation.report(), StandardCharsets.UTF_8);
            LOG.warn("Browser diagnostics httpErrors={} requestFailures={} javascriptErrors={} consoleErrors={} report={}.txt",
                    observation.httpErrors, observation.requestFailures, observation.javascriptErrors, observation.consoleErrors, stem);
            String html = page.content();
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= 1_048_576) Files.write(properties.diagnosticsPath().resolve(stem + ".html"), bytes);
            if (System.nanoTime() < deadline.endNanos()) {
                page.screenshot(new Page.ScreenshotOptions().setPath(properties.diagnosticsPath().resolve(stem + ".png"))
                        .setTimeout(deadline.timeout(java.time.Duration.ofSeconds(2))).setFullPage(false));
            }
            cleanup();
        } catch (RuntimeException | IOException exception) { LOG.warn("Diagnostic capture failed; type={}", exception.getClass().getSimpleName()); }
    }

    private void cleanup() throws IOException {
        try (var paths = Files.list(properties.diagnosticsPath())) {
            var owned = paths.filter(path -> path.getFileName().toString().matches("failure-[a-f0-9-]+\\.(html|png|txt)"))
                    .sorted(Comparator.comparingLong(this::modified).reversed()).toList();
            for (int index = 0; index < owned.size(); index++) {
                Path path = owned.get(index);
                if (index >= properties.diagnosticFiles() || modified(path) < System.currentTimeMillis() - 86_400_000L)
                    Files.deleteIfExists(path);
            }
        }
    }

    private long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException exception) { return 0; }
    }

    private String resourceUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null)
                return "[non-http-resource]";
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getRawPath();
        } catch (IllegalArgumentException exception) { return "[invalid-url]"; }
    }

    public static final class Observation {
        private static final int MAX_EVENTS = 200;
        private static final int MAX_EVENT_LENGTH = 1000;
        private final Deque<String> events = new ArrayDeque<>();
        private int dropped;
        private int httpErrors;
        private int requestFailures;
        private int javascriptErrors;
        private int consoleErrors;

        void add(String event) {
            if (events.size() == MAX_EVENTS) {
                events.removeFirst();
                dropped++;
            }
            String line = event.replaceAll("[\\p{Cntrl}]", " ");
            events.addLast(line.substring(0, Math.min(line.length(), MAX_EVENT_LENGTH)));
        }

        List<String> report() {
            var lines = new java.util.ArrayList<String>();
            lines.add("httpErrors=" + httpErrors + " requestFailures=" + requestFailures
                    + " javascriptErrors=" + javascriptErrors + " consoleErrors=" + consoleErrors + " droppedEvents=" + dropped);
            lines.addAll(events);
            return List.copyOf(lines);
        }
    }
}
