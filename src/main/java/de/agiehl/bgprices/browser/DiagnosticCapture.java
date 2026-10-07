package de.agiehl.bgprices.browser;

import com.microsoft.playwright.Page;
import de.agiehl.bgprices.config.PriceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticCapture {
    private static final Logger LOG = LoggerFactory.getLogger(DiagnosticCapture.class);
    private final PriceProperties properties;

    public DiagnosticCapture(PriceProperties properties) { this.properties = properties; }

    public void capture(Page page, Deadline deadline) {
        if (!properties.diagnosticsEnabled()) return;
        try {
            Files.createDirectories(properties.diagnosticsPath());
            cleanup();
            String stem = "failure-" + UUID.randomUUID();
            String html = page.content();
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            if (bytes.length <= 1_048_576) Files.write(properties.diagnosticsPath().resolve(stem + ".html"), bytes);
            page.screenshot(new Page.ScreenshotOptions().setPath(properties.diagnosticsPath().resolve(stem + ".png"))
                    .setTimeout(deadline.timeout(java.time.Duration.ofSeconds(2))).setFullPage(false));
            cleanup();
        } catch (RuntimeException | IOException exception) { LOG.warn("Diagnostic capture failed; type={}", exception.getClass().getSimpleName()); }
    }

    private void cleanup() throws IOException {
        try (var paths = Files.list(properties.diagnosticsPath())) {
            var owned = paths.filter(path -> path.getFileName().toString().matches("failure-[a-f0-9-]+\\.(html|png)"))
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
}
