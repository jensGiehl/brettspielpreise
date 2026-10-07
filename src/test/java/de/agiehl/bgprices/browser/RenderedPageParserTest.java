package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.TestSettings;
import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.service.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RenderedPageParserTest {
    private final UrlPolicy urls = new UrlPolicy(TestSettings.properties());
    private final RenderedPageParser parser = new RenderedPageParser(urls, new GameNameNormalizer());
    private final String detail = "https://www.brettspiel-angebote.de/spiele/scythe/100/";
    private final Lookup lookup = new Lookup("Scythe", "Scythe", 123L, "key");
    private String identity = "<h1>Scythe</h1><a href='https://boardgamegeek.com/boardgame/123/scythe'>BGG</a>";

    @Test
    void readsBothAndPartialPrices() {
        String prices = "<div itemprop='offers'><meta itemprop='lowPrice' content='44.90'></div><div data-absolute-bestprice='32,50'></div>";
        var result = parser.detail(parser.document(identity + prices, detail), lookup);
        assertThat(result.availablePrice()).isEqualByComparingTo("44.90");
        assertThat(result.bestPrice()).isEqualByComparingTo("32.50");
        assertThat(result.complete()).isTrue();
        var partial = parser.detail(parser.document(identity + "<div data-absolute-bestprice='32.50'></div>", detail), lookup);
        assertThat(partial.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(partial.availablePrice()).isNull();
        assertThat(partial.complete()).isFalse();
        assertThat(parser.detail(parser.document(identity, detail), lookup).reason()).isEqualTo(FailureCode.NO_PRICE_DATA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "NaN", "EUR 10", "1.234,50", "44.999", "1000000000"})
    void rejectsInvalidPrices(String price) {
        assertThatThrownBy(() -> parser.detail(parser.document(identity + "<div data-absolute-bestprice='" + price + "'></div>", detail), lookup))
                .isInstanceOf(UpstreamException.class).hasMessage("PARSER_ERROR");
    }

    @Test
    void enforcesExactBggIdentityIncludingDirectDetails() {
        assertThatThrownBy(() -> parser.detail(parser.document(identity.replace("/123/", "/1234/"), detail), lookup)).hasMessage("IDENTITY_MISMATCH");
        assertThatThrownBy(() -> parser.detail(parser.document("<h1>Scythe</h1>", detail), lookup)).hasMessage("IDENTITY_UNCONFIRMED");
        assertThat(urls.bggId("https://evilboardgamegeek.com/boardgame/123")).isEmpty();
        assertThat(urls.bggId("https://boardgamegeek.com/boardgame/1234")).contains(1234L);
        assertThatThrownBy(() -> parser.detail(parser.document(identity.replace("Scythe</h1>", "Scythe Erweiterung</h1>"), detail),
                new Lookup("Scythe", "Scythe", null, "key"))).hasMessage("NAME_CONFLICT");
    }

    @Test
    void distinguishesEmptySearchUnknownMarkupAndChallenge() {
        assertThat(parser.selectDetail(parser.document("<div class='no-results'>Keine Treffer</div>", "https://www.brettspiel-angebote.de/suche/"), lookup)).isNull();
        assertThatThrownBy(() -> parser.selectDetail(parser.document("<html>unexpected</html>", "https://www.brettspiel-angebote.de/suche/"), lookup)).hasMessage("PARSER_ERROR");
        assertThatThrownBy(() -> parser.document("<h1>Hold tight</h1>" + identity + "<div data-absolute-bestprice='1'></div>", detail)).hasMessage("UPSTREAM_BLOCKED");
        assertThatThrownBy(() -> parser.document("<div data-sitekey='captcha'></div>", detail)).hasMessage("UPSTREAM_BLOCKED");
    }

    @Test
    void choosesMatchingResultAndRejectsForeignUrls() {
        String html = "<div class='item-box'><a href='/spiele/wrong/99/'>wrong</a><a href='https://boardgamegeek.com/boardgame/1234'>BGG</a></div>"
                + "<div class='item-box'><a href='/spiele/scythe/100/'>Scythe</a><a href='https://boardgamegeek.com/boardgame/123'>BGG</a></div>";
        assertThat(parser.selectDetail(parser.document(html, "https://www.brettspiel-angebote.de/suche/"), lookup)).isEqualTo(detail);
        assertThatThrownBy(() -> urls.checked("https://other.example/spiele/x/1/")).hasMessage("UNSAFE_NAVIGATION");
        assertThat(urls.allowed("https://user:pass@www.brettspiel-angebote.de/")).isFalse();
        assertThat(urls.allowed("http://www.brettspiel-angebote.de/")).isFalse();
    }
}
