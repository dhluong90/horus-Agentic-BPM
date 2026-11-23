package io.github.dhluong90.bpm.rules.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A starting point for a new rule set: a rule set document without a name, with a label and a description in English
 * and Vietnamese (PD-112, RLE-197). An author picks one in the cockpit, which fills the grid from it and saves the
 * result as a normal draft through {@code POST /rule-sets}; the template itself is never stored as a rule set.
 *
 * @param id the template id, kebab-case, unique among the templates the host offers
 * @param labels the name by language code; always holds {@code en} and {@code vi}
 * @param descriptions the description by language code; always holds {@code en} and {@code vi}
 * @param source {@code BUILT_IN} for a template shipped with the engine, {@code HOST} for one in the host's folder
 * @param hitPolicy {@code first}, {@code unique} or {@code collect}
 * @param inputs the inputs with their types and, where the author picks from a list, the allowed values
 * @param outputs the outputs with their types
 * @param rows the first rows, in table order
 */
public record RuleSetTemplate(String id, Map<String, String> labels, Map<String, String> descriptions, String source,
        String hitPolicy, List<Input> inputs, List<Output> outputs, List<Row> rows) {

    /** Marks a template shipped with the engine. */
    public static final String BUILT_IN = "BUILT_IN";

    /** Marks a template read from the host's template folder. */
    public static final String HOST = "HOST";

    public RuleSetTemplate {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(hitPolicy, "hitPolicy must not be null");
        labels = Map.copyOf(labels);
        descriptions = Map.copyOf(descriptions);
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        rows = List.copyOf(rows);
    }

    /**
     * One input of the template.
     *
     * @param name the input name
     * @param type the value type, such as {@code string}, {@code integer} or {@code money}
     * @param allowed the values the author picks from, empty when any value of the type is allowed
     */
    public record Input(String name, String type, List<String> allowed) {

        public Input {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
            allowed = List.copyOf(allowed);
        }
    }

    /**
     * One output of the template.
     *
     * @param name the output name
     * @param type the value type
     */
    public record Output(String name, String type) {

        public Output {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    /**
     * One first row of the template, in the cell syntax of a rule set document.
     *
     * @param id the rule id
     * @param when the condition cell of each input the row tests, such as {@code "== 'GOLD'"}; an empty map is a
     *     default row
     * @param then the value cell of each output, a text expression such as {@code "50000 VND"} or a boolean
     */
    public record Row(String id, Map<String, String> when, Map<String, Object> then) {

        public Row {
            Objects.requireNonNull(id, "id must not be null");
            when = Map.copyOf(when);
            then = Map.copyOf(then);
        }
    }
}
