package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.TestSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserWorkerTest {
    @Test
    void boundsQueueExpiresQueuedWorkAndClosesOnOwnerThread() throws Exception {
        var client = mock(PriceClient.class);
        var closedBy = new AtomicReference<String>();
        doAnswer(invocation -> { closedBy.set(Thread.currentThread().getName()); return null; }).when(client).close();
        var settings = TestSettings.properties(URI.create("https://example.org/"), 1, Duration.ofMillis(400));
        var worker = new BrowserWorker(settings, client, new SimpleMeterRegistry());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = worker.submit(deadline -> {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
                return "first";
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var queued = worker.submit(deadline -> "should not execute");
            var rejected = worker.submit(deadline -> "should not execute");
            assertThatThrownBy(rejected::join).hasCauseInstanceOf(UpstreamException.class).hasRootCauseMessage("QUEUE_FULL");
            assertThatThrownBy(() -> queued.get(350, TimeUnit.MILLISECONDS)).hasRootCauseMessage("QUEUE_TIMEOUT");
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("first");
            assertThatThrownBy(queued::join).hasRootCauseMessage("QUEUE_TIMEOUT");
        } finally { release.countDown(); worker.shutdown(); }
        assertThat(closedBy.get()).isEqualTo("price-browser-worker");
        assertThat(worker.ready()).isFalse();
        assertThatThrownBy(() -> worker.submit(deadline -> "no").join()).hasRootCauseMessage("SHUTTING_DOWN");
    }

    @Test
    void totalDeadlineAlsoCompletesAStuckOperation() throws Exception {
        var client = mock(PriceClient.class);
        var worker = new BrowserWorker(TestSettings.properties(URI.create("https://example.org/"), 1, Duration.ofMillis(300)),
                client, new SimpleMeterRegistry());
        var release = new CountDownLatch(1);
        try {
            var result = worker.submit(deadline -> {
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
                return "late";
            });
            assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS)).hasRootCauseMessage("TOTAL_TIMEOUT");
        } finally { release.countDown(); worker.shutdown(); }
    }
}
