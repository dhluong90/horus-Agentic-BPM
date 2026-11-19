package com.horus.bpm.rules.autoconfigure;

import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;

/**
 * Checks that the engine's DataSource and the host's transaction manager are the same resource.
 *
 * <p>Engine writes join the caller's transaction only when the transaction manager manages the
 * DataSource the engine writes through (RLE-013). If they differ, engine writes would commit on their
 * own and nothing would fail, so a mismatch stops the start-up.
 */
final class HostResources {

    private static final Log LOG = LogFactory.getLog(HostResources.class);

    /** Result of the check. */
    enum Check { SAME_RESOURCE, NOT_VERIFIABLE }

    private HostResources() {
    }

    /**
     * Fails when the transaction manager manages a different DataSource than the engine's.
     *
     * @param engineDataSource the DataSource the engine writes through
     * @param transactionManager the host's transaction manager
     * @return {@link Check#SAME_RESOURCE}, or {@link Check#NOT_VERIFIABLE} (logged as a warning) when
     *     the transaction manager does not expose a DataSource, for example a JPA transaction manager
     * @throws IllegalStateException when both expose DataSources and they differ
     */
    static Check requireSameResource(DataSource engineDataSource, PlatformTransactionManager transactionManager) {
        if (transactionManager instanceof ResourceTransactionManager resourceManager
                && resourceManager.getResourceFactory() instanceof DataSource managed) {
            if (unwrap(managed) != unwrap(engineDataSource)) {
                throw new IllegalStateException("The Rule Engine writes through DataSource " + describe(engineDataSource)
                        + " but the host transaction manager " + transactionManager.getClass().getName()
                        + " manages " + describe(managed) + ". Engine writes would not join the caller's transaction. "
                        + "Make the transaction manager manage the engine's DataSource.");
            }
            return Check.SAME_RESOURCE;
        }
        LOG.warn("The Rule Engine cannot verify that transaction manager " + transactionManager.getClass().getName()
                + " manages DataSource " + describe(engineDataSource) + ". Engine writes join the caller's transaction "
                + "only if it does.");
        return Check.NOT_VERIFIABLE;
    }

    private static DataSource unwrap(DataSource dataSource) {
        DataSource current = dataSource;
        while (current instanceof DelegatingDataSource delegating && delegating.getTargetDataSource() != null) {
            current = delegating.getTargetDataSource();
        }
        return current;
    }

    private static String describe(DataSource dataSource) {
        return dataSource.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(dataSource));
    }
}
