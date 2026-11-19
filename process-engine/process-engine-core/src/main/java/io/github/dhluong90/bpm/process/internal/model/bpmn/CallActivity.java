package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code callActivity}.
 *
 * @param id id
 * @param name name
 * @param incoming incoming
 * @param outgoing outgoing
 * @param activity activity
 * @param calledElement calledElement
 * @param info info
 */
public record CallActivity(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        ActivityAttributes activity,
        String calledElement,
        ElementInfo info) implements Activity {

    public CallActivity {
        Objects.requireNonNull(id, "id must not be null");
        incoming = incoming == null ? List.of() : List.copyOf(incoming);
        outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
        activity = activity == null ? ActivityAttributes.defaults() : activity;
        info = info == null ? ElementInfo.empty() : info;
    }
}
