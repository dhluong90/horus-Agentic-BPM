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
     * @param maxEntries compiled versions kept per replica, 1 to 100000; default 1000
     */
    public record Cache(Integer maxEntries) {
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
     * Version lifecycle commands and the automatic retirement of superseded versions (ADR-0025).
     *
     * @param reasonMaxLength the longest reason of {@code :reject} and {@code :retire}, in characters, 1 to 2000;
     *     default 2000. May be lowered, never raised
     * @param sweepInterval how often the lifecycle sweep retires versions a newer version in effect has superseded,
     *     {@code PT1S} to {@code PT1H}; default {@code PT1M}. Status only: selection follows the effective date
     * @param systemActor the actor recorded on the history row and event of a superseded version; it must start with
     *     {@code system:}, a namespace no caller id may use; default {@code system:supersession}
     */
    public record Lifecycle(Integer reasonMaxLength, Duration sweepInterval, String systemActor) {

        /** Shortest accepted {@code sweep-interval}. */
        static final Duration SWEEP_MIN = Duration.ofSeconds(1);

        /** Longest accepted {@code sweep-interval}: the status of a superseded version lags at most this long. */
        static final Duration SWEEP_MAX = Duration.ofHours(1);

        /**
         * Returns the validated sweep interval.
         *
         * @return the interval, the default when unset
         * @throws IllegalArgumentException when it is outside {@code PT1S} to {@code PT1H}, naming the property
         */
        public Duration sweepIntervalOrDefault() {
            Duration interval = sweepInterval == null ? EngineDefaults.SWEEP_INTERVAL : sweepInterval;
            if (interval.compareTo(SWEEP_MIN) < 0 || interval.compareTo(SWEEP_MAX) > 0) {
                throw new IllegalArgumentException("horus.bpm.rules.lifecycle.sweep-interval must be PT1S to PT1H,"
                        + " was " + interval);
            }
            return interval;
        }
    }

    /**
     * Returns the validated settings; an out-of-range value fails start-up naming the property.
     *
     * @return the engine settings
     */
    /**
     * Returns the validated settings, with the retirement settings shared by both engines read from the host
     * (PD-101, PD-102: {@code horus.bpm.lifecycle.retire-approval} and {@code horus.bpm.lifecycle.retire-last-version},
     * through bpm-host-metadata); an out-of-range value fails start-up naming the property.
     *
     * @param environment the host environment
     * @return the engine settings
     */
    public EngineSettings toSettings(org.springframework.core.env.Environment environment) {
        EngineSettings settings = toSettings();
        return settings.withLifecycle(settings.systemActor(),
                        io.github.dhluong90.bpm.host.autoconfigure.RetireApproval.from(environment).value())
                .withRetireLastVersion(io.github.dhluong90.bpm.host.autoconfigure.RetireLastVersion.from(environment)
                        .value());
    }

    /**
     * Returns the validated settings with the shared retirement settings at their defaults.
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
                evaluation.maxRows() == null ? EngineDefaults.RULES_MAX : evaluation.maxRows())
                .withLifecycle(
                        lifecycle.systemActor() == null ? EngineDefaults.SYSTEM_ACTOR : lifecycle.systemActor(),
                        EngineDefaults.RETIRE_APPROVAL);
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
