package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code linkEventDefinition}.
 *
 * @param id id
 * @param name name
 * @param sources sources
 * @param target target
 * @param info info
 */
public record LinkEventDefinition(
        String id,
        String name,
        List<String> sources,
        String target,
        ElementInfo info) implements EventDefinition {

    public LinkEventDefinition {
        sources = sources == null ? List.of() : List.copyOf(sources);
        info = info == null ? ElementInfo.empty() : info;
    }
}
