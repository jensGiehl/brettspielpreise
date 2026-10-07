package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.config.PriceProperties;
import de.agiehl.bgprices.domain.FailureCode;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class UrlPolicy {
    private static final Pattern DETAIL = Pattern.compile("^/spiele/[^/]+/[0-9]+/?$");
    private static final Pattern BGG = Pattern.compile("^/boardgame/([1-9][0-9]*)(?:/[^/]*)?/?$");
    private final URI origin;

    public UrlPolicy(PriceProperties properties) { origin = properties.baseUrl(); }

    public boolean allowed(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getUserInfo() == null && origin.getScheme().equals(uri.getScheme())
                    && origin.getHost().equalsIgnoreCase(uri.getHost()) && port(origin) == port(uri);
        } catch (IllegalArgumentException exception) { return false; }
    }

    public URI checked(String url) {
        if (!allowed(url)) throw new UpstreamException(FailureCode.UNSAFE_NAVIGATION);
        return URI.create(url);
    }

    public boolean detail(String url) { return allowed(url) && DETAIL.matcher(URI.create(url).getPath()).matches(); }

    public Optional<Long> bggId(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !java.util.Set.of("boardgamegeek.com", "www.boardgamegeek.com").contains(uri.getHost().toLowerCase(Locale.ROOT))
                    || !java.util.Set.of("https", "http").contains(uri.getScheme())) return Optional.empty();
            var matcher = BGG.matcher(uri.getPath());
            return matcher.matches() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
        } catch (IllegalArgumentException exception) { return Optional.empty(); }
    }

    private int port(URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }
}
