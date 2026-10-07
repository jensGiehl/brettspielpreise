package de.agiehl.bgprices.api;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import de.agiehl.bgprices.browser.PriceClient;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:documentation;DB_CLOSE_DELAY=-1", "prices.source-check-enabled=false"})
@EnabledIfEnvironmentVariable(named = "RUN_BROWSER_TESTS", matches = "true")
class DocumentationIT {
    @LocalServerPort private int port;
    @MockitoBean private PriceClient client;

    @Test
    void rendersLocalOpenApiAndExecutesRequestsOnDesktopAndMobile() {
        String origin = "http://localhost:" + port;
        var failures = new ArrayList<String>();
        var requests = new ArrayList<String>();
        try (var playwright = Playwright.create();
             var browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 900))) {
            Page page = context.newPage();
            page.onPageError(failures::add);
            page.onRequest(request -> requests.add(request.url()));
            page.navigate(origin + "/swagger-ui.html");
            assertThat(page.locator(".opblock")).hasCount(2);
            assertThat(page.locator(".errors-wrapper")).hasCount(0);
            var prices = page.locator("#operations-default-lookupPrices");
            prices.locator(".opblock-summary").click();
            assertThat(prices).containsText("169786");
            assertThat(prices).containsText("503");
            var source = page.locator("#operations-default-sourceStatus");
            source.locator(".opblock-summary").click();
            source.getByText("Try it out", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)).click();
            source.getByText("Execute", new com.microsoft.playwright.Locator.GetByTextOptions().setExact(true)).click();
            assertThat(source.locator(".live-responses-table .response-col_status:not(.col_header)")).containsText("200");
            assertThat(source.locator(".live-responses-table .response-col_description:not(.col_header)")).containsText("UNKNOWN");
            page.setViewportSize(390, 844);
            assertThat(page.locator("header h1")).hasText("bg-prices API");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("target/documentation-mobile.png")));
            assertThat(page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")).isEqualTo(true);
            assertThat(failures).isEmpty();
            assertThat(requests).allMatch(url -> url.startsWith(origin + "/"));
            verifyNoInteractions(client);
        }
    }
}
