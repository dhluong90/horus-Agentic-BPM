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
    /** Parsed conditions cached per replica. */
    public static final int CONDITION_CACHE_ENTRIES = 1_000;
    /** Longest idempotency key. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    /** Longest business key. */
    public static final int MAX_BUSINESS_KEY_LENGTH = 200;

    private EngineDefaults() {
    }
}
