package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Joins the caller's transaction on the host's transaction manager, or opens one ({@code REQUIRED}). */
public final class ProcessEngineTransactions implements TransactionRunner {

    private static final System.Logger LOG = System.getLogger(ProcessEngineTransactions.class.getName());

    private final TransactionTemplate template;

    /** @param transactionManager the host's transaction manager */
    public ProcessEngineTransactions(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }

    @Override
    public void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException e) {
                    // A measurement never fails a state change that has already committed.
                    LOG.log(System.Logger.Level.WARNING, "process.after-commit.failed exception={0}",
                            e.getClass().getName());
                }
            }
        });
    }

    @Override
    public boolean activeOnThisThread() {
        return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
    }
}
