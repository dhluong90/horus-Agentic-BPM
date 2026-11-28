package io.github.dhluong90.bpm.process.spi;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which instance variables are encrypted (PRE-395): names that apply to every definition, and names per definition
 * key. A definition entry {@value #ALL} encrypts every variable of that definition. Matching is on the top-level
 * variable name, exactly.
 *
 * @param variables names encrypted in every definition
 * @param definitions per definition key, the names encrypted there, or {@value #ALL}
 */
public record VariableEncryptionRules(Set<String> variables, Map<String, Set<String>> definitions) {

    /** Encrypts every variable of a definition. */
    public static final String ALL = "*";

    /** Encrypts nothing. */
    public static final VariableEncryptionRules NONE = new VariableEncryptionRules(Set.of(), Map.of());

    public VariableEncryptionRules {
        variables = variables == null ? Set.of() : Set.copyOf(variables);
        Map<String, Set<String>> copy = new HashMap<>();
        if (definitions != null) {
            definitions.forEach((key, names) -> copy.put(key, names == null ? Set.of() : Set.copyOf(names)));
        }
        definitions = Map.copyOf(copy);
    }

    /**
     * Tells whether a variable of a definition is encrypted.
     *
     * @param definitionKey the process key
     * @param name the top-level variable name
     * @return true when it is protected
     */
    public boolean encrypts(String definitionKey, String name) {
        if (variables.contains(name)) {
            return true;
        }
        Set<String> own = definitions.get(definitionKey);
        return own != null && (own.contains(ALL) || own.contains(name));
    }

    /** @return true when no variable of any definition is protected */
    public boolean isEmpty() {
        return variables.isEmpty() && definitions.values().stream().allMatch(Set::isEmpty);
    }
}
