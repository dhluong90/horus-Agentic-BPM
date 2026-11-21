package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the engine measures, without a metrics library (DESIGN section 4.8, TR-OBS-02): one record per evaluation call
 * and one per retired version. A {@link Recorder}, which the Spring Boot starter binds to the host's meter registry,
 * receives each record; with none bound, nothing is recorded. Every value handed to a recorder comes from a small
 * fixed set (an {@link Outcome}, an engine error code, a {@link RetireReason}), never a tenant, rule set, caller or
 * input, so a recorder can use them as metric tags. Thread-safe.
 */
public final class EngineMeters {

    /** The {@code code} of a failure whose code is not an engine error code, so a tag value set stays bounded. */
    public static final String OTHER_CODE = "OTHER";

    /** The {@code code} of an evaluation that failed with an unexpected exception. */
    public static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";

    /**
     * Every error code the engine reports, as listed by the {@code Problem.code} enum of the REST contract. A tag value
     * is one of these or {@link #OTHER_CODE}, so a host exception that merely looks like a code cannot add a series.
     */
    static final Set<String> KNOWN_CODES = Set.of(
            "INPUT_INVALID", "QUERY_INVALID", "UNAUTHENTICATED", "PERMISSION_DENIED", "TENANT_NOT_ALLOWED",
            "SELF_APPROVAL_FORBIDDEN", "RULE_SET_NOT_FOUND", "VERSION_NOT_FOUND", "NO_EFFECTIVE_VERSION",
            "DECISION_NOT_FOUND", "VERSION_CONFLICT", "INVALID_STATE", "IDEMPOTENCY_IN_FLIGHT", "RULE_SET_EXISTS",
            "VALIDATION_FAILED", "IDEMPOTENCY_KEY_REUSE_MISMATCH", "EVALUATION_ERROR", "UNIQUE_HIT_VIOLATION",
            "PAYLOAD_TOO_LARGE", "PRECONDITION_REQUIRED", INTERNAL_ERROR_CODE, "SCHEMA_NOT_READY",
            "DRAFT_AUTHORING_DISABLED", "RATE_LIMITED", "CLOCK_NOT_ALLOWED", "REPLAY_NOT_POSSIBLE",
            "LAST_VERSION_IN_EFFECT", "WARNINGS_NOT_ACKNOWLEDGED", "AUTHOR_UNKNOWN",
            "BATCH_TOO_LARGE", "DMN_UNSUPPORTED_ELEMENT", "CMMN_UNSUPPORTED", "EXPRESSION_LANGUAGE_UNSUPPORTED",
            "EVIDENCE_TOO_LARGE", "READINGS_TOO_LARGE");

    /** How one evaluation call ended. */
    public enum Outcome {
        /** At least one rule matched. */
        MATCHED,
        /** No rule matched; the outputs are null. */
        NO_MATCH,
        /** The decision was logged with outcome {@code EVALUATION_ERROR} or a hit-policy violation. */
        FAILED,
        /** The call was refused before a decision was logged: not found, not permitted, invalid input. */
        REFUSED;

        /** @return the tag value, for example {@code no_match} */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why a version was retired. */
    public enum RetireReason {
        /** A newer version came into force (lifecycle sweep or approval). */
        SUPERSEDED,
        /** A user retired it. */
        MANUAL
    }

    /** Receives the engine's records; implementations must be thread-safe and must not throw. */
    public interface Recorder {

        /**
         * One evaluation call.
         *
         * @param outcome how it ended
         * @param code the engine error code of a failed or refused call, else null
         * @param nanos the time the call took, including the decision log write
         */
        void evaluation(Outcome outcome, String code, long nanos);

        /**
         * Versions retired by one committed transaction.
         *
         * @param reason why
         * @param count how many, at least 1
         */
        void retired(RetireReason reason, int count);
    }

    private final List<Recorder> recorders = new CopyOnWriteArrayList<>();

    /**
     * Adds a recorder; a host with several meter registries binds one per registry.
     *
     * @param recorder the recorder
     */
    public void bind(Recorder recorder) {
        recorders.add(Objects.requireNonNull(recorder, "recorder must not be null"));
    }

    /** Records an evaluation call that returned a result. */
    void evaluated(DecisionResult result, long nanos) {
        if (recorders.isEmpty()) {
            return;
        }
        switch (result.outcome()) {
            case DecisionResult.Matched ignored -> record(Outcome.MATCHED, null, nanos);
            case DecisionResult.NoMatch ignored -> record(Outcome.NO_MATCH, null, nanos);
            case DecisionResult.Failed failed -> record(Outcome.FAILED, code(failed.code()), nanos);
        }
    }

    /** Records an evaluation call that threw. */
    void refused(RuntimeException failure, long nanos) {
        if (recorders.isEmpty()) {
            return;
        }
        if (failure instanceof HorusBpmException refusal) {
            record(Outcome.REFUSED, code(refusal.code()), nanos);
        } else {
            record(Outcome.FAILED, INTERNAL_ERROR_CODE, nanos);
        }
    }

    /** Records retired versions; call it after commit. */
    void retired(RetireReason reason, int count) {
        if (count <= 0) {
            return;
        }
        for (Recorder recorder : recorders) {
            recorder.retired(reason, count);
        }
    }

    private void record(Outcome outcome, String code, long nanos) {
        for (Recorder recorder : recorders) {
            recorder.evaluation(outcome, code, nanos);
        }
    }

    /**
     * Keeps a code only when it is one of the engine's error codes, so a tag never carries free text or an unbounded
     * set of values.
     *
     * @param code the code
     * @return the code, or {@link #OTHER_CODE}
     */
    static String code(String code) {
        return code != null && KNOWN_CODES.contains(code) ? code : OTHER_CODE;
    }
}
