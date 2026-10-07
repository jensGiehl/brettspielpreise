package de.agiehl.bgprices.service;

import de.agiehl.bgprices.config.PriceProperties;
import de.agiehl.bgprices.domain.Lookup;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class LookupFactory {
    private final GameNameNormalizer normalizer;
    private final PriceProperties properties;

    public LookupFactory(GameNameNormalizer normalizer, PriceProperties properties) {
        this.normalizer = normalizer;
        this.properties = properties;
    }

    public Lookup create(String name, Long bggId) {
        String normalized = normalizer.normalize(name);
        String identity = properties.baseUrl().normalize() + "\n" + normalizer.canonical(normalized) + "\n" + bggId;
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
            return new Lookup(name, normalized, bggId, key);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
