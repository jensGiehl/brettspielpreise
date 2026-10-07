package de.agiehl.bgprices.health;

import de.agiehl.bgprices.browser.BrowserWorker;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class BrowserWorkerHealthIndicator implements HealthIndicator {
    private final BrowserWorker worker;
    public BrowserWorkerHealthIndicator(BrowserWorker worker) { this.worker = worker; }
    @Override public Health health() { return worker.ready() ? Health.up().build() : Health.down().build(); }
}
