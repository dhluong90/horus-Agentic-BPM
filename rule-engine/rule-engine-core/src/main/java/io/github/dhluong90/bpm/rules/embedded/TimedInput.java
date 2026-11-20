package io.github.dhluong90.bpm.rules.embedded;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import java.time.Instant;
import java.util.Objects;

/**
 * One case of a simulation over recorded work: the inputs and the instant the decision reasons about, which feeds
 * {@code today()} and {@code now()}. Used by {@link TenantScopedRuleEngine#simulate}.
 *
 * @param input the inputs
 * @param evaluationTime the instant the case was originally decided at
 */
public record TimedInput(DecisionInput input, Instant evaluationTime) {

    public TimedInput {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(evaluationTime, "evaluationTime must not be null");
    }
}
