package de.agiehl.bgprices.health;

import de.agiehl.bgprices.browser.*;
import de.agiehl.bgprices.config.PriceProperties;
import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.service.LookupFactory;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SourceProbe {
    private static final Logger LOG = LoggerFactory.getLogger(SourceProbe.class);
    private final PriceProperties properties;
    private final BrowserWorker worker;
    private final PriceClient client;
    private final LookupFactory lookups;
    private final SourceStatus status;
    private final Clock clock;

    public SourceProbe(PriceProperties properties, BrowserWorker worker, PriceClient client, LookupFactory lookups,
                       SourceStatus status, Clock clock) {
        this.properties = properties;
        this.worker = worker;
        this.client = client;
        this.lookups = lookups;
        this.status = status;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startup() { if (properties.sourceCheckAtStartup()) check(); }

    @Scheduled(cron = "0 0 8 * * *", zone = "Europe/Berlin")
    public void daily() { if (properties.sourceCheckEnabled()) check(); }

    private void check() {
        LOG.info("Starting scheduled live source check name=Scythe bggId=169786");
        worker.submit(deadline -> client.fetch(lookups.create("Scythe", 169786L), deadline))
                .whenComplete((result, failure) -> {
                    LiveResult observed = failure == null ? result : LiveResult.failed(
                            failure instanceof UpstreamException upstream ? upstream.code() : FailureCode.BROWSER_CRASH);
                    status.record(observed, clock.instant());
                    LOG.info("Scheduled live source check status={} errorCode={}", status.current().status(), status.current().errorCode());
                });
    }
}
