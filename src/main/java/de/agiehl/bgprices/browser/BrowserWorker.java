package de.agiehl.bgprices.browser;

import de.agiehl.bgprices.config.PriceProperties;
import de.agiehl.bgprices.domain.FailureCode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class BrowserWorker {
    private record Job<T>(long submittedNanos, Deadline deadline, Function<Deadline, T> operation, CompletableFuture<T> result,
                          AtomicBoolean started) {
        void execute(Duration queueTimeout) {
            try {
                if (!started.compareAndSet(false, true) || result.isDone()) return;
                if (System.nanoTime() - submittedNanos >= queueTimeout.toNanos()) throw new UpstreamException(FailureCode.QUEUE_TIMEOUT);
                deadline.remainingMillis();
                T value = operation.apply(deadline);
                deadline.remainingMillis();
                result.complete(value);
            } catch (Exception exception) { result.completeExceptionally(exception); }
        }
    }

    private final ArrayBlockingQueue<Job<?>> queue;
    private final PriceProperties properties;
    private final PriceClient client;
    private final Thread worker;
    private final ScheduledThreadPoolExecutor timers;
    private volatile boolean running = true;

    public BrowserWorker(PriceProperties properties, PriceClient client, MeterRegistry metrics) {
        this.properties = properties;
        this.client = client;
        queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        timers = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("price-deadline").factory());
        timers.setRemoveOnCancelPolicy(true);
        metrics.gauge("bg_prices_queue_size", queue, ArrayBlockingQueue::size);
        metrics.gauge("bg_prices_queue_utilization", queue, value -> (double) value.size() / properties.queueCapacity());
        worker = Thread.ofPlatform().name("price-browser-worker").start(this::run);
    }

    public <T> CompletableFuture<T> submit(Function<Deadline, T> operation) {
        var result = new CompletableFuture<T>();
        var job = new Job<>(System.nanoTime(), Deadline.after(properties.totalTimeout()), operation, result, new AtomicBoolean());
        if (!running) result.completeExceptionally(new UpstreamException(FailureCode.SHUTTING_DOWN));
        else if (!queue.offer(job)) result.completeExceptionally(new UpstreamException(FailureCode.QUEUE_FULL));
        if (!result.isDone()) {
            try {
                var queueTimer = timers.schedule(() -> {
                    if (job.started().compareAndSet(false, true) && !result.isDone()) {
                        queue.remove(job);
                        result.completeExceptionally(new UpstreamException(FailureCode.QUEUE_TIMEOUT));
                    }
                }, properties.queueTimeout().toMillis(), TimeUnit.MILLISECONDS);
                var totalTimer = timers.schedule(() -> result.completeExceptionally(new UpstreamException(FailureCode.TOTAL_TIMEOUT)),
                        properties.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
                result.whenComplete((value, failure) -> { queueTimer.cancel(false); totalTimer.cancel(false); });
            } catch (RejectedExecutionException exception) {
                queue.remove(job);
                result.completeExceptionally(new UpstreamException(FailureCode.SHUTTING_DOWN));
            }
        }
        if (!running && queue.remove(job)) result.completeExceptionally(new UpstreamException(FailureCode.SHUTTING_DOWN));
        return result;
    }

    private void run() {
        long nextAllowed = 0;
        try {
            while (running) {
                Job<?> job = queue.poll(250, TimeUnit.MILLISECONDS);
                if (job == null) continue;
                try {
                    long wait = nextAllowed - System.nanoTime();
                    if (wait > 0) job.deadline().pause(Duration.ofNanos(wait));
                    if (!running) throw new UpstreamException(FailureCode.SHUTTING_DOWN);
                    job.execute(properties.queueTimeout());
                } catch (UpstreamException exception) { job.result().completeExceptionally(exception); }
                catch (InterruptedException exception) {
                    job.result().completeExceptionally(new UpstreamException(FailureCode.SHUTTING_DOWN));
                    throw exception;
                }
                nextAllowed = System.nanoTime() + properties.minimumInterval().toNanos();
            }
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        finally {
            running = false;
            timers.shutdownNow();
            Job<?> job;
            while ((job = queue.poll()) != null) job.result().completeExceptionally(new UpstreamException(FailureCode.SHUTTING_DOWN));
            client.close();
        }
    }

    public boolean ready() { return running && worker.isAlive(); }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        running = false;
        worker.join(properties.totalTimeout().toMillis() + 2000);
        if (worker.isAlive()) {
            worker.interrupt();
            worker.join(2000);
        }
    }
}
