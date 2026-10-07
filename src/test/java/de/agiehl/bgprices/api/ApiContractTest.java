package de.agiehl.bgprices.api;

import de.agiehl.bgprices.browser.PriceClient;
import de.agiehl.bgprices.domain.*;
import java.math.BigDecimal;
import java.time.*;
import de.agiehl.bgprices.cache.CacheStore;
import de.agiehl.bgprices.service.LookupFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:contract;DB_CLOSE_DELAY=-1", "prices.minimum-interval=0ms", "prices.cooldown=0ms"})
@AutoConfigureMockMvc
class ApiContractTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private PriceClient client;
    @Autowired private CacheStore cache;
    @Autowired private LookupFactory lookups;

    @Test
    void validatesInputAndReturnsUniformSafeErrors() throws Exception {
        for (String name : new String[]{"", " ", "x".repeat(301)})
            mvc.perform(get("/api/v1/prices").param("name", name)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/prices")).andExpect(status().isBadRequest());
        for (String id : new String[]{"0", "-1", "foo", "99999999999999999999999"})
            mvc.perform(get("/api/v1/prices").param("name", "Scythe").param("bggId", id)).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test
    void servesLiveThenFallbackThenDoesNotMaskSourceOutage() throws Exception {
        when(client.fetch(any(), any())).thenReturn(new LiveResult(LookupStatus.FOUND,
                "https://www.brettspiel-angebote.de/spiele/contract/100/", 169786L, new BigDecimal("44.90"), new BigDecimal("32.50"), null));
        mvc.perform(get("/api/v1/prices").param("name", "contract").param("bggId", "169786"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.dataSource").value("LIVE")).andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.stale").value(false)).andExpect(jsonPath("$.fallbackReason").value(nullValue()));
        when(client.fetch(any(), any())).thenReturn(LiveResult.failed(FailureCode.UPSTREAM_BLOCKED));
        mvc.perform(get("/api/v1/prices").param("name", "contract").param("bggId", "169786"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataSource").value("CACHE"))
                .andExpect(jsonPath("$.stale").value(true)).andExpect(jsonPath("$.fallbackReason").value("UPSTREAM_BLOCKED"));
        mvc.perform(get("/api/v1/source-status")).andExpect(jsonPath("$.source.status").value("DOWN"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void returns503WithoutPricesAndSupportsPartialAndMissing() throws Exception {
        when(client.fetch(any(), any())).thenReturn(LiveResult.failed(FailureCode.UPSTREAM_BLOCKED));
        mvc.perform(get("/api/v1/prices").param("name", "uncached-error"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.availablePrice").value(nullValue())).andExpect(jsonPath("$.bestPrice").value(nullValue()))
                .andExpect(jsonPath("$.errorCode").value("UPSTREAM_BLOCKED")).andExpect(jsonPath("$.dataSource").value("NONE"));
        when(client.fetch(any(), any())).thenReturn(new LiveResult(LookupStatus.FOUND,
                "https://www.brettspiel-angebote.de/spiele/partial/2/", null, null, BigDecimal.ONE, null));
        mvc.perform(get("/api/v1/prices").param("name", "partial"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.bestPrice").value(1)).andExpect(jsonPath("$.availablePrice").value(nullValue()));
        when(client.fetch(any(), any())).thenReturn(LiveResult.missing(FailureCode.NO_MATCH));
        mvc.perform(get("/api/v1/prices").param("name", "missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/prices").param("name", "Scythe Bundle"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SKIPPED"));
        mvc.perform(get("/api/openapi.yaml")).andExpect(status().isOk()).andExpect(content().string(containsString("openapi: 3.1.0")));
    }

    @Test
    void exposesDocumentationAndLocallyPackagedUiAssets() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/docs/index.html"));
        mvc.perform(get("/docs/index.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("API-Dokumentation")));
        mvc.perform(get("/webjars/swagger-ui/5.33.1/swagger-ui-bundle.js")).andExpect(status().isOk());
        mvc.perform(get("/webjars/bootstrap/5.3.8/css/bootstrap.min.css")).andExpect(status().isOk());
        verifyNoInteractions(client);
    }

    @Test
    void neverDeliversExpiredPrices() throws Exception {
        var lookup = lookups.create("expired", 123L);
        Instant fetched = Instant.now().minus(Duration.ofDays(70));
        cache.record(lookup, new PriceSnapshot("https://www.brettspiel-angebote.de/spiele/expired/9/", 123L,
                BigDecimal.TEN, BigDecimal.ONE, fetched, fetched.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()), fetched, null, null);
        when(client.fetch(any(), any())).thenReturn(LiveResult.failed(FailureCode.UPSTREAM_BLOCKED));
        mvc.perform(get("/api/v1/prices").param("name", "expired").param("bggId", "123"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.dataSource").value("NONE"))
                .andExpect(jsonPath("$.availablePrice").value(nullValue())).andExpect(jsonPath("$.bestPrice").value(nullValue()))
                .andExpect(jsonPath("$.fetchedAt").value(nullValue()));
    }
}
