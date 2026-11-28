package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.service.DecisionQuerySettings;
import io.github.dhluong90.bpm.rules.internal.service.EngineContext;
import io.github.dhluong90.bpm.rules.internal.service.HostFunctions;
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
        return create(dataSource, transactions, tenants, callers, clock, settings, masking, querySettings,
                io.github.dhluong90.bpm.rules.internal.service.CatalogueExplainer.bundled());
    }

    /**
     * Builds the entry points with a masking policy, search bounds and the renderer of decision explanations
     * (RLE-030).
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, wrapped
     * @param tenants resolves the tenant of a call
     * @param callers resolves the caller of a call
     * @param clock the host clock
     * @param settings validated engine settings
     * @param masking the masking key and default per tenant
     * @param querySettings validated bounds of decision log searches
     * @param explainer writes the text of decision explanations
     * @return the entry points
     */
    public static RuleEngines create(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, Clock clock, EngineSettings settings, MaskingPolicy masking,
            DecisionQuerySettings querySettings, io.github.dhluong90.bpm.rules.spi.DecisionExplainer explainer) {
        return create(dataSource, transactions, tenants, callers, clock, settings, masking, querySettings, explainer,
                java.util.List.of(), HostFunctions.VersionChange.WARN);
    }

    /**
     * Builds the entry points with the functions the host registered for rule cells (RLE-055 to RLE-058). The versions
     * each approved rule set version was approved with are kept in {@code rle_version_function}.
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, wrapped
     * @param tenants resolves the tenant of a call
     * @param callers resolves the caller of a call
     * @param clock the host clock
     * @param settings validated engine settings
     * @param masking the masking key and default per tenant
     * @param querySettings validated bounds of decision log searches
     * @param explainer writes the text of decision explanations
     * @param functions the host's functions; none is allowed
     * @param onVersionChange what evaluation does when a function's version is not the approved one
     * @return the entry points
     * @throws IllegalArgumentException naming the function, when a name is registered twice, is a built-in name or is
     *     not a valid name
     */
    public static RuleEngines create(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, Clock clock, EngineSettings settings, MaskingPolicy masking,
            DecisionQuerySettings querySettings, io.github.dhluong90.bpm.rules.spi.DecisionExplainer explainer,
            java.util.Collection<io.github.dhluong90.bpm.expr.spi.ExpressionFunction> functions,
            HostFunctions.VersionChange onVersionChange) {
        return create(dataSource, transactions, tenants, callers, clock, settings, masking, querySettings, explainer,
                functions, onVersionChange, io.github.dhluong90.bpm.rules.spi.ApprovalSealKey.none());
    }

    /**
     * Builds the entry points with a key for the approval seal (RLE-223): with a key the seal written at approval is an
     * HMAC-SHA256 whose key is held outside the database.
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, wrapped
     * @param tenants resolves the tenant of a call
     * @param callers resolves the caller of a call
     * @param clock the host clock
     * @param settings validated engine settings
     * @param masking the masking key and default per tenant
     * @param querySettings validated bounds of decision log searches
     * @param explainer writes the text of decision explanations
     * @param functions the host's functions; none is allowed
     * @param onVersionChange what evaluation does when a function's version is not the approved one
     * @param sealKey the seal key; {@code ApprovalSealKey.none()} for plain seals
     * @return the entry points
     */
    public static RuleEngines create(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, Clock clock, EngineSettings settings, MaskingPolicy masking,
            DecisionQuerySettings querySettings, io.github.dhluong90.bpm.rules.spi.DecisionExplainer explainer,
            java.util.Collection<io.github.dhluong90.bpm.expr.spi.ExpressionFunction> functions,
            HostFunctions.VersionChange onVersionChange, io.github.dhluong90.bpm.rules.spi.ApprovalSealKey sealKey) {
        return create(dataSource, transactions, tenants, callers, clock, settings, masking, querySettings, explainer,
                functions, onVersionChange, sealKey, new JdbcDecisionLog(dataSource));
    }

    /**
     * Builds the entry points with the decision log the host chose: the transactional {@link JdbcDecisionLog}, which
     * every other overload uses, or the opt-in {@link AsyncDecisionLog} (RLE-230).
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, wrapped
     * @param tenants resolves the tenant of a call
     * @param callers resolves the caller of a call
     * @param clock the host clock
     * @param settings validated engine settings
     * @param masking the masking key and default per tenant
     * @param querySettings validated bounds of decision log searches
     * @param explainer writes the text of decision explanations
     * @param functions the host's functions; none is allowed
     * @param onVersionChange what evaluation does when a function's version is not the approved one
     * @param sealKey the seal key; {@code ApprovalSealKey.none()} for plain seals
     * @param decisionLog where decisions are written
     * @return the entry points
     */
    public static RuleEngines create(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, Clock clock, EngineSettings settings, MaskingPolicy masking,
            DecisionQuerySettings querySettings, io.github.dhluong90.bpm.rules.spi.DecisionExplainer explainer,
            java.util.Collection<io.github.dhluong90.bpm.expr.spi.ExpressionFunction> functions,
            HostFunctions.VersionChange onVersionChange, io.github.dhluong90.bpm.rules.spi.ApprovalSealKey sealKey,
            io.github.dhluong90.bpm.rules.internal.store.DecisionLog decisionLog) {
        JdbcIdempotencyStore idempotency = new JdbcIdempotencyStore(dataSource);
        EngineContext context = new EngineContext(tenants, callers, clock, transactions, idempotency, settings,
                masking, HostFunctions.of(functions, new JdbcFunctionVersionStore(dataSource), onVersionChange),
                new JdbcSimulationReportStore(dataSource), sealKey);
        return RuleEngines.create(context, new JdbcRuleStore(dataSource), decisionLog,
                new JdbcEventOutbox(dataSource), querySettings, explainer);
    }
}
