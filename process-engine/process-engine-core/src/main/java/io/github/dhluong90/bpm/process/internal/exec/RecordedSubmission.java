package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What history keeps of a form submission (#663): the declared fields with every masked field replaced by the marker
 * {@code {"masked": true}}, and the names, never the values, of the fields the form does not declare.
 *
 * @param values the stored values
 * @param unrecordedFields paths of submitted fields that are not in the form schema and so were not stored
 */
public record RecordedSubmission(Map<String, Object> values, List<String> unrecordedFields) {

    /** The marker stored in place of a masked value; a real value can never be this object by accident of the form. */
    public static final Map<String, Object> MASKED = Map.of("masked", true);

    public RecordedSubmission {
        Objects.requireNonNull(values, "values must not be null");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        unrecordedFields = unrecordedFields == null ? List.of() : List.copyOf(unrecordedFields);
    }
}
