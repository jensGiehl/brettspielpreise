package de.agiehl.bgprices.service;

import de.agiehl.bgprices.TestSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class GameNameNormalizerTest {
    private final GameNameNormalizer names = new GameNameNormalizer();

    @ParameterizedTest
    @CsvSource(value = {
            "  Die Glasstraße (German first edition)  ;Die Glasstraße",
            "Die (2026) Glasstraße () (German (first) edition);Die Glasstraße",
            "DE: Scythe (deutsch) inkl. Promo;Scythe",
            "International - Biber Gang (english);Biber Gang",
            "Carcassonne Jubiläumsausgabe (deutsch);Carcassonne",
            "EN / DE: ÄÖü Würfelspiel Stapelspiel Set;ÄÖü",
            "(foo (bar)) ();''",
            "Scythe Bundle;Scythe",
            "Bundle-Angebot;Bundle-Angebot",
            "Bundled Edition;Bundled Edition",
            "Spielwürfelspiel;Spielwürfelspiel"
    }, delimiter = ';')
    void normalizes(String input, String expected) { assertThat(names.normalize(input)).isEqualTo(expected); }

    @Test
    void detectsOnlyUnicodeWords() {
        assertThat(names.isBundle("Bundle-Angebot")).isTrue();
        assertThat(names.isBundle("Scythe Bundle")).isTrue();
        assertThat(names.isBundle("Bundled Edition")).isFalse();
        assertThat(names.isBundle("äBundle")).isFalse();
    }

    @Test
    void keysIsolateIdentityAndPreserveUnicodeNormalization() {
        var factory = new LookupFactory(names, TestSettings.properties());
        assertThat(factory.create("Ärger", null).key()).isEqualTo(factory.create("A\u0308RGER", null).key());
        assertThat(factory.create("Scythe", 123L).key()).isNotEqualTo(factory.create("Scythe", 1234L).key());
        assertThat(factory.create("Scythe", null).key()).isNotEqualTo(factory.create("Scythe", 123L).key());
    }
}
