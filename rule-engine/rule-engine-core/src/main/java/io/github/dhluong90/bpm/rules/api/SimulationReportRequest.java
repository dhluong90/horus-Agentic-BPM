package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a simulation report runs over (PD-115, RLE-199): sample inputs, or the logged decisions of a past period.
 *
 * @param source {@link Source#SAMPLES} or {@link Source#REPLAY}
 * @param samples for {@code SAMPLES}, 1 to {@code simulation.max-inputs} input sets; empty for {@code REPLAY}
 * @param from for {@code REPLAY}, the first instant of the period, inclusive
 * @param to for {@code REPLAY}, the end of the period, exclusive; at most {@code query.max-range} after {@code from}
 * @param limit for {@code REPLAY}, the most decisions to replay, 1 to {@code simulation.max-inputs}; null for that
 *     maximum
 * @param idempotencyKey optional; a repeat with the same key and the same request returns the first report
 */
public record SimulationReportRequest(Source source, List<DecisionInput> samples, Instant from, Instant to,
        Integer limit, String idempotencyKey) {

    /** What the report runs over. */
    public enum Source {
        /** Sample inputs the caller sends. */
        SAMPLES,
        /** Logged decisions of a past period. */
        REPLAY
    }

    public SimulationReportRequest {
        Objects.requireNonNull(source, "source must not be null");
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    /**
     * A report over sample inputs.
     *
     * @param samples the input sets
     * @return the request
     */
    public static SimulationReportRequest samples(List<DecisionInput> samples) {
        return new SimulationReportRequest(Source.SAMPLES, samples, null, null, null, null);
    }

    /**
     * A report over the logged decisions of a period.
     *
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @param limit the most decisions, or null for the host's maximum
     * @return the request
     */
    public static SimulationReportRequest replay(Instant from, Instant to, Integer limit) {
        return new SimulationReportRequest(Source.REPLAY, List.of(), from, to, limit, null);
    }

    /**
     * Returns a copy with an idempotency key.
     *
     * @param key the key
     * @return the request
     */
    public SimulationReportRequest withIdempotencyKey(String key) {
        return new SimulationReportRequest(source, samples, from, to, limit, key);
    }
}
