package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.QueryTimeoutException;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Joins the caller's transaction on the host's transaction manager, or opens one ({@code REQUIRED}). List reads run
 * in their own read-only transaction with a statement timeout instead ({@link #inReadTransaction}).
 */
public final class ProcessEngineTransactions implements TransactionRunner {

    private static final System.Logger LOG = System.getLogger(ProcessEngineTransactions.class.getName());

    /** SQLSTATE of a statement the server cancelled, here by {@code statement_timeout}. */
    private static final String QUERY_CANCELED = "57014";

    private final TransactionTemplate template;
    private final TransactionTemplate readTemplate;
    private final JdbcClient jdbc;

    /** @param transactionManager the host's transaction manager; reads are then not time-limited */
    public ProcessEngineTransactions(PlatformTransactionManager transactionManager) {
        this(transactionManager, null);
    }

    /**
     * @param transactionManager the host's transaction manager
     * @param jdbc a client on the data source of that transaction manager, which sets the statement timeout of a read;
     *     {@code null} leaves reads without a limit
     */
    public ProcessEngineTransactions(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this.template = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readTemplate = new TransactionTemplate(transactionManager);
        this.readTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTemplate.setReadOnly(true);
        this.jdbc = jdbc;
    }

    @Override
    public <T> T inReadTransaction(Duration limit, Supplier<T> work) {
        if (jdbc == null || limit == null) {
            return inTransaction(work);
        }
        try {
            return readTemplate.execute(status -> {
                // Transaction-local, so the setting never outlives the read or reaches a pooled connection's next use.
                jdbc.sql("SELECT set_config('statement_timeout', ?, true)").param(limit.toMillis() + "ms").query().singleValue();
                return work.get();
            });
        } catch (DataAccessException e) {
            if (isCanceled(e)) {
                throw new QueryTimeoutException("The read took longer than " + limit + " (query.statement-timeout) and "
                        + "was stopped; narrow the filter or try again");
            }
            throw e;
        }
    }

    private static boolean isCanceled(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                return false;
            }
        }
        return false;
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
