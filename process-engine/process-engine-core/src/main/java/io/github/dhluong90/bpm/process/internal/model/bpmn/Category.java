package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code category}, the values groups refer to.
 *
 * @param id id
 * @param name name
 * @param categoryValues categoryValues
 * @param info info
 */
public record Category(
        String id,
        String name,
        List<CategoryValue> categoryValues,
        ElementInfo info) implements RootElement {

    public Category {
        Objects.requireNonNull(id, "id must not be null");
        categoryValues = categoryValues == null ? List.of() : List.copyOf(categoryValues);
        info = info == null ? ElementInfo.empty() : info;
    }
}
