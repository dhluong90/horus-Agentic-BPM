package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a version would decide for sample inputs, beside the version effective at the clock (RLE-016). Nothing of it
 * is logged: a simulation writes no decision row, idempotency record or event.
 *
 * @param ruleSet the rule set name
 * @param version the simulated version, normally a draft or a version pending approval
 * @param status the simulated version's status
 * @param baselineVersion the version effective at {@code evaluatedAt}, or null when none is
 * @param evaluatedAt the clock both sides were evaluated at
 * @param samples one result per sample, in the order sent
 */
public record SimulationResult(String ruleSet, int version, VersionStatus status, Integer baselineVersion,
        Instant evaluatedAt, List<Sample> samples) {

    public SimulationResult {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        samples = List.copyOf(samples);
    }

    /**
     * Counts the samples whose outcome changed.
     *
     * @return how many samples have {@link Sample#changed()} set
     */
    public int changedCount() {
        return (int) samples.stream().filter(Sample::changed).count();
    }

    /**
     * The two outcomes of one sample.
     *
     * @param index the sample's position in the request, from 0
     * @param simulated what the simulated version decides
     * @param baseline what the effective version decides, or null when no version is effective
     * @param changed true when the outcome kind, an output value or the failure code differs, or when there is no
     *     baseline; a different matched rule giving the same outputs is not a change
     */
    public record Sample(int index, DecisionResult.Outcome simulated, DecisionResult.Outcome baseline, boolean changed) {

        public Sample {
            Objects.requireNonNull(simulated, "simulated must not be null");
        }
    }
}
