package io.github.dhluong90.bpm.process.bpmn.subset;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Shape check of {@code <horus:fieldTitles>} (#386): {@code {"inputs": {field: {language: title}}, "outputs": {...}}}.
 * The titles are labels for the review screen; the language list is open, so a host adds a language without a code
 * change. A message names the rule, never a title or a field name.
 */
final class FieldTitles {

    static final int MAX_FIELDS = 100;
    static final int MAX_LANGUAGES = 10;
    static final int MAX_TITLE = 120;
    private static final Pattern FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,99}$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");

    private FieldTitles() {
    }

    /**
     * @param root the parsed element text
     * @return why it is not a valid set of field titles, or null when it is
     */
    static String check(JsonNode root) {
        if (root == null || !root.isObject()) {
            return "horus:fieldTitles must be a JSON object with inputs and outputs";
        }
        Iterator<Map.Entry<String, JsonNode>> sides = root.properties().iterator();
        while (sides.hasNext()) {
            Map.Entry<String, JsonNode> side = sides.next();
            if (!side.getKey().equals("inputs") && !side.getKey().equals("outputs")) {
                return "horus:fieldTitles has only inputs and outputs";
            }
            String problem = side(side.getValue());
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String side(JsonNode fields) {
        if (!fields.isObject()) {
            return "horus:fieldTitles inputs and outputs are objects of field names";
        }
        if (fields.size() > MAX_FIELDS) {
            return "horus:fieldTitles names at most " + MAX_FIELDS + " fields a side";
        }
        for (Map.Entry<String, JsonNode> field : fields.properties()) {
            if (!FIELD.matcher(field.getKey()).matches()) {
                return "a horus:fieldTitles field name is 1 to 100 letters, digits or _ . -";
            }
            JsonNode languages = field.getValue();
            if (!languages.isObject() || languages.isEmpty() || languages.size() > MAX_LANGUAGES) {
                return "each horus:fieldTitles field maps 1 to " + MAX_LANGUAGES + " languages to titles";
            }
            for (Map.Entry<String, JsonNode> title : languages.properties()) {
                if (!LANGUAGE.matcher(title.getKey()).matches()) {
                    return "a horus:fieldTitles language is a tag such as en or vi";
                }
                JsonNode text = title.getValue();
                if (!text.isString() || text.stringValue().isBlank() || text.stringValue().length() > MAX_TITLE
                        || text.stringValue().chars().anyMatch(Character::isISOControl)) {
                    return "a horus:fieldTitles title is 1 to " + MAX_TITLE + " characters of plain text";
                }
            }
        }
        return null;
    }
}
