package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.service.GameNameNormalizer;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class RenderedPageParser {
    private final UrlPolicy urls;
    private final GameNameNormalizer normalizer;

    public RenderedPageParser(UrlPolicy urls, GameNameNormalizer normalizer) {
        this.urls = urls;
        this.normalizer = normalizer;
    }

    public Document document(String html, String url) {
        urls.checked(url);
        Document document = Jsoup.parse(html, url);
        if (challenge(document)) throw new UpstreamException(FailureCode.UPSTREAM_BLOCKED);
        return document;
    }

    public boolean challenge(Document document) {
        String text = (document.title() + " " + document.text()).toLowerCase(Locale.ROOT);
        return text.contains("establishing a secure connection") || text.contains("hold tight")
                || text.contains("enable javascript and cookies to continue") || text.contains("verify you are human")
                || document.selectFirst("#challenge-form, .cf-challenge, [data-sitekey]") != null;
    }

    public String selectDetail(Document document, Lookup lookup) {
        var items = document.select(".item-box");
        if (items.isEmpty()) {
            if (emptySearch(document)) return null;
            throw new UpstreamException(FailureCode.PARSER_ERROR);
        }
        for (Element item : items) {
            Element link = item.selectFirst("a[href*='/spiele/']");
            if (link == null || !urls.detail(link.absUrl("href"))) throw new UpstreamException(FailureCode.PARSER_ERROR);
            Set<Long> ids = ids(item);
            if (lookup.bggId() == null || ids.contains(lookup.bggId())) return link.absUrl("href");
        }
        throw new UpstreamException(FailureCode.IDENTITY_UNCONFIRMED);
    }

    public LiveResult detail(Document document, Lookup lookup) {
        if (!urls.detail(document.location())) throw new UpstreamException(FailureCode.PARSER_ERROR);
        Set<Long> ids = ids(document);
        if (lookup.bggId() != null) {
            if (ids.isEmpty()) throw new UpstreamException(FailureCode.IDENTITY_UNCONFIRMED);
            if (!ids.equals(Set.of(lookup.bggId()))) throw new UpstreamException(FailureCode.IDENTITY_MISMATCH);
        } else {
            Element title = document.selectFirst("h1 [itemprop=name], h1[itemprop=name], h1");
            if (title == null) throw new UpstreamException(FailureCode.IDENTITY_UNCONFIRMED);
            if (!normalizer.canonical(normalizer.normalize(title.text())).equals(normalizer.canonical(lookup.normalizedName())))
                throw new UpstreamException(FailureCode.NAME_CONFLICT);
            if (ids.size() > 1) throw new UpstreamException(FailureCode.IDENTITY_UNCONFIRMED);
        }
        BigDecimal available = prices(document, "[itemprop=offers] meta[itemprop=lowPrice]", "content");
        BigDecimal best = prices(document, "[data-absolute-bestprice]", "data-absolute-bestprice");
        if (available == null && best == null) return LiveResult.missing(FailureCode.NO_PRICE_DATA);
        return new LiveResult(LookupStatus.FOUND, document.location(), ids.stream().findFirst().orElse(null), available, best, null);
    }

    private Set<Long> ids(Element element) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Element link : element.select("a[href]")) urls.bggId(link.absUrl("href")).ifPresent(ids::add);
        return ids;
    }

    private boolean emptySearch(Document document) {
        String text = document.text().toLowerCase(Locale.ROOT);
        return document.selectFirst("[data-search-empty], .search-no-results, .no-results") != null
                || text.matches("(?s).*(keine (suchergebnisse|treffer|spiele gefunden)|no results found).*" );
    }

    private BigDecimal prices(Document document, String selector, String attribute) {
        Set<BigDecimal> values = new LinkedHashSet<>();
        for (Element element : document.select(selector)) {
            String raw = element.attr(attribute).strip();
            if (raw.isEmpty()) continue;
            if (!raw.matches("(?:0|[1-9][0-9]{0,7})(?:[.,][0-9]{1,2})?")) throw new UpstreamException(FailureCode.PARSER_ERROR);
            values.add(new BigDecimal(raw.replace(',', '.')).stripTrailingZeros());
        }
        if (values.size() > 1) throw new UpstreamException(FailureCode.PARSER_ERROR);
        return values.stream().findFirst().orElse(null);
    }
}
