package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow;
import java.util.function.BooleanSupplier;

/**
 * Fires one kind of job inside the fire transaction opened by {@link JobExecutor}. Database work only, no network
 * I/O (DESIGN section 4).
 */
@FunctionalInterface
public interface JobHandler {

    /**
     * Locks the aggregate the job belongs to (for example the {@code pre_instance} row), then calls
     * {@code claimHeld}, which locks the job row and answers whether this replica still holds the claim. Locking in
     * that order matches every other writer of the aggregate, so a fire transaction and a user command never
     * deadlock.
     *
     * @param job the claimed job
     * @param claimHeld locks the job row; false means another replica took the job over or it was cancelled
     * @return true when the job was consumed (fired, or found obsolete) and may be marked {@code DONE}; false when the
     *     claim was not held and nothing was written
     */
    boolean fire(JobRow job, BooleanSupplier claimHeld);
}
