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
 * stores.
 *
 * <p>Each async job kind has its own runner ({@link AsyncStepRunner}) and its own lane: its own slots, timeout and
 * retry settings ({@link AsyncSettings#runner}), and its own claim statement, which returns jobs of that kind only and
 * at most as many as that lane has free slots. A backlog of agent jobs therefore never takes the slots or the claims
 * of remote service jobs, and the other way round (#363). Timers are not run here: {@link JobExecutor} runs them on
 * the poller thread with {@code jobs.*}. Thread-safe; callers must not hold a transaction.
 */
public final class AsyncStepExecutor implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AsyncStepExecutor.class.getName());
    /** Error code of an attempt the engine abandoned at the step timeout. */
    public static final String TIMEOUT = "ADAPTER_TIMEOUT";
    /** Error code of an agent step's attempt the engine abandoned at the step timeout (#58, PRE-011). */
    public static final String AGENT_TIMEOUT = "AGENT_TIMEOUT";

    private final JobStore jobs;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final JobSettings settings;
    private final Map<String, Lane> lanes;
    private final ProcessServices services;
    private final ExecutorService adapterPool;
    private final ScheduledExecutorService timeouts;

    /** One job kind: its runner, its settings and its slots. */
    private record Lane(AsyncStepRunner runner, RunnerSettings settings, Semaphore slots) {
    }

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

    /**
     * An executor with the remote service runner only, as before #363.
     *
     * @param jobs job storage
     * @param transactions transaction runner on the host's transaction manager
     * @param clock engine clock
     * @param settings job settings, with this replica's id
     * @param async async settings; the slots of each kind come from {@link AsyncSettings#runner}
     * @param adapters the host's adapters by name
     * @param services the engine services, which record results
     * @param adapterPool runs adapter calls; at least {@link #threadsNeeded} threads
     * @param timeouts abandons calls at their step timeout
     */
    public AsyncStepExecutor(JobStore jobs, TransactionRunner transactions, Clock clock, JobSettings settings,
            AsyncSettings async, Map<String, ServiceAdapter> adapters, ProcessServices services,
            ExecutorService adapterPool, ScheduledExecutorService timeouts) {
        this(jobs, transactions, clock, settings, async, List.of(new ServiceStepRunner(adapters)), services,
                adapterPool, timeouts);
    }

    /**
     * @param jobs job storage
     * @param transactions transaction runner on the host's transaction manager
     * @param clock engine clock
     * @param settings job settings, with this replica's id
     * @param async async settings; the slots of each kind come from {@link AsyncSettings#runner}
     * @param runners one runner per async job kind; a kind without a runner is never claimed by this replica
     * @param services the engine services, which record results
     * @param adapterPool runs adapter calls; at least {@link #threadsNeeded} threads, so no claimed call waits for a
     *     thread while its lease runs
     * @param timeouts abandons calls at their step timeout
     * @throws IllegalArgumentException when two runners run the same kind, or a runner's kind is not async
     */
    public AsyncStepExecutor(JobStore jobs, TransactionRunner transactions, Clock clock, JobSettings settings,
            AsyncSettings async, List<? extends AsyncStepRunner> runners, ProcessServices services,
            ExecutorService adapterPool, ScheduledExecutorService timeouts) {
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.transactions = TransactionRunner.tracked(Objects.requireNonNull(transactions,
                "transactions must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.services = Objects.requireNonNull(services, "services must not be null");
        this.adapterPool = Objects.requireNonNull(adapterPool, "adapterPool must not be null");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts must not be null");
        Objects.requireNonNull(async, "async must not be null");
        Map<String, Lane> byKind = new java.util.LinkedHashMap<>();
        for (AsyncStepRunner runner : runners) {
            String kind = runner.jobKind();
            if (!JobStore.isAsync(kind)) {
                throw new IllegalArgumentException("Runner for " + kind + ", which is no async job kind");
            }
            RunnerSettings lane = async.runner(kind, settings);
            if (byKind.putIfAbsent(kind, new Lane(runner, lane, new Semaphore(lane.slots()))) != null) {
                throw new IllegalArgumentException("Two runners for job kind " + kind);
            }
        }
        this.lanes = java.util.Collections.unmodifiableMap(byKind);
    }

    /**
     * @param async async settings
     * @param settings job settings
     * @param kinds the job kinds that have a runner
     * @return the threads an adapter pool needs so every slot of every kind can call at once
     */
    public static int threadsNeeded(AsyncSettings async, JobSettings settings, java.util.Collection<String> kinds) {
        return kinds.stream().mapToInt(kind -> async.runner(kind, settings).slots()).sum();
    }

    /** @return the job kinds this executor runs, in claim order */
    public java.util.Set<String> kinds() {
        return lanes.keySet();
    }

    /** @return adapter slots free now, over every kind */
    public int freeSlots() {
        return lanes.values().stream().mapToInt(l -> l.slots().availablePermits()).sum();
    }

    /**
     * @param jobKind an async job kind
     * @return the slots of that kind free now; 0 for a kind without a runner
     */
    public int freeSlots(String jobKind) {
        Lane lane = lanes.get(jobKind);
        return lane == null ? 0 : lane.slots().availablePermits();
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
        long deadline = System.nanoTime() + max.toNanos();
        for (Lane lane : lanes.values()) {
            int all = lane.settings().slots();
            long left = Math.max(0, deadline - System.nanoTime());
            if (!lane.slots().tryAcquire(all, left, TimeUnit.NANOSECONDS)) {
                return false;
            }
            lane.slots().release(all);
        }
        return true;
    }

    /**
     * Claims, for each job kind, as many due jobs of that kind as its runner has free slots, and hands each to the
     * adapter executor; returns without waiting for any call.
     *
     * @return the number of attempts started
     */
    public int pollOnce() {
        int started = 0;
        for (Map.Entry<String, Lane> lane : lanes.entrySet()) {
            int free = Math.min(lane.getValue().slots().availablePermits(), settings.batchSize());
            if (free < 1) {
                continue;
            }
            for (Claim job : claim(lane.getKey(), free)) {
                if (dispatch(job)) {
                    started++;
                }
            }
        }
        return started;
    }

    /**
     * TX1 alone, for every kind in turn: claims due jobs and issues each claim its completion token, at most
     * {@code limit} in total. Separate so a test can stop a replica between the claim and the call, as a crash would.
     *
     * @param limit at most this many jobs
     * @return the claimed attempts
     */
    public List<Claim> claim(int limit) {
        List<Claim> claimed = new java.util.ArrayList<>();
        for (String kind : lanes.keySet()) {
            if (claimed.size() >= limit) {
                break;
            }
            claimed.addAll(claim(kind, limit - claimed.size()));
        }
        return List.copyOf(claimed);
    }

    /**
     * TX1 for one kind: claims due jobs of that kind only and issues each claim its completion token.
     *
     * @param jobKind an async job kind with a runner
     * @param limit at most this many jobs
     * @return the claimed attempts
     */
    public List<Claim> claim(String jobKind, int limit) {
        if (!lanes.containsKey(jobKind)) {
            return List.of();
        }
        return transactions.inTransaction(() -> jobs.claimDueAsync(settings.replicaId(), clock.instant(), jobKind,
                limit).stream().map(job -> new Claim(job, services.issueCompletionToken(job))).toList());
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
        Lane lane = lanes.get(job.jobKind());
        Semaphore slots = lane.slots();
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
                    record(job, new StepResult.Failed(JobStore.ASYNC_AGENT.equals(job.jobKind()) ? AGENT_TIMEOUT
                            : TIMEOUT, "No answer within " + timeout, true));
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
        Lane lane = lanes.get(job.jobKind());
        if (lane == null) {
            return new AdapterResult.Failed("RUNNER_NOT_REGISTERED", "No runner for job kind " + job.jobKind(), false);
        }
        Map<String, Object> payload = Values.readMap(job.payloadJson());
        String target = String.valueOf(payload.get("target"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = payload.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Instant claimedAt = job.lockedUntil().minus(job.leaseDuration());
        AdapterCall call = new AdapterCall(job.tenantId(), job.instanceId(), job.nodeId(),
                payload.get("businessKey") == null ? null : String.valueOf(payload.get("businessKey")), job.id(),
                ProcessServices.attemptId(job.id(), job.attempt()), claim.completionToken(), job.attempt(),
                claimedAt.plus(timeoutOf(job)), inputs);
        try {
            AdapterResult result = lane.runner().run(target, call);
            return result == null ? new AdapterResult.Failed("ADAPTER_NULL_RESULT", "The adapter returned null", true)
                    : result;
        } catch (RuntimeException e) {
            // The class only: an adapter's message may carry personal data.
            LOG.log(System.Logger.Level.WARNING, "process.adapter.error kind={0} target={1} job={2} attempt={3}"
                    + " exception={4}", job.jobKind(), target, job.id(), job.attempt(), e.getClass().getName());
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

    /** The step timeout stored at park, or the default timeout of the job's kind. */
    private Duration timeoutOf(JobRow job) {
        Object ms = Values.readMap(job.payloadJson()).get("timeoutMs");
        if (ms instanceof Number n) {
            return Duration.ofMillis(n.longValue());
        }
        Lane lane = lanes.get(job.jobKind());
        return lane == null ? AsyncSettings.DEFAULT_TIMEOUT : lane.settings().defaultTimeout();
    }

    private static StepResult toStep(AdapterResult result) {
        return switch (result) {
            case AdapterResult.Completed c -> AnswerLabels.step(c);
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
