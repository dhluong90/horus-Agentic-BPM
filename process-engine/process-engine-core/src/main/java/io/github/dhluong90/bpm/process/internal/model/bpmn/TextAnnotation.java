package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code textAnnotation}.
 *
 * @param id id
 * @param text text
 * @param textFormat textFormat
 * @param info info
 */
public record TextAnnotation(
        String id,
        String text,
        String textFormat,
        ElementInfo info) implements Artifact {

    public TextAnnotation {
        info = info == null ? ElementInfo.empty() : info;
    }
}
