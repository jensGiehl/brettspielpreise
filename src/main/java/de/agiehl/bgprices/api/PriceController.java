package de.agiehl.bgprices.api;

import de.agiehl.bgprices.browser.BrowserConnection;
import de.agiehl.bgprices.domain.*;
import de.agiehl.bgprices.health.SourceStatus;
import de.agiehl.bgprices.service.PriceService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PriceController {
    public record SourceResponse(SourceStatus.State source, BrowserConnection.Observation browserConnection) { }
    private final PriceService prices;
    private final SourceStatus source;
    private final BrowserConnection connection;
    private final Clock clock;

    public PriceController(PriceService prices, SourceStatus source, BrowserConnection connection, Clock clock) {
        this.prices = prices;
        this.source = source;
        this.connection = connection;
        this.clock = clock;
    }

    @GetMapping("/v1/prices")
    public ResponseEntity<PriceResponse> prices(@RequestParam @NotBlank @Size(max = 300) String name,
                                               @RequestParam(required = false) @Positive Long bggId) {
        PriceResponse response = prices.lookup(name, bggId);
        HttpStatus status = response.status() != LookupStatus.ERROR ? HttpStatus.OK
                : response.errorCode() == FailureCode.QUEUE_FULL ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        var builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (response.retryAt() != null && response.status() == LookupStatus.ERROR)
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, Duration.between(clock.instant(), response.retryAt()).toSeconds())));
        return builder.body(response);
    }

    @GetMapping("/v1/source-status")
    public ResponseEntity<SourceResponse> source() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SourceResponse(source.current(), connection.latest()));
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public ResponseEntity<Resource> openapi() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ClassPathResource("openapi.yaml"));
    }
}
