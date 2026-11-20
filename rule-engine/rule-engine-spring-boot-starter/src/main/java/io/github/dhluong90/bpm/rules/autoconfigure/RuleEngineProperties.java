package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.DecisionQuerySettings;
import io.github.dhluong90.bpm.rules.internal.expr.RegexLimits;
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
 * @param query bounds of list reads and decision log searches
 * @param lifecycle version lifecycle commands
 * @param security how host authorities map to engine permissions
 * @param authoring how rule changes may be made on this host
 * @param masking keys of the keyed fingerprints of masked inputs, for the host and per tenant
 * @param decisionLog what the decision log records and how far ahead its partitions are created
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
        @DefaultValue Authoring authoring,
        @DefaultValue Masking masking,
        @DefaultValue DecisionLog decisionLog) {

    /**
     * Keys of the keyed fingerprints (HMAC-SHA256) of masked inputs (RLE-012, RLE-035). Supply every key from an
     * environment variable or a secret store, never from a file in the repository. A host that needs a key per
     * tenant from a vault declares its own {@code MaskingPolicy} bean instead.
     *
     * @param hmacKey Base64 key of at least 32 bytes after decoding, for every tenant without its own key. When
     *     unset, the shared {@code horus.bpm.masking.hmac-key} is used. Start-up fails when a rule set masks an input
     *     and its tenant has no valid key
     * @param hmacKeyId the id stored in every fingerprint of {@code hmacKey} ({@code hmac:<keyId>:<hex>}), 1 to 32
     *     letters, digits, {@code _} or {@code -}; default {@code k1}. Not secret. Change it together with the key
     * @param tenants per-tenant settings, by tenant id; a tenant listed here with a key never uses the host key
     * @param search limits of the masked-input search
     */
    public record Masking(String hmacKey, String hmacKeyId, Map<String, TenantMasking> tenants,
            @DefaultValue Search search) {

        /** Copies the tenants; unset is empty. */
        public Masking {
            tenants = tenants == null ? Map.of() : Map.copyOf(tenants);
        }

        /** Never prints a key. */
        @Override
        public String toString() {
            return "Masking[hmacKey=" + (hmacKey == null ? "unset" : "set") + ", hmacKeyId=" + hmacKeyId + ", tenants="
                    + tenants.keySet() + "]";
        }
    }

    /**
     * Limits of the masked-input search ({@code POST /decisions:find-by-masked-input}).
     *
     * @param maxPerMinute searches one caller may run per minute on one replica, at least 1; default 30. It cannot be
     *     switched off. Past it the search is refused with {@code 429 RATE_LIMITED} and the refusal is audited
     */
    public record Search(Integer maxPerMinute) {
    }

    /**
     * Masking settings of one tenant.
     *
     * @param hmacKey the tenant's Base64 key of at least 32 bytes after decoding; unset uses the host key
     * @param hmacKeyId the id stored in every fingerprint of the tenant's key; default {@code k1}
     * @param maskDefault whether this tenant's inputs that declare no {@code mask} are masked; unset uses
     *     {@code decision-log.mask-default}
     * @param search the tenant's own search limit; unset uses {@code masking.search}
     */
    public record TenantMasking(String hmacKey, String hmacKeyId, Boolean maskDefault, Search search) {

        /** Never prints a key. */
        @Override
        public String toString() {
            return "TenantMasking[hmacKey=" + (hmacKey == null ? "unset" : "set") + ", hmacKeyId=" + hmacKeyId
                    + ", maskDefault=" + maskDefault + "]";
        }
    }

    /**
     * What the decision log records, and its maintenance (RLE-017).
     *
     * @param maskDefault whether inputs that declare no {@code mask} are masked, for every tenant without its own
     *     setting; default false. An input with {@code mask: false} is never masked by this default
     * @param partitionsAhead how far ahead of the host clock monthly partitions are created, at start-up and daily;
     *     {@code PT0S} to {@code P366D}, default {@code P7D}. The current and the next month always exist
     */
    public record DecisionLog(Boolean maskDefault, Duration partitionsAhead) {

        /** Validates the period; an out-of-range value fails start-up naming the property. */
        public DecisionLog {
            if (partitionsAhead != null && (partitionsAhead.isNegative()
                    || partitionsAhead.compareTo(EngineDefaults.PARTITIONS_AHEAD_MAX) > 0)) {
                throw new IllegalArgumentException("horus.bpm.rules.decision-log.partitions-ahead must be PT0S to "
                        + "P366D, was " + partitionsAhead);
            }
        }

        /**
         * Returns the period in force.
         *
         * @return the configured period, or {@code P7D}
         */
        public Duration partitionsAheadOrDefault() {
            return partitionsAhead == null ? EngineDefaults.PARTITIONS_AHEAD : partitionsAhead;
        }
    }

    /**
     * How rule changes may be made on this host (PD-075, PD-094).
     *
     * @param cockpitDrafts whether drafts may be created and edited from the cockpit; default false (PD-094). Published
     *     to clients as the rules setting {@code authoring.cockpit-drafts} of {@code GET /api/bpm/meta}, and enforced by
     *     the engine: when false, replacing a draft ({@code PUT .../versions/{v}}) is refused with
     *     {@code 403 DRAFT_AUTHORING_DISABLED}. Import from a file is not affected
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
     * @param regexTimeoutMs longest time one {@code matches()} may run, in milliseconds, 1 to 1000; default 50. A
     *     match past it fails the decision with {@code EVALUATION_ERROR}, reason {@code REGEX_TIMEOUT}
     * @param regexMaxInputLength longest text {@code matches()} looks at, 1 to {@code max-string-length}; default
     *     4096. A longer text fails the decision without running the match, reason {@code REGEX_INPUT_TOO_LONG}
     * @param regexMaxReads characters one {@code matches()} may read, 1 to 1000000; default 1000000. The deterministic
     *     primary bound, the same on every machine; past it the decision fails with {@code REGEX_TIMEOUT}. Lower only
     * @param regexMaxReadsPerDecision characters all the {@code matches()} of one decision may read together, 1 to
     *     5000000; default 5000000. Counted the same deterministic way as {@code regex-max-reads}, so a rule set of
     *     many {@code matches()} rows stops at this total; past it the decision fails with {@code REGEX_TIMEOUT}.
     *     Lower only
     * @param pinRetired whether a pinned evaluation ({@code rules:pin}) may name a {@code RETIRED} version; default
     *     false. Replay of a logged decision is not affected
     */
    public record Evaluation(Integer maxInputScale, Boolean rejectUndeclaredInputs, Integer maxOutputBytes,
            Integer maxStringLength, Integer maxRows, Integer regexTimeoutMs, Integer regexMaxInputLength,
            Integer regexMaxReads, Integer regexMaxReadsPerDecision, Boolean pinRetired) {
    }

    /**
     * Bounds of list reads (ADR-0015).
     *
     * @param maxPageSize the largest {@code limit} of {@code GET /rule-sets}, {@code GET .../versions} and
     *     {@code GET /decisions}, 1 to 200; default 200. May be lowered, never raised
     * @param maxRange the longest period of {@code GET /decisions}, {@code PT1H} to {@code P31D}; default {@code P31D}.
     *     May be lowered, never raised
     * @param statementTimeout the longest one {@code GET /decisions} page may run in the database, {@code PT0.1S} to
     *     {@code PT30S}; default {@code PT2S}
     */
    public record Query(Integer maxPageSize, Duration maxRange, Duration statementTimeout) {
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
                evaluation.maxRows() == null ? EngineDefaults.RULES_MAX : evaluation.maxRows(),
                new RegexLimits(
                        evaluation.regexTimeoutMs() == null ? RegexLimits.TIMEOUT_MILLIS : evaluation.regexTimeoutMs(),
                        evaluation.regexMaxInputLength() != null ? evaluation.regexMaxInputLength()
                                // A host that lowers max-string-length lowers the default regex length with it;
                                // an invalid max-string-length is reported by the settings, naming that property.
                                : Math.max(1, Math.min(RegexLimits.MAX_INPUT_LENGTH, evaluation.maxStringLength() == null
                                        ? EngineDefaults.STRING_MAX_LENGTH : evaluation.maxStringLength())),
                        evaluation.regexMaxReads() == null ? RegexLimits.MAX_READS : evaluation.regexMaxReads(),
                        evaluation.regexMaxReadsPerDecision() == null
                                ? RegexLimits.MAX_READS_PER_DECISION : evaluation.regexMaxReadsPerDecision()))
                .withDraftAuthoring(Boolean.TRUE.equals(authoring.cockpitDrafts()))
                .withPinRetired(evaluation.pinRetired() == null ? EngineDefaults.PIN_RETIRED : evaluation.pinRetired())
                .withLifecycle(
                        lifecycle.systemActor() == null ? EngineDefaults.SYSTEM_ACTOR : lifecycle.systemActor(),
                        EngineDefaults.RETIRE_APPROVAL);
    }

    /**
     * Returns the validated bounds of decision log searches; an out-of-range value fails start-up naming the property.
     *
     * @return the search settings
     */
    public DecisionQuerySettings toQuerySettings() {
        return new DecisionQuerySettings(
                query.maxRange() == null ? EngineDefaults.QUERY_MAX_RANGE : query.maxRange(),
                query.statementTimeout() == null ? EngineDefaults.QUERY_STATEMENT_TIMEOUT : query.statementTimeout());
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
