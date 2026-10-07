package de.agiehl.bgprices.browser;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class BrowserConnection {
    public record Observation(String remoteAddress, boolean ipv6, boolean proxyConfigured, Instant observedAt) { }
    private final AtomicReference<Observation> latest = new AtomicReference<>();
    public void observed(String address, boolean proxyConfigured) {
        latest.set(new Observation(address, address.contains(":"), proxyConfigured, Instant.now()));
    }
    public Observation latest() { return latest.get(); }
}
