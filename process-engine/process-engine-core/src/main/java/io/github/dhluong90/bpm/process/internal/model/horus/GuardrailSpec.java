package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code <horus:guardrail>} of an agent step: the guardrail rule set and what happens when it refuses an answer
 * (#52, DESIGN section 4.1). A review is always four-eyes; the model has no switch for it.
 *
 * @param decision the guardrail rule set, evaluated on the agent's answer
 * @param onViolation {@code route-to-human} or {@code fail}
 * @param reviewer {@code role:}, {@code user:} or {@code group:} and a name; null with {@code fail}
 */
public record GuardrailSpec(
        String decision,
        String onViolation,
        String reviewer) implements HorusExtension {

    /** The route that sends a refused answer to a reviewer's four-eyes task. */
    public static final String ROUTE_TO_HUMAN = "route-to-human";
    /** The route that fails the step's job. */
    public static final String FAIL = "fail";
}
