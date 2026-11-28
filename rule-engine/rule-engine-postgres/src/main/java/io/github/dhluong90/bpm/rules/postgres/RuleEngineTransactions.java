package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.store.TransactionRunner;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The engine's access to the host's transactions.
 *
 * <p>Engine writes made on behalf of a caller join the caller's transaction (RLE-013). Engine-internal
 * work that has no caller, such as partition maintenance and the outbox relay, runs in a new
 * transaction of the same host transaction manager. The engine never creates a transaction manager
 * of its own (TR-EMB-04).
 *
 * <p>Deliberately not a {@code TransactionOperations} bean, so it never replaces or competes with the
 * host's own {@code TransactionTemplate}.
 */
public final class RuleEngineTransactions implements TransactionRunner {

    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate newTransaction;
    private final TransactionTemplate callerTransaction;
    private final TransactionTemplate readOnlyTransaction;

    /**
     * Creates the engine's transaction access on the host's transaction manager.
     *
     * @param transactionManager the host's transaction manager
     */
    public RuleEngineTransactions(PlatformTransactionManager transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.callerTransaction = new TransactionTemplate(transactionManager);
        this.callerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * Runs engine work on behalf of a caller: in the caller's transaction when there is one, so the
     * engine's rows commit or roll back with the caller's work (RLE-013), otherwise in a new one.
     *
     * @param work the work to run
     * @param <T> the result type
     * @return the work's result
     */
    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Objects.requireNonNull(work, "work must not be null");
        return callerTransaction.execute(status -> work.get());
    }

    /**
     * Runs work in a new transaction ({@code REQUIRES_NEW}), whatever the caller's transaction: used for the one
     * short insert-or-read of the first-use masking key check.
     *
     * @param work the work to run
     * @param <T> the result type
     * @return the work's result
     */
    @Override
    public <T> T inIndependentTransaction(Supplier<T> work) {
        Objects.requireNonNull(work, "work must not be null");
        return newTransaction.execute(status -> work.get());
    }

    /**
     * Runs a read in a read-only transaction ({@code REQUIRED}: it joins a caller's transaction when there is one,
     * which never happens on the scheduler thread that uses it). Never suspends a transaction or takes a second
     * connection.
     *
     * @param work the work to run
     * @param <T> the result type
     * @return the work's result
     */
    @Override
    public <T> T inReadOnlyTransaction(Supplier<T> work) {
        Objects.requireNonNull(work, "work must not be null");
        return readOnlyTransaction.execute(status -> work.get());
    }

    /**
     * Runs work after the current Spring transaction commits, or at once when no transaction synchronisation is
     * active. Work of a transaction that rolls back never runs. A failure of the work is not propagated: the
     * transaction has committed already.
     *
     * @param work the work
     */
    @Override
    public void afterCommit(Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                work.run();
            }
        });
    }

    /**
     * Returns the host transaction manager the engine uses.
     *
     * @return the host's transaction manager
     */
    public PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    /**
     * Runs engine-internal work in a new host transaction, committed or rolled back on return.
     *
     * @param work the work to run
     * @param <T> the result type
     * @return the work's result
     */
    public <T> T inNewTransaction(TransactionCallback<T> work) {
        return newTransaction.execute(Objects.requireNonNull(work, "work must not be null"));
    }
}
