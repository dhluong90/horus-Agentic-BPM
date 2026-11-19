package com.horus.bpm.rules.postgres;

import com.horus.bpm.rules.internal.service.EngineContext;
import com.horus.bpm.rules.internal.service.EngineSettings;
import com.horus.bpm.rules.internal.service.RuleEngines;
import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.TenantResolver;
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
        JdbcIdempotencyStore idempotency = new JdbcIdempotencyStore(dataSource);
        EngineContext context = new EngineContext(tenants, callers, clock, transactions, idempotency, settings);
        return RuleEngines.create(context, new JdbcRuleStore(dataSource), new JdbcDecisionLog(dataSource),
                new JdbcEventOutbox(dataSource));
    }
}
