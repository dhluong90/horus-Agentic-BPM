package io.github.dhluong90.bpm.process.internal.exec;

import java.util.List;
import java.util.Objects;

/**
 * Result of one kernel step: the new state, the effects for the shell to apply, and the history to record. The
 * shell applies all three in one transaction, except {@link Effect.InvokeService} in {@link ExecutionMode#ASYNC},
 * which runs outside any transaction (ADR-0008).
 *
 * @param state the new state
 * @param effects effects in the order they were produced
 * @param history history entries in order
 */
public record Transition(InstanceState state, List<Effect> effects, List<HistoryEntry> history) {

    public Transition {
        Objects.requireNonNull(state, "state must not be null");
        effects = effects == null ? List.of() : List.copyOf(effects);
        history = history == null ? List.of() : List.copyOf(history);
    }
}
