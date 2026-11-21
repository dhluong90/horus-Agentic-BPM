package io.github.dhluong90.bpm.rules.internal.service;

import java.time.Duration;
import java.util.List;

/**
 * The one place where the Rule Engine's numeric and duration defaults are declared (ADR-0024,
 * TR-EXT-04). Code reads them through {@link EngineSettings}; the starter binds host properties over them.
 */
public final class EngineDefaults {

    /** Default of {@code horus.bpm.rules.cache.max-entries}: compiled versions kept per replica. */
    public static final int CACHE_MAX_ENTRIES = 1000;

    /** Largest accepted {@code cache.max-entries}. */
    public static final int CACHE_MAX_ENTRIES_LIMIT = 100_000;

    /** How often each replica checks the {@code cache_epoch} of its cached rule sets (ADR-0009). */
    public static final Duration CACHE_EPOCH_CHECK_INTERVAL = Duration.ofSeconds(5);

    /** Default of {@code horus.bpm.rules.idempotency.retention}: how long a key is remembered (ADR-0006). */
    public static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    /** Shortest accepted {@code idempotency.retention}: a retry within a minute must still replay. */
    public static final Duration IDEMPOTENCY_RETENTION_MIN = Duration.ofMinutes(1);

    /** Longest accepted {@code idempotency.retention}. */
    public static final Duration IDEMPOTENCY_RETENTION_MAX = Duration.ofDays(30);

    /**
     * Significant digits of every number input and of number arithmetic: {@code MathContext.DECIMAL128}.
     * Part of the evaluation semantics (DESIGN section 3.2), so not a setting.
     */
    public static final int DECIMAL_PRECISION = 34;

    /**
     * Default and ceiling of {@code horus.bpm.rules.evaluation.max-input-scale}: a number input's scale must lie
     * within plus or minus this value. A host may lower it, never raise it, because an unbounded scale lets one
     * request burn CPU in rounding and fail the decision log write.
     */
    public static final int INPUT_MAX_SCALE = 34;

    /**
     * Default of {@code horus.bpm.rules.evaluation.reject-undeclared-inputs}: a misspelt input name ({@code txcount}
     * for {@code txCount}) is refused instead of silently matching a default row.
     */
    public static final boolean REJECT_UNDECLARED_INPUTS = true;

    /**
     * Default and ceiling of {@code horus.bpm.rules.evaluation.max-output-bytes}: the estimated JSON size of one
     * decision's outputs, checked while collected values accumulate. Lower only: outputs are held in the heap
     * that every tenant of the host shares.
     */
    public static final int OUTPUT_MAX_BYTES = 1_048_576;

    /**
     * Default and ceiling of {@code horus.bpm.rules.evaluation.max-string-length}: the longest string an input may
     * carry or string {@code +} may build, in UTF-16 code units. Lower only.
     */
    public static final int STRING_MAX_LENGTH = 65_536;

    /** Default and ceiling of {@code horus.bpm.rules.evaluation.max-rows}: the most rules a version may contain. Lower only. */
    public static final int RULES_MAX = 5_000;

    /**
     * Default and ceiling of {@code horus.bpm.rules.query.max-page-size}: items per page of a list read (ADR-0015).
     * A host may lower it, never raise it.
     */
    public static final int QUERY_MAX_PAGE_SIZE = 200;

    /** Items per page of a list read when the request names no {@code limit}. */
    public static final int QUERY_DEFAULT_PAGE_SIZE = 50;

    /** Longest period of a decision log search, {@code query.max-range} (ADR-0015); may be lowered only. */
    public static final Duration QUERY_MAX_RANGE = Duration.ofDays(31);

    /** Longest a decision log search may run, {@code query.statement-timeout}. */
    public static final Duration QUERY_STATEMENT_TIMEOUT = Duration.ofSeconds(2);

    /** Bounds of {@code query.statement-timeout}. */
    public static final Duration QUERY_STATEMENT_TIMEOUT_MIN = Duration.ofMillis(100);

    /** Bounds of {@code query.statement-timeout}. */
    public static final Duration QUERY_STATEMENT_TIMEOUT_MAX = Duration.ofSeconds(30);

    /** How far ahead monthly partitions of the decision log are created, {@code decision-log.partitions-ahead}. */
    public static final Duration PARTITIONS_AHEAD = Duration.ofDays(7);

    /** Largest {@code decision-log.partitions-ahead}: one year of months. */
    public static final Duration PARTITIONS_AHEAD_MAX = Duration.ofDays(366);

    /** How often partition maintenance runs after start-up: daily. */
    public static final Duration PARTITION_MAINTENANCE_INTERVAL = Duration.ofDays(1);

    /**
     * Default and ceiling of {@code horus.bpm.rules.lifecycle.reason-max-length}: longest reason of a reject or a
     * retire, in characters. A host may lower it, never raise it.
     */
    public static final int REASON_MAX_LENGTH = 2000;

    /**
     * Largest body of a lifecycle command ({@code :approve} comment, {@code :reject} reason), in bytes: room for a
     * reason of {@link #REASON_MAX_LENGTH} characters even when every character is JSON-escaped. A safety bound of
     * the REST layer, not a setting; a larger body is {@code 413 PAYLOAD_TOO_LARGE}, read no further than one byte
     * past the bound.
     */
    public static final int COMMAND_BODY_MAX_BYTES = 16_384;

    /**
     * Largest {@code :evaluate} body, in bytes, the same bound as an imported document. A safety bound of the REST
     * layer, not a setting; a larger body is {@code 413 PAYLOAD_TOO_LARGE}.
     */
    public static final int EVALUATE_BODY_MAX_BYTES = 1_048_576;

