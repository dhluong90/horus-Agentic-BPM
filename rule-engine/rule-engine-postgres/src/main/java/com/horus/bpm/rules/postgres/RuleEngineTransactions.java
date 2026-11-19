package com.horus.bpm.rules.postgres;

import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
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
public final class RuleEngineTransactions {

    private final PlatformTransactionManager transactionManager;
    private final TransactionTemplate newTransaction;

    /**
     * Creates the engine's transaction access on the host's transaction manager.
     *
     * @param transactionManager the host's transaction manager
     */
    public RuleEngineTransactions(PlatformTransactionManager transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
