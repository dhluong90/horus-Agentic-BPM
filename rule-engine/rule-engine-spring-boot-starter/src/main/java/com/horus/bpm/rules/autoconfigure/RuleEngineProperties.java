package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.internal.service.EngineDefaults;
import com.horus.bpm.rules.internal.service.EngineSettings;
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
 * @param masking keys of the keyed fingerprints of masked inputs, for the host and per tenant
 * @param decisionLog what the decision log records
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
     * @param tenants per-tenant settings, by tenant id; a tenant listed here with a key never uses the host key
     */
    public record Masking(String hmacKey, Map<String, TenantMasking> tenants) {

        /** Copies the tenants; unset is empty. */
        public Masking {
            tenants = tenants == null ? Map.of() : Map.copyOf(tenants);
        }

        /** Never prints a key. */
        @Override
        public String toString() {
            return "Masking[hmacKey=" + (hmacKey == null ? "unset" : "set") + ", tenants=" + tenants.keySet() + "]";
        }
    }

    /**
     * Masking settings of one tenant.
     *
     * @param hmacKey the tenant's Base64 key of at least 32 bytes after decoding; unset uses the host key
     * @param maskDefault whether this tenant's inputs that declare no {@code mask} are masked; unset uses
     *     {@code decision-log.mask-default}
     */
    public record TenantMasking(String hmacKey, Boolean maskDefault) {

        /** Never prints a key. */
        @Override
        public String toString() {
            return "TenantMasking[hmacKey=" + (hmacKey == null ? "unset" : "set") + ", maskDefault=" + maskDefault
                    + "]";
        }
    }

    /**
     * What the decision log records.
     *
     * @param maskDefault whether inputs that declare no {@code mask} are masked, for every tenant without its own
     *     setting; default false. An input with {@code mask: false} is never masked by this default
     */
    public record DecisionLog(Boolean maskDefault) {
    }

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
