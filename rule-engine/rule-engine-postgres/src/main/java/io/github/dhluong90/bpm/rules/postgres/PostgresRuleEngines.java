package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.service.DecisionQuerySettings;
import io.github.dhluong90.bpm.rules.internal.service.EngineContext;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.time.Clock;
import javax.sql.DataSource;

/**
 * Builds the engine's entry points on PostgreSQL, on the host's data source and transaction manager.
 */
public final class PostgresRuleEngines {

    private PostgresRuleEngines() {
    }

    /**
     * Builds {@code RuleEngine} and {@code VersionService}.
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, wrapped
     * @param tenants resolves the tenant of a call
     * @param callers resolves the caller of a call
     * @param clock the host clock
     * @param settings validated engine settings
     * @return both entry points
     */
    public static RuleEngines create(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, Clock clock, EngineSettings settings) {
        return create(dataSource, transactions, tenants, callers, clock, settings, MaskingPolicy.none());
    }

    /**
     * Builds the entry points with a masking policy (RLE-012).
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, wrapped
     * @param tenants resolves the tenant of a call
     * @param callers resolves the caller of a call
     * @param clock the host clock
     * @param settings validated engine settings
     * @param masking the masking key and default per tenant
     * @return the entry points
     */
    public static RuleEngines create(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, Clock clock, EngineSettings settings, MaskingPolicy masking) {
        return create(dataSource, transactions, tenants, callers, clock, settings, masking, DecisionQuerySettings.defaults());
    }

    /**
     * Builds the entry points with the given decision log search bounds and no masking.
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, wrapped
     * @param tenants resolves the tenant of a call
     * @param callers resolves the caller of a call
     * @param clock the host clock
     * @param settings validated engine settings
     * @param querySettings validated bounds of decision log searches
     * @return the entry points
     */
    public static RuleEngines create(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, Clock clock, EngineSettings settings, DecisionQuerySettings querySettings) {
        return create(dataSource, transactions, tenants, callers, clock, settings, MaskingPolicy.none(), querySettings);
    }

    /**
     * Builds the entry points with a masking policy (RLE-012) and the given decision log search bounds (RLE-029).
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, wrapped
     * @param tenants resolves the tenant of a call
     * @param callers resolves the caller of a call
     * @param clock the host clock
     * @param settings validated engine settings
     * @param masking the masking key and default per tenant
     * @param querySettings validated bounds of decision log searches
     * @return the entry points
     */
    public static RuleEngines create(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, Clock clock, EngineSettings settings, MaskingPolicy masking,
            DecisionQuerySettings querySettings) {
        JdbcIdempotencyStore idempotency = new JdbcIdempotencyStore(dataSource);
        EngineContext context = new EngineContext(tenants, callers, clock, transactions, idempotency, settings,
                masking);
        return RuleEngines.create(context, new JdbcRuleStore(dataSource), new JdbcDecisionLog(dataSource),
                new JdbcEventOutbox(dataSource), querySettings);
    }
}
