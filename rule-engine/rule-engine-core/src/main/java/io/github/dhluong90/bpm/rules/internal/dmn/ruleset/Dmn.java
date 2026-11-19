package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** DMN namespaces and the type mapping shared by the reader and the writer. */
final class Dmn {

    /** DMN 1.5 model namespace, written on export. */
    static final String MODEL_15 = "https://www.omg.org/spec/DMN/20230324/MODEL/";

    /** Namespace of the HORUS extension attributes ({@code horus:type}, {@code horus:mask}, ...). */
    static final String HORUS_NAMESPACE = "urn:horus-bpm:dmn:extensions:1";

    /** Model namespace to FEEL namespace, DMN 1.2 to 1.5. */
    private static final Map<String, String> FEEL_BY_MODEL = Map.of(
            "http://www.omg.org/spec/DMN/20180521/MODEL/", "http://www.omg.org/spec/DMN/20180521/FEEL/",
            "https://www.omg.org/spec/DMN/20191111/MODEL/", "https://www.omg.org/spec/DMN/20191111/FEEL/",
            "https://www.omg.org/spec/DMN/20211108/MODEL/", "https://www.omg.org/spec/DMN/20211108/FEEL/",
            MODEL_15, "https://www.omg.org/spec/DMN/20230324/FEEL/");

    /** Model namespaces read on import. */
    static final java.util.Set<String> MODEL_NAMESPACES = FEEL_BY_MODEL.keySet();

    /** FEEL namespaces of every supported DMN version. */
    static final List<String> FEEL_NAMESPACES = List.copyOf(FEEL_BY_MODEL.values());

    private Dmn() {
    }

    static String feelNamespaceOf(String modelNamespace) {
        return FEEL_BY_MODEL.get(modelNamespace);
    }

    /**
     * Returns the FEEL {@code typeRef} written for a v1 type; {@code Any} when FEEL has no name for it, in which
     * case {@code horus:type} carries the v1 type.
     *
     * @param v1Type the v1 type text
     * @return the type reference, or empty for an unknown type
     */
    static Optional<String> typeRefOf(String v1Type) {
        if (v1Type.startsWith("list<")) {
            return Optional.of("Any");
        }
        return Optional.ofNullable(switch (v1Type) {
            case "string" -> "string";
            case "integer", "decimal" -> "number";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "datetime" -> "date and time";
            case "money" -> "Any";
            default -> null;
        });
    }

    /**
     * Tells whether the reader maps {@code typeRef} back to the v1 type without {@code horus:type}.
     *
     * @param v1Type the v1 type text
     * @return true when {@code horus:type} is not needed
     */
    static boolean typeRefIsEnough(String v1Type) {
        return switch (v1Type) {
            case "string", "decimal", "boolean", "date", "datetime" -> true;
            default -> false;
        };
    }
}
