package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:guardrail}: the guardrail rule set and what happens when it refuses.
 *
 * @param ruleSet ruleSet
 * @param onFail onFail
 */
public record GuardrailSpec(
        String ruleSet,
        String onFail) implements HorusExtension {
}
