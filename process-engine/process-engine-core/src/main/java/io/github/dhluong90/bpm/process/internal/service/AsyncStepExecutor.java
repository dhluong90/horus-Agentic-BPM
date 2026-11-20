package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs async step jobs as the Async Wait-State of ADR-0008. One attempt is:
 * <ol>
 *   <li>TX1, the claim ({@link #claim}): due async jobs of every tenant, {@code FOR UPDATE SKIP LOCKED}, leased for the
 *       step timeout plus {@code jobs.lease-margin}, at most as many as adapter slots are free; committed at once;</li>
 *   <li>the adapter call on the adapter executor, with no transaction, no connection and no row lock, abandoned and
 *       interrupted when the step timeout elapses;</li>
 *   <li>TX2, the result ({@link ProcessServices#recordAttempt}): recorded only while the attempt is still current.</li>
 * </ol>
 * No thread waits for a remote side: an adapter that answers {@link AdapterResult.Accepted} frees its slot at once and
 * the step waits durably in {@code pre_timer} until the completion API reports the result or the lease ends. Every
 * claim has a new attempt id ({@link ProcessServices#attemptId}) and a new random completion token, whose hash TX1
 * stores. Thread-safe; callers must not hold a transaction.
 */
public final class AsyncStepExecutor implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AsyncStepExecutor.class.getName());
    /** Error code of an attempt the engine abandoned at the step timeout. */
    public static final String TIMEOUT = "ADAPTER_TIMEOUT";

    private final JobStore jobs;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final JobSettings settings;
    private final Map<String, ServiceAdapter> adapters;
    private final ProcessServices services;
    private final ExecutorService adapterPool;
    private final ScheduledExecutorService timeouts;

    /**
     * One claimed attempt: the job row and the completion token issued with the claim, which only the adapter call
     * receives.
     *
     * @param job the claimed job
     * @param completionToken the token; never stored, never logged
     */
    public record Claim(JobRow job, String completionToken) {

        public Claim {
            Objects.requireNonNull(job, "job must not be null");
            Objects.requireNonNull(completionToken, "completionToken must not be null");
        }

        @Override
        public String toString() {
            return "Claim[job=" + job.id() + ", attempt=" + job.attempt() + ", completionToken=***]";
        }
    }
    private final Semaphore slots;
    private final int capacity;

    /**
     * @param jobs job storage
     * @param transactions transaction runner on the host's transaction manager
     * @param clock engine clock
     * @param settings job settings, with this replica's id
     * @param async async settings; {@code executorThreads} is the number of slots
     * @param adapters the host's adapters by name
     * @param services the engine services, which record results
     * @param adapterPool runs adapter calls; at least {@code executorThreads} threads
     * @param timeouts abandons calls at their step timeout
     */
    public AsyncStepExecutor(JobStore jobs, TransactionRunner transactions, Clock clock, JobSettings settings,
            AsyncSettings async, Map<String, ServiceAdapter> adapters, ProcessServices services,
            ExecutorService adapterPool, ScheduledExecutorService timeouts) {
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.transactions = TransactionRunner.tracked(Objects.requireNonNull(transactions,
                "transactions must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.adapters = Map.copyOf(adapters);
        this.services = Objects.requireNonNull(services, "services must not be null");
        this.adapterPool = Objects.requireNonNull(adapterPool, "adapterPool must not be null");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts must not be null");
        this.capacity = Objects.requireNonNull(async, "async must not be null").executorThreads();
        this.slots = new Semaphore(capacity);
    }

    /** @return adapter slots free now */
    public int freeSlots() {
        return slots.availablePermits();
    }

    /**
     * Waits until no attempt of this replica is in flight: every call returned or was abandoned and its result
     * transaction ended. For graceful shutdown and for tests.
     *
     * @param max longest wait
     * @return true when idle, false when {@code max} passed first
     * @throws InterruptedException when interrupted while waiting
     */
    public boolean awaitIdle(Duration max) throws InterruptedException {
        if (slots.tryAcquire(capacity, max.toMillis(), TimeUnit.MILLISECONDS)) {
            slots.release(capacity);
            return true;
        }
        return false;
    }

    /**
     * Claims as many due async jobs as adapter slots are free and hands each to the adapter executor; returns without
     * waiting for any call.
     *
     * @return the number of attempts started
     */
    public int pollOnce() {
        int free = Math.min(slots.availablePermits(), settings.batchSize());
        if (free < 1) {
            return 0;
        }
        List<Claim> claimed = claim(free);
        int started = 0;
        for (Claim job : claimed) {
            if (dispatch(job)) {
                started++;
            }
        }
        return started;
    }

    /**
     * TX1 alone: claims due jobs and issues each claim its completion token. Separate so a test can stop a replica
     * between the claim and the call, as a crash would.
     *
     * @param limit at most this many jobs
     * @return the claimed attempts
     */
    public List<Claim> claim(int limit) {
        return transactions.inTransaction(() -> jobs.claimDueAsync(settings.replicaId(), clock.instant(), limit)
                .stream().map(job -> new Claim(job, services.issueCompletionToken(job))).toList());
    }

    /**
     * Runs one claimed attempt on the calling thread: the adapter call, then TX2. No timeout is applied; for tests and
     * for hosts that drive the steps themselves.
     *
     * @param job an attempt claimed by this replica
     * @return the outcome of TX2, or empty when the adapter handed the call off or the job was not run
     */
    public java.util.Optional<StepCompletion.Outcome> runNow(Claim job) {
        if (exhausted(job.job())) {
            return java.util.Optional.empty();
        }
        AdapterResult result = call(job);
        return result instanceof AdapterResult.Accepted ? java.util.Optional.empty()
                : java.util.Optional.of(record(job.job(), toStep(result)));
    }

    /** Starts one claimed attempt on the adapter executor, with its timeout. */
    private boolean dispatch(Claim claim) {
        JobRow job = claim.job();
        if (exhausted(job)) {
            return false;
        }
        if (!slots.tryAcquire()) {
            // Never happens while this is the only claimer of the slots; the lease brings the job back.
            return false;
        }
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean started = new AtomicBoolean();
        // The slot is given back when both the adapter thread has ended (or never started) and the result transaction
        // has run (or there is nothing to record), so awaitIdle covers TX2 too.
        AtomicInteger pending = new AtomicInteger(2);
        Runnable done = () -> {
            if (pending.decrementAndGet() == 0) {
                slots.release();
            }
        };
        java.util.concurrent.atomic.AtomicReference<ScheduledFuture<?>> deadline =
                new java.util.concurrent.atomic.AtomicReference<>();
        Duration timeout = timeoutOf(job);
        Future<?> call;
        try {
            call = adapterPool.submit(() -> {
                if (started.getAndSet(true)) {
                    return; // abandoned at its timeout before it started; the timeout counted this thread as ended
                }
                try {
                    AdapterResult result = call(claim);
                    if (finished.compareAndSet(false, true)) {
                        ScheduledFuture<?> pendingDeadline = deadline.get();
                        if (pendingDeadline != null) {
                            pendingDeadline.cancel(false);
                        }
                        try {
                            if (!(result instanceof AdapterResult.Accepted)) {
                                record(job, toStep(result));
                            }
                        } finally {
                            done.run();
                        }
                    }
                } finally {
                    done.run();
                }
            });
        } catch (RejectedExecutionException e) {
            slots.release();
            return false;
        }
        deadline.set(timeouts.schedule(() -> {
            if (finished.compareAndSet(false, true)) {
                call.cancel(true);
                if (!started.getAndSet(true)) {
                    done.run();
                }
                try {
                    record(job, new StepResult.Failed(TIMEOUT, "No answer within " + timeout, true));
                } finally {
                    done.run();
                }
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS));
        return true;
    }

    /** The adapter call: no transaction may be open on this thread (TR-ASY-01). */
    private AdapterResult call(Claim claim) {
        JobRow job = claim.job();
        if (transactions.activeOnThisThread()) {
            throw new IllegalStateException("An adapter must not be called inside a transaction (ADR-0008)");
        }
        Map<String, Object> payload = Values.readMap(job.payloadJson());
        String target = String.valueOf(payload.get("target"));
        ServiceAdapter adapter = adapters.get(target);
        if (adapter == null) {
            return new AdapterResult.Failed("ADAPTER_NOT_REGISTERED", "No ServiceAdapter named " + target, false);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = payload.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Instant claimedAt = job.lockedUntil().minus(job.leaseDuration());
        AdapterCall call = new AdapterCall(job.tenantId(), job.instanceId(), job.nodeId(),
                payload.get("businessKey") == null ? null : String.valueOf(payload.get("businessKey")), job.id(),
                ProcessServices.attemptId(job.id(), job.attempt()), claim.completionToken(), job.attempt(),
                claimedAt.plus(timeoutOf(job)), inputs);
        try {
            AdapterResult result = adapter.execute(call);
            return result == null ? new AdapterResult.Failed("ADAPTER_NULL_RESULT", "The adapter returned null", true)
                    : result;
        } catch (RuntimeException e) {
            // The class only: an adapter's message may carry personal data.
            LOG.log(System.Logger.Level.WARNING, "process.adapter.error target={0} job={1} attempt={2} exception={3}",
                    target, job.id(), job.attempt(), e.getClass().getName());
            return new AdapterResult.Failed("ADAPTER_ERROR", e.getClass().getName(), true);
        }
    }

    private StepCompletion.Outcome record(JobRow job, StepResult result) {
        try {
            return services.recordAttempt(job.tenantId(), job.id(), ProcessServices.attemptId(job.id(), job.attempt()),
                    result, "system:replica:" + settings.replicaId());
        } catch (RuntimeException e) {
            // TX2 itself failed (database down): the lease brings the job back to another attempt.
            LOG.log(System.Logger.Level.WARNING, "process.async.record.failed tenant={0} job={1} attempt={2} exception={3}",
                    job.tenantId(), job.id(), job.attempt(), e.getClass().getName());
            return StepCompletion.Outcome.DISCARDED;
        }
    }

    /** A job claimed more often than it may be (its attempts keep dying with their replica) fails without a call. */
    private boolean exhausted(JobRow job) {
        if (job.attempt() <= job.maxAttempts()) {
            return false;
        }
        services.failExhausted(job, settings.replicaId());
        LOG.log(System.Logger.Level.WARNING, "process.async.failed tenant={0} job={1} attempt={2} reason=max attempts"
                + " reached", job.tenantId(), job.id(), job.attempt());
        return true;
    }

    private static Duration timeoutOf(JobRow job) {
        Object ms = Values.readMap(job.payloadJson()).get("timeoutMs");
        return ms instanceof Number n ? Duration.ofMillis(n.longValue()) : AsyncSettings.DEFAULT_TIMEOUT;
    }

    private static StepResult toStep(AdapterResult result) {
        return switch (result) {
            case AdapterResult.Completed c -> new StepResult.Completed(c.output());
            case AdapterResult.BusinessError e -> new StepResult.BusinessError(e.code(), e.message());
            case AdapterResult.Failed f -> new StepResult.Failed(f.errorCode(), f.detail(), f.retryable());
            case AdapterResult.Accepted a -> throw new IllegalArgumentException("Accepted carries no result");
        };
    }

    /** Stops the adapter executor and the timeout scheduler; calls in flight are interrupted. */
    @Override
    public void close() {
        adapterPool.shutdownNow();
        timeouts.shutdownNow();
    }
}
