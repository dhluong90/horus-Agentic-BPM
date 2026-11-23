package io.github.dhluong90.bpm.process.api;

import java.util.Map;
import java.util.Objects;

/**
 * The form a user task is completed through, resolved for one task (PRE-031, {@code task-form.v1.schema.json}):
 * either a JSON Schema for the result with display hints and prefilled data, or a link to the host's own screen.
 *
 * @param taskId the task
 * @param lockVersion the task's lock version, sent back as {@code If-Match} on completion
 * @param schema JSON Schema of the result, or null for a link
 * @param hints display hints ({@code order}, {@code widgets}, {@code labels}, {@code readOnly}); empty for a link
 * @param data prefilled values from the instance variables the schema declares; a field the schema marks
 *     {@code "mask": true} carries the placeholder {@code ***}, never its value; empty for a link
 * @param link the resolved link with {@code {taskId}} and {@code {instanceId}} replaced, or null for a form
 */
public record TaskForm(
        String taskId,
        long lockVersion,
        Map<String, Object> schema,
        Map<String, Object> hints,
        Map<String, Object> data,
        String link) {

    /** Checks that exactly one of a schema and a link is present. */
    public TaskForm {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if ((schema == null) == (link == null)) {
            throw new IllegalArgumentException("A task form is either a schema or a link");
        }
        hints = hints == null ? Map.of() : copy(hints);
        data = data == null ? Map.of() : copy(data);
    }

    private static Map<String, Object> copy(Map<String, Object> map) {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(map));
    }

    /** @return true when the task is completed in the host's own screen */
    public boolean isLink() {
        return link != null;
    }
}
