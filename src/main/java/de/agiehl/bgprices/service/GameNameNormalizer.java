package de.agiehl.bgprices.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class GameNameNormalizer {
    private static final Pattern PARENTHESES = Pattern.compile("\\([^()]*\\)");
    private static final Pattern PREFIX = Pattern.compile("(?iu)^(de|deutsch|german|en|engl|englisch|english|international)\\s*[:|/\\-]\\s*");
    private static final Pattern WORDS = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(Stapelspiel|Würfelspiel|Jubiläumsausgabe)(?![\\p{L}\\p{N}_])");
    private static final Pattern BUNDLE = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])Bundle(?![\\p{L}\\p{N}_])");

    public String normalize(String name) {
        String value = Normalizer.normalize(name, Normalizer.Form.NFC);
        while (PARENTHESES.matcher(value).find()) value = PARENTHESES.matcher(value).replaceAll(" ");
        value = collapse(value);
        while (PREFIX.matcher(value).find()) value = PREFIX.matcher(value).replaceFirst("");
        value = WORDS.matcher(value).replaceAll(" ");
        value = value.replaceFirst("(?iu)(?<![\\p{L}\\p{N}_])inkl\\..*$", "");
        value = value.replaceFirst("(?iu)(?<![\\p{L}\\p{N}_])(Bundle|Set)\\s*$", "");
        return collapse(value);
    }

    public boolean isBundle(String original) { return BUNDLE.matcher(original).find(); }
    public String canonical(String name) { return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    private String collapse(String value) { return value.replaceAll("(?U)\\s+", " ").strip(); }
}