    /**
     * Default and ceiling of {@code simulation.max-inputs}: the most sample input sets one simulation accepts
     * (RLE-016). A simulation runs in the request, so the bound keeps its time and memory close to a batch
     * evaluation; its body is read with {@link #EVALUATE_BODY_MAX_BYTES}. May be lowered, never raised.
     */
    public static final int SIMULATION_MAX_SAMPLES = 1_000;

    /**
     * Default of {@code simulation.max-history-decisions}: the most logged decisions one comparison over a past period
     * reads (RLE-077, PD-124). About 100,000 evaluations, a few seconds of one thread. A larger period is refused,
     * never sampled, so a total is never partial.
     */
    public static final int SIMULATION_MAX_HISTORY_DECISIONS = 50_000;

    /**
     * Default of {@code approval.require-content-hash}: an approval or a rejection must name the content it reviewed
     * (RLE-084, #275). Required from the first release, because making a field required later breaks clients.
     */
    public static final boolean REQUIRE_CONTENT_HASH = true;

    /** Ceiling of {@code simulation.max-history-decisions}: it may be raised to this, never above. */
    public static final int SIMULATION_MAX_HISTORY_DECISIONS_CEILING = 200_000;

    /**
     * Default and ceiling of {@code evaluation.max-batch}: the most items one {@code :evaluate-batch} accepts
     * (RLE-068). May be lowered, never raised.
     */
    public static final int BATCH_MAX_ITEMS = 1_000;

    /**
     * Largest {@code :evaluate-batch} body, in bytes (4 MiB, RLE-068). A safety bound of the REST layer, not a
     * setting; a larger body is {@code 413 PAYLOAD_TOO_LARGE}, refused before it is read when the declared length
     * says so.
     */
    public static final int BATCH_BODY_MAX_BYTES = 4_194_304;

    /**
     * How long a replica keeps the cursor key before reading it again from {@code rle_engine_key}. Deleting the row
     * rotates the key; every replica uses the new key within this time, and one that meets a cursor it cannot
     * verify re-reads at once. A bound of the engine, not a setting.
     */
    public static final java.time.Duration CURSOR_KEY_REFRESH = java.time.Duration.ofMinutes(5);

    /** Default of {@code horus.bpm.rules.lifecycle.system-actor}: the actor recorded when a version is superseded. */
    public static final String SYSTEM_ACTOR = "system:supersession";

    /**
     * The namespace of the engine's own actors: {@code lifecycle.system-actor} must start with it, and no caller id
     * may, so a host user can never act or appear in the audit trail under an engine actor's name.
     */
    public static final String RESERVED_ACTOR_PREFIX = "system:";

    /** Longest {@code lifecycle.system-actor}, in characters, the bound of every recorded actor. */
    public static final int SYSTEM_ACTOR_MAX_LENGTH = 200;

    /**
     * Default of {@code horus.bpm.lifecycle.retire-approval} (PD-080): one {@code rules:approve} holder with a
     * reason retires a version. The other value, {@code four-eyes}, arrives in M3 (RLE-038).
     */
    public static final String RETIRE_APPROVAL = "single";

    /**
     * Default of {@code horus.bpm.lifecycle.retire-last-version} (PD-101): retiring the version in effect, when no other
     * would remain, needs {@code confirmNoVersionInEffect} on the request.
     */
    public static final String RETIRE_LAST_CONFIRM = "confirm";

    /** {@code horus.bpm.lifecycle.retire-last-version: refuse}: the last version in effect is never retired by hand. */
    public static final String RETIRE_LAST_REFUSE = "refuse";

    /** Default of {@code horus.bpm.rules.lifecycle.sweep-interval}: how often superseded versions are retired. */
    public static final java.time.Duration SWEEP_INTERVAL = java.time.Duration.ofMinutes(1);

    /** Reason recorded on a version retired because a newer one is in effect (ADR-0025 decision 2). */
    public static final String REASON_SUPERSEDED = "SUPERSEDED";

    /**
     * Default of {@code horus.bpm.rules.evaluation.pin-retired}: a pinned evaluation of a retired version is refused,
     * so a rule taken out of service cannot produce new decisions through the pin.
     */
    public static final boolean PIN_RETIRED = false;

    /** Longest accepted {@code X-Correlation-Id}; a longer or malformed one is replaced by a generated id. */
    public static final int CORRELATION_ID_MAX_LENGTH = 128;

    /** The host setting of the masking key, named in every message about a missing or invalid key (RLE-035). */
    public static final String MASKING_KEY_PROPERTY = "horus.bpm.rules.masking.hmac-key";

    /** Default of {@code horus.bpm.rules.decision-log.mask-default}: inputs that declare no {@code mask} are logged. */
    public static final boolean MASK_DEFAULT = false;

    /**
     * Largest evidence file one export may produce, in bytes (PD-116); a larger one is {@code 422 EVIDENCE_TOO_LARGE}
     * before anything is written. Bounds the memory one export holds and the size of its idempotency record.
     */
    public static final int EVIDENCE_MAX_BYTES = 8_388_608;

    /** Most history events one version may contribute to an evidence file; more is {@code EVIDENCE_TOO_LARGE}. */
    public static final int EVIDENCE_MAX_EVENTS_PER_VERSION = 10_000;

    private EngineDefaults() {
    }

    /** Default of {@code lifecycle.acknowledge-warnings} (PD-104): personal-data warnings need an acknowledgement. */
    public static final List<String> ACKNOWLEDGE_WARNINGS = List.of("UNMASKED_PERSONAL_DATA");

    /** Most codes {@code lifecycle.acknowledge-warnings} may list. */
    public static final int ACKNOWLEDGE_WARNINGS_MAX = 20;
}
