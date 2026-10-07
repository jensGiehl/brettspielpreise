package de.agiehl.bgprices.browser;

import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.CDPSession;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.agiehl.bgprices.TestSettings;
import de.agiehl.bgprices.config.PriceProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticCaptureTest {
    @TempDir Path directory;

    @Test
    void disabledDiagnosticsDoNotObserveThePageOrWriteFiles() throws Exception {
        var page = mock(Page.class);
        var capture = capture(false);
        capture.capture(page, new Deadline(0), capture.observe(page));
        verifyNoInteractions(page);
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
    }

    @Test
    void recordsFailuresAndJavascriptErrorsWithoutRequestCredentialsOrQueries() throws Exception {
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html>Prüfung</html>");
        var capture = capture(true);
        var observation = capture.observe(page);
        ArgumentCaptor<Consumer<Response>> responses = ArgumentCaptor.captor();
        ArgumentCaptor<Consumer<Request>> failures = ArgumentCaptor.captor();
        ArgumentCaptor<Consumer<String>> errors = ArgumentCaptor.captor();
        ArgumentCaptor<Consumer<ConsoleMessage>> console = ArgumentCaptor.captor();
        verify(page).onResponse(responses.capture());
        verify(page).onRequestFailed(failures.capture());
        verify(page).onPageError(errors.capture());
        verify(page).onConsoleMessage(console.capture());
        var request = mock(Request.class);
        when(request.method()).thenReturn("POST");
        when(request.resourceType()).thenReturn("fetch");
        when(request.url()).thenReturn("https://user:password@example.org/check?token=secret#private");
        when(request.failure()).thenReturn("net::ERR_FAILED");
        var response = mock(Response.class);
        when(response.status()).thenReturn(403);
        when(response.request()).thenReturn(request);
        when(response.url()).thenReturn("https://user:password@example.org/check?token=secret#private");
        responses.getValue().accept(response);
        failures.getValue().accept(request);
        errors.getValue().accept("ReferenceError: challenge is not defined\nsecond line");
        var message = mock(ConsoleMessage.class);
        when(message.type()).thenReturn("error");
        when(message.text()).thenReturn("Blocked by Content Security Policy");
        console.getValue().accept(message);
        capture.capture(page, new Deadline(0), observation);
        String report;
        try (var files = Files.list(directory)) {
            report = Files.readString(files.filter(path -> path.toString().endsWith(".txt")).findFirst().orElseThrow(), StandardCharsets.UTF_8);
        }
        assertThat(report).contains("httpErrors=1 requestFailures=1 javascriptErrors=1 consoleErrors=1",
                "response status=403 method=POST type=fetch url=https://example.org/check",
                "failure=net::ERR_FAILED", "ReferenceError", "Content Security Policy");
        assertThat(report).doesNotContain("password", "token=secret", "#private", "\nsecond line");
        verify(page, never()).screenshot(any());
    }

    @Test
    void boundsEventsAndReportsDroppedEntries() {
        var observation = new DiagnosticCapture.Observation();
        for (int index = 0; index < 210; index++) observation.add("event-" + index + " " + "x".repeat(1500));
        assertThat(observation.report()).hasSize(201);
        assertThat(observation.report().getFirst()).contains("droppedEvents=10");
        assertThat(observation.report().get(1)).startsWith("event-10 ").hasSize(1000);
    }

    @Test
    void reportsCookieInclusionAndBlockingWithoutCookieValuesOrRawHeaders() {
        var session = mock(CDPSession.class);
        var observation = new DiagnosticCapture.Observation();
        capture(true).observeCookies(session, observation);
        ArgumentCaptor<Consumer<JsonObject>> requests = ArgumentCaptor.captor();
        ArgumentCaptor<Consumer<JsonObject>> responses = ArgumentCaptor.captor();
        verify(session).on(eq("Network.requestWillBeSentExtraInfo"), requests.capture());
        verify(session).on(eq("Network.responseReceivedExtraInfo"), responses.capture());
        requests.getValue().accept(JsonParser.parseString("""
                {"requestId":"123", "associatedCookies":[
                  {"cookie":{"name":"verification_fixture", "value":"request-secret", "domain":"example.org", "path":"/"}, "blockedReasons":[]},
                  {"cookie":{"name":"other_fixture", "value":"blocked-secret", "domain":"other.example"}, "blockedReasons":["DomainMismatch"]}
                ]}
                """).getAsJsonObject());
        responses.getValue().accept(JsonParser.parseString("""
                {"requestId":"123", "statusCode":200,
                 "headers":{"Set-Cookie":"verification_fixture=response-secret; Path=/\\ninvalid_fixture=invalid-secret; Domain=other.example", "Authorization":"private-header"},
                 "blockedCookies":[{"cookieLine":"invalid_fixture=invalid-secret; Domain=other.example", "blockedReasons":["InvalidDomain"]}]}
                """).getAsJsonObject());
        String report = String.join("\n", observation.report());
        assertThat(report).contains("request-cookies id=123", "verification_fixture", "DomainMismatch",
                "setCookieNames=[verification_fixture, invalid_fixture]", "blocked-cookie", "InvalidDomain");
        assertThat(report).doesNotContain("request-secret", "blocked-secret", "response-secret", "invalid-secret", "private-header");
    }

    @Test
    void disabledDiagnosticsDoNotSubscribeToCookieEvents() {
        var session = mock(CDPSession.class);
        capture(false).observeCookies(session, new DiagnosticCapture.Observation());
        verifyNoInteractions(session);
    }

    @Test
    void preservesTheReportWhenThePageCannotBeRead() throws Exception {
        var page = mock(Page.class);
        when(page.content()).thenThrow(new IllegalStateException("page closed"));
        var capture = capture(true);
        capture.capture(page, new Deadline(0), capture.observe(page));
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString())).singleElement().asString().endsWith(".txt");
        }
    }

    @Test
    void cleansExpiredReportsAndPreservesUnrelatedFiles() throws Exception {
        var expired = directory.resolve("failure-12345678-1234-1234-1234-123456789abc.txt");
        Files.writeString(expired, "old", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(expired, FileTime.from(Instant.now().minusSeconds(172800)));
        var unrelated = directory.resolve("notes.txt");
        Files.writeString(unrelated, "keep", StandardCharsets.UTF_8);
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html></html>");
        var capture = capture(true);
        capture.capture(page, new Deadline(0), capture.observe(page));
        assertThat(expired).doesNotExist();
        assertThat(unrelated).hasContent("keep");
    }

    private DiagnosticCapture capture(boolean enabled) {
        var settings = TestSettings.properties();
        return new DiagnosticCapture(new PriceProperties(settings.baseUrl(), settings.browserPath(), settings.headless(),
                settings.sandbox(), settings.browserTimeout(), settings.queueTimeout(), settings.totalTimeout(),
                settings.queueCapacity(), settings.attempts(), settings.minimumInterval(), settings.retryPause(),
                settings.cooldown(), settings.proxy(), enabled, directory, settings.diagnosticFiles(),
                settings.sourceCheckEnabled(), settings.sourceCheckAtStartup()));
    }
}
