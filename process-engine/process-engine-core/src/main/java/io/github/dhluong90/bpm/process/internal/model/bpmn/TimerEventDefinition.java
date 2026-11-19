package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code timerEventDefinition}.
 *
 * @param id id
 * @param timeDate timeDate
 * @param timeDuration timeDuration
 * @param timeCycle timeCycle
 * @param info info
 */
public record TimerEventDefinition(
        String id,
        Expression timeDate,
        Expression timeDuration,
        Expression timeCycle,
        ElementInfo info) implements EventDefinition {

    public TimerEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
