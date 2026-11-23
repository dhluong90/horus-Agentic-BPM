package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;

/** The one place numeric and duration defaults of the Process Engine are declared (ADR-0024 item 4). */
public final class EngineDefaults {

    /** {@code definitions.max-nodes}; may be lowered, never raised. */
    public static final int MAX_NODES = 500;
    /** {@code definitions.max-bytes}; may be lowered, never raised. */
    public static final int MAX_BYTES = 1_048_576;
    /** Deepest XML nesting of a BPMN document. */
    public static final int MAX_XML_DEPTH = 32;
    /** Most XML elements in a BPMN document. */
    public static final int MAX_XML_ELEMENTS = 5_000;
    /** {@code idempotency.retention}. */
    public static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    /**
     * {@code handlers.slow-call-warning}: a local handler or decision that takes longer is logged, because it holds
     * the instance row lock and a pool connection for its whole duration (a sign of a network call in a handler).
     */
    public static final Duration SLOW_CALL_WARNING = Duration.ofMillis(200);
    /** Parsed conditions cached per replica. */
    public static final int CONDITION_CACHE_ENTRIES = 1_000;
    /** Longest idempotency key. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    /** Largest instance variables, start variables or task result, in characters of JSON. */
    public static final int MAX_VARIABLES_CHARS = 262_144;
    /** Largest JSON request body of the REST endpoints, in bytes. */
    public static final int MAX_JSON_BODY_BYTES = 262_144;
    /** Most kernel commands one start or completion may apply (local steps included); a loop without a wait state stops here. */
    public static final int MAX_COMMANDS_PER_CALL = 1_000;
    /** {@code query.max-page-size}; may be lowered, never raised. */
    public static final int MAX_PAGE_SIZE = 200;

    /** Default and largest {@code query.max-roles}: the most role and group queues one task list reads (#408). */
    public static final int MAX_ROLES = 100;
    /** Largest value {@code query.max-roles} may be raised to. */
    public static final int MAX_ROLES_LIMIT = 1_000;

    /** Default {@code query.due-soon-window}: how long before its due time a task item reads {@code DUE_SOON} (PD-111). */
    public static final Duration DUE_SOON_WINDOW = Duration.ofHours(1);

    /** Page size of a list when the request names none (the maximum when that is lower). */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** {@code query.max-range}: longest start-time range of a list (ADR-0015); may be lowered, never raised. */
    public static final Duration MAX_RANGE = Duration.ofDays(31);

    /** {@code lifecycle.reason-max-length}: longest cancellation reason (PD-103); may be lowered, never raised. */
    public static final int MAX_REASON_LENGTH = 2_000;

    /** {@code calls.max-depth}: most calls one above another (#92); may be lowered, never raised. */
    public static final int MAX_CALL_DEPTH = 5;

    /** Highest {@code multi-instance.max}: the most repetitions one looped activity may be set to allow (#496). */
    public static final int MAX_LOOP_INSTANCES = 1_000;

    /** Longest business key. */
    public static final int MAX_BUSINESS_KEY_LENGTH = 200;

    private EngineDefaults() {
    }
}
