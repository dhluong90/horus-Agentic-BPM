package io.github.dhluong90.bpm.process.internal.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The guardrail contract of an agent step (PRE-016, DESIGN section 4.1 step 4), checked when a definition is imported,
 * when its approval is requested and when it is approved, so a guardrail that cannot decide is found before a customer
 * reaches it. The rule set named by {@code horus:guardrail decision} must:
 * <ul>
 *   <li>exist in the tenant with a version in effect;</li>
 *   <li>use hit policy {@code first} or {@code unique}, so one row decides;</li>
 *   <li>declare the output {@code allow: boolean}, and may declare {@code reasons} as {@code string} or
 *       {@code list<string>}; other outputs are allowed and never read;</li>
 *   <li>declare only inputs the answer can carry: each is a property of the step's output schema with a compatible
 *       type; and, when the Rule Engine refuses undeclared inputs, every property the answer may carry is declared.</li>
 * </ul>
 * A pure function: no I/O, no clock. Messages name the rule set, its fields and the failed check, never a schema value.
 */
public final class GuardrailContract {

    /** The output that decides. */
    public static final String ALLOW = "allow";
    /** The optional output with the reasons of a refusal. */
    public static final String REASONS = "reasons";

    private static final Set<String> HIT_POLICIES = Set.of("FIRST", "UNIQUE");
    private static final Set<String> REASON_TYPES = Set.of("string", "list<string>");

    private GuardrailContract() {
    }

    /**
     * Checks a rule set's signature against the contract and the step's output schema.
     *
     * @param decision the rule set name the step names
     * @param signature what the decision engine says about its effective version
     * @param outputSchema the step's output schema as a JSON object
     * @return each failed check as a sentence; empty when the contract holds
     */
    public static List<String> check(String decision, DecisionSignature signature, Map<String, Object> outputSchema) {
        List<String> failures = new ArrayList<>();
        switch (signature) {
            case DecisionSignature.Unavailable u -> failures.add(unavailable(decision, u));
            case DecisionSignature.Found found -> checkFound(found, outputSchema, failures);
        }
        return List.copyOf(failures);
    }

    private static String unavailable(String decision, DecisionSignature.Unavailable u) {
        return switch (u.code()) {
            case "RULE_SET_NOT_FOUND" -> "guardrail rule set " + decision + " does not exist in this tenant";
            case "NO_EFFECTIVE_VERSION" -> "guardrail rule set " + decision + " has no published version in effect";
            default -> "guardrail rule set " + decision + " cannot be checked (" + u.code() + ")";
        };
    }

    private static void checkFound(DecisionSignature.Found found, Map<String, Object> schema, List<String> failures) {
        String name = found.ruleSet() + " v" + found.version();
        String hitPolicy = found.hitPolicy().toUpperCase(Locale.ROOT);
        if (!HIT_POLICIES.contains(hitPolicy)) {
            failures.add("guardrail rule set " + name + " has hit policy " + hitPolicy.toLowerCase(Locale.ROOT)
                    + "; a guardrail needs first or unique");
        }
        String allow = found.outputs().get(ALLOW);
        if (allow == null) {
            failures.add("guardrail rule set " + name + " declares no output allow: boolean (outputs "
                    + signatureText(found.outputs()) + ")");
        } else if (!"boolean".equals(allow)) {
            failures.add("guardrail rule set " + name + " declares allow as " + allow + "; it must be boolean");
        }
        String reasons = found.outputs().get(REASONS);
        if (reasons != null && !REASON_TYPES.contains(reasons)) {
            failures.add("guardrail rule set " + name + " declares reasons as " + reasons
                    + "; it must be string or list<string>");
        }
        Map<String, Object> properties = properties(schema);
        for (Map.Entry<String, String> input : found.inputs().entrySet()) {
            Object field = properties.get(input.getKey());
            if (!(field instanceof Map<?, ?> fieldSchema)) {
                failures.add("guardrail rule set " + name + " reads input " + input.getKey()
                        + ", which horus:outputSchema does not declare");
            } else if (!compatible(fieldSchema, input.getValue())) {
                failures.add("guardrail rule set " + name + " reads input " + input.getKey() + " as "
                        + input.getValue() + ", which horus:outputSchema declares as " + typeText(fieldSchema));
            }
        }
        if (found.rejectsUndeclaredInputs()) {
            for (String property : properties.keySet()) {
                if (!found.inputs().containsKey(property)) {
                    failures.add("guardrail rule set " + name + " does not declare input " + property
                            + ", and this Rule Engine refuses undeclared inputs");
                }
            }
            if (!Boolean.FALSE.equals(schema == null ? null : schema.get("additionalProperties"))) {
                failures.add("horus:outputSchema allows undeclared fields (additionalProperties), which guardrail "
                        + "rule set " + name + " would refuse; set additionalProperties to false");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return schema != null && schema.get("properties") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Whether every value the field schema admits (besides null) is a value of the rule set type. */
    private static boolean compatible(Map<?, ?> field, String ruleType) {
        boolean list = ruleType.startsWith("list<") && ruleType.endsWith(">");
        String scalar = list ? ruleType.substring(5, ruleType.length() - 1) : ruleType;
        List<String> types = types(field.get("type"));
        if (types.isEmpty()) {
            return true;
        }
        for (String type : types) {
            boolean ok = list
                    ? "array".equals(type) && (!(field.get("items") instanceof Map<?, ?> items)
                            || compatible(items, scalar))
                    : scalarCompatible(type, scalar);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean scalarCompatible(String schemaType, String ruleType) {
        return switch (schemaType) {
            case "string" -> Set.of("string", "date", "datetime").contains(ruleType);
            case "integer" -> Set.of("integer", "decimal").contains(ruleType);
            case "number" -> "decimal".equals(ruleType);
            case "boolean" -> "boolean".equals(ruleType);
            case "object" -> "money".equals(ruleType);
            default -> false;
        };
    }

    /** The schema's type names other than {@code null}; empty when it names none. */
    private static List<String> types(Object type) {
        List<String> names = new ArrayList<>();
        if (type instanceof String s) {
            names.add(s);
        } else if (type instanceof Collection<?> c) {
            c.forEach(n -> names.add(String.valueOf(n)));
        }
        names.removeIf("null"::equals);
        return names;
    }

    private static String typeText(Map<?, ?> field) {
        List<String> types = types(field.get("type"));
        return types.isEmpty() ? "any type" : String.join(" or ", types);
    }

    private static String signatureText(Map<String, String> fields) {
        if (fields.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        fields.forEach((k, v) -> out.append(out.isEmpty() ? "" : ", ").append(k).append(": ").append(v));
        return out.toString();
    }
}
