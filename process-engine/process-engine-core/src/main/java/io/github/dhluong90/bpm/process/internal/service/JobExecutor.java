package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Claims due jobs and fires them, replica-safe (PRE-006, DESIGN sections 4 and 9). One poll is:
 * <ol>
 *   <li>a short claim transaction ({@link JobStore#claimDue}, {@code FOR UPDATE SKIP LOCKED}), committed before any
 *       job fires, so the lease is visible to every replica;</li>
 *   <li>one fire transaction per job: the handler locks its aggregate and then the job row, fires only while this
 *       replica still holds the claim, and the job is marked {@code DONE} in that same transaction.</li>
 * </ol>
 * The fire and the {@code DONE} commit together, and a claim is fenced by its attempt number, so a job fires at most
 * once: a replica that dies mid-way rolls back, a replica whose lease another replica took over finds the claim gone
 * and writes nothing. A job claimed by a replica that died is claimed again after its lease. A firing that fails rolls
 * back and the job is due again after {@code jobs.retry-backoff} times the attempt, or {@code FAILED} after
 * {@code jobs.max-attempts}. Thread-safe; callers must not hold a transaction when they call {@link #pollOnce}.
 */
public final class JobExecutor {

    private static final System.Logger LOG = System.getLogger(JobExecutor.class.getName());

    private final JobStore jobs;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final JobSettings settings;
    private final Map<String, JobHandler> handlers;

    /**
     * @param jobs job storage
     * @param transactions transaction runner on the host's transaction manager
     * @param clock engine clock
     * @param settings job settings, with this replica's id
     * @param handlers handler per job kind
     */
    public JobExecutor(JobStore jobs, TransactionRunner transactions, Clock clock, JobSettings settings,
            Map<String, JobHandler> handlers) {
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.handlers = Map.copyOf(handlers);
    }

    /** @return this replica's id */
    public String replicaId() {
        return settings.replicaId();
    }

    /**
     * Claims at most {@code jobs.batch-size} due jobs and fires each in its own transaction.
     *
     * @return the number of jobs fired by this replica
     */
    public int pollOnce() {
        List<JobRow> claimed = claim();
        int fired = 0;
        for (JobRow job : claimed) {
            if (fire(job)) {
                fired++;
            }
        }
        return fired;
    }

    /**
     * The claim transaction alone; {@link #fire} completes each claimed job. Separate so a test can stop a replica
     * between the two, as a crash would.
     *
     * @return the claimed jobs
     */
    public List<JobRow> claim() {
        return transactions.inTransaction(() -> jobs.claimDue(settings.replicaId(), clock.instant(),
                settings.batchSize()));
    }

    /**
     * Fires one job claimed by this replica.
     *
     * @param job the claimed job, as returned by {@link #claim}
     * @return true when this call fired it
     */
    public boolean fire(JobRow job) {
        JobHandler handler = handlers.get(job.jobKind());
        try {
            return transactions.inTransaction(() -> {
                if (handler == null) {
                    // A kind this release cannot run was written by a newer replica during a rolling upgrade
                    // (PRE-218): hand it back untouched, attempt included, for a replica that knows it.
                    if (jobs.lockClaim(job.tenantId(), job.id(), settings.replicaId(), job.attempt(), clock.instant())
                            .isPresent()) {
                        Instant now = clock.instant();
                        jobs.defer(job.tenantId(), job.id(), settings.replicaId(), job.attempt(),
                                now.plus(settings.retryBackoff()), now);
                        LOG.log(System.Logger.Level.WARNING, "process.job.deferred tenant={0} job={1} kind={2}"
                                + " reason=kind unknown to this release (rolling upgrade)", job.tenantId(), job.id(),
                                job.jobKind());
                    }
                    return false;
                }
                if (job.attempt() > job.maxAttempts()) {
                    // A job whose firings keep dying with their replica: never fire it again; it stays visible as
                    // FAILED.
                    if (jobs.lockClaim(job.tenantId(), job.id(), settings.replicaId(), job.attempt(), clock.instant())
                            .isPresent()) {
                        jobs.release(job.tenantId(), job.id(), settings.replicaId(), job.attempt(), job.nextFireAt(),
                                true, clock.instant());
                        LOG.log(System.Logger.Level.WARNING, "process.job.failed tenant={0} job={1} kind={2} attempt={3}"
                                + " reason=max attempts reached", job.tenantId(), job.id(), job.jobKind(),
                                job.attempt());
                    }
                    return false;
                }
                boolean[] checked = {false};
                boolean consumed = handler.fire(job, () -> {
                    checked[0] = true;
                    return jobs.lockClaim(job.tenantId(), job.id(), settings.replicaId(), job.attempt(),
                            clock.instant()).isPresent();
                });
                if (!consumed) {
                    return false;
                }
                if (!checked[0] || !jobs.markDone(job.tenantId(), job.id(), settings.replicaId(), job.attempt(),
                        clock.instant())) {
                    // Rolls the firing back: without the claim, the work must not commit.
                    throw new IllegalStateException("Job " + job.id() + " fired without holding its claim");
                }
                return true;
            });
        } catch (RuntimeException e) {
            Instant now = clock.instant();
            boolean failed = job.attempt() >= job.maxAttempts();
            Instant retryAt = now.plus(settings.retryBackoff().multipliedBy(Math.max(1, job.attempt())));
            boolean released = transactions.inTransaction(() -> jobs.release(job.tenantId(), job.id(),
                    settings.replicaId(), job.attempt(), retryAt, failed, now));
            // The error code only, never the message: a handler's or a decision's text may carry personal data.
            String reason = switch (e) {
                case StepFailedException step -> step.reason();
                case ProcessException process -> process.code();
                default -> "-";
            };
            LOG.log(System.Logger.Level.WARNING, "process.job.fire.failed tenant={0} job={1} kind={2} attempt={3} "
                    + "released={4} failed={5} exception={6} reason={7}", job.tenantId(), job.id(), job.jobKind(),
                    job.attempt(), released, failed, e.getClass().getName(), reason);
            return false;
        }
    }
}
