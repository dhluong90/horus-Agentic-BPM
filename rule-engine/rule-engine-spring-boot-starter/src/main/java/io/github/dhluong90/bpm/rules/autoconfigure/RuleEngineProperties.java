package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the Rule Engine under {@code horus.bpm.rules}. Names follow the wiki page
 * "Rule Engine Configuration"; properties are added with the features that use them. Numeric defaults
 * come from {@link EngineDefaults} only (TR-EXT-04).
 *
 * @param enabled whether the Rule Engine is auto-configured at all
 * @param migrateOnStart whether the engine applies its own {@code rle_*} migrations at start-up on the
 *     host {@code DataSource}; {@code false} for hosts that run migrations in a separate job
 * @param cache the compiled-version cache
 * @param idempotency idempotency records
 * @param evaluation limits on evaluation inputs
 * @param query bounds of list reads
 * @param lifecycle version lifecycle commands
 * @param security how host authorities map to engine permissions
 * @param authoring how rule changes may be made on this host
 */
@ConfigurationProperties("horus.bpm.rules")
public record RuleEngineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean migrateOnStart,
        @DefaultValue Cache cache,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Evaluation evaluation,
        @DefaultValue Query query,
        @DefaultValue Lifecycle lifecycle,
        @DefaultValue Security security,
        @DefaultValue Authoring authoring) {

    /**
     * How rule changes may be made on this host (PD-075, PD-094).
     *
     * @param cockpitDrafts whether the cockpit may create and edit drafts; default false (PD-094). Published to
     *     clients as the rules setting {@code authoring.cockpit-drafts} of {@code GET /api/bpm/meta}. Import from a
     *     file is not affected
     */
    public record Authoring(Boolean cockpitDrafts) {
    }

    /**
     * The compiled-version cache.
     *
     * @param maxEntries compiled versions kept per replica, 1 to 100000; default 1000. The least recently used
     *     entry leaves first, counted by {@code horus.rle.cache.evictions{cause=size}}
     * @param epochCheckInterval how often each replica compares its cached rule sets with their {@code cache_epoch},
     *     {@code PT0.1S} to {@code PT5M}; default {@code PT5S}. The longest a replica serves a version after another
     *     replica's approval when no notification arrives (ADR-0009)
     * @param listenNotify hold one session connection from the pool for {@code LISTEN horus_rle_cache}, so replicas
     *     refresh within a second; default true. Set false behind a transaction-mode connection pooler, which drops
     *     notifications; the epoch check alone then keeps the bound
     */
    public record Cache(Integer maxEntries, Duration epochCheckInterval, Boolean listenNotify) {

        /** Validates the interval; an out-of-range value fails start-up naming the property. */
        public Cache {
            if (epochCheckInterval != null && (epochCheckInterval.compareTo(Duration.ofMillis(100)) < 0
                    || epochCheckInterval.compareTo(Duration.ofMinutes(5)) > 0)) {
                throw new IllegalArgumentException("horus.bpm.rules.cache.epoch-check-interval must be PT0.1S to PT5M, "
                        + "was " + epochCheckInterval);
            }
        }

        /**
         * Returns the interval in force.
         *
         * @return the configured interval, or {@code PT5S}
         */
        public Duration epochCheckIntervalOrDefault() {
            return epochCheckInterval == null ? EngineDefaults.CACHE_EPOCH_CHECK_INTERVAL : epochCheckInterval;
        }

        /**
         * Returns whether the listener runs.
         *
         * @return the configured value, or true
         */
        public boolean listenNotifyOrDefault() {
            return listenNotify == null || listenNotify;
        }
    }

    /**
     * Idempotency records (ADR-0006).
     *
     * @param retention how long a key is remembered, PT1M to P30D; default PT24H
     */
    public record Idempotency(Duration retention) {
    }

    /**
     * Limits on evaluation inputs.
     *
     * @param maxInputScale the largest absolute scale of a number input, 0 to 34; default 34. May be lowered,
     *     never raised
     * @param rejectUndeclaredInputs refuse input names the rule set does not declare; default true. When false,
     *     undeclared inputs are ignored and never logged
     * @param maxOutputBytes estimated JSON size of one decision's outputs, 1 to 1048576; default 1048576. Lower only
     * @param maxStringLength longest string input or string built by {@code +}, 1 to 65536; default 65536. Lower only
     * @param maxRows most rules a version may contain, checked at import, 1 to 5000; default 5000. Lower only
     */
    public record Evaluation(Integer maxInputScale, Boolean rejectUndeclaredInputs, Integer maxOutputBytes,
            Integer maxStringLength, Integer maxRows) {
    }

    /**
     * Bounds of list reads (ADR-0015).
     *
     * @param maxPageSize the largest {@code limit} of {@code GET /rule-sets} and {@code GET .../versions}, 1 to 200;
     *     default 200. May be lowered, never raised
     */
    public record Query(Integer maxPageSize) {
    }

    /**
     * Version lifecycle commands.
     *
     * @param reasonMaxLength the longest reason of {@code :reject}, in characters, 1 to 2000; default 2000. May be
     *     lowered, never raised
     */
    public record Lifecycle(Integer reasonMaxLength) {
    }

    /**
     * Returns the validated settings; an out-of-range value fails start-up naming the property.
     *
     * @return the engine settings
     */
    public EngineSettings toSettings() {
        return new EngineSettings(
                cache.maxEntries() == null ? EngineDefaults.CACHE_MAX_ENTRIES : cache.maxEntries(),
                idempotency.retention() == null ? EngineDefaults.IDEMPOTENCY_RETENTION : idempotency.retention(),
                evaluation.maxInputScale() == null ? EngineDefaults.INPUT_MAX_SCALE : evaluation.maxInputScale(),
                evaluation.rejectUndeclaredInputs() == null
                        ? EngineDefaults.REJECT_UNDECLARED_INPUTS : evaluation.rejectUndeclaredInputs(),
                query.maxPageSize() == null ? EngineDefaults.QUERY_MAX_PAGE_SIZE : query.maxPageSize(),
                lifecycle.reasonMaxLength() == null ? EngineDefaults.REASON_MAX_LENGTH : lifecycle.reasonMaxLength(),
                evaluation.maxOutputBytes() == null ? EngineDefaults.OUTPUT_MAX_BYTES : evaluation.maxOutputBytes(),
                evaluation.maxStringLength() == null ? EngineDefaults.STRING_MAX_LENGTH : evaluation.maxStringLength(),
                evaluation.maxRows() == null ? EngineDefaults.RULES_MAX : evaluation.maxRows());
    }

    /**
     * Security settings.
     *
     * @param rolePermissions host authority, for example {@code ROLE_PRICING_APPROVER}, to the
     *     {@code rules:*} permissions it grants; empty when the host issues permission strings directly
     */
    public record Security(Map<String, List<String>> rolePermissions) {

        /** Copies the mapping; an unset mapping is empty. */
        public Security {
            rolePermissions = rolePermissions == null ? Map.of() : Map.copyOf(rolePermissions);
        }
    }
}
