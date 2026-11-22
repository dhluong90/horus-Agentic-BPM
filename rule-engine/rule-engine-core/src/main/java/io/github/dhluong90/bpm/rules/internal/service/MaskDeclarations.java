package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.PersonalDataNames;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionDiff.Masking;
import io.github.dhluong90.bpm.rules.api.VersionWarning;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The guards against unmasked personal data (PD-099), read from the {@code inputs} of a canonical document: which
 * inputs declare no {@code mask}, which of those look like personal data, and each input's masking status. Pure
 * functions: no I/O and no clock.
 */
final class MaskDeclarations {

    private MaskDeclarations() {
    }

    /**
     * Warns about every undeclared input whose name looks like personal data (RLE-049).
     *
     * @param canonicalJson the canonical document
     * @param names the tenant's personal-data names
     * @param maskByDefault the tenant's {@code decision-log.mask-default}
     * @return the warnings, in input order
     */
    static List<VersionWarning> warnings(String canonicalJson, PersonalDataNames names, boolean maskByDefault) {
        List<VersionWarning> warnings = new ArrayList<>();
        for (JsonNode input : inputs(canonicalJson)) {
            if (declared(input)) {
                continue;
            }
            String name = name(input);
            names.match(name).ifPresent(matched ->
                    warnings.add(VersionWarning.unmaskedPersonalData(name, matched, maskByDefault)));
        }
        return warnings;
    }

    /**
     * Lists every input that declares no {@code mask} (PD-146, #438), in input order. Only the declaration counts:
     * {@code mask: false} is classified, and the name and values are never looked at.
     *
     * @param canonicalJson the canonical document
     * @return one {@link VersionWarning#UNCLASSIFIED_INPUT} warning per undeclared input
     */
    static List<VersionWarning> unclassified(String canonicalJson) {
        List<VersionWarning> warnings = new ArrayList<>();
        for (JsonNode input : inputs(canonicalJson)) {
            if (!declared(input)) {
                warnings.add(VersionWarning.unclassified(name(input)));
            }
        }
        return warnings;
    }

    /**
     * Counts the inputs of a stored {@code inputs} array that declare no {@code mask}.
     *
     * @param inputsJson the {@code inputs} array of a stored version
     * @return the number of unclassified inputs
     */
    static int unclassifiedCount(String inputsJson) {
        int count = 0;
        for (JsonNode input : EngineValues.read(inputsJson)) {
            if (!declared(input)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Refuses a document with an input that declares no {@code mask}, under strict mode (RLE-050).
     *
     * @param canonicalJson the canonical document
     * @throws ValidationFailedException with one violation per undeclared input, each naming it
     */
    static void requireDeclared(String canonicalJson) {
        List<Violation> violations = new ArrayList<>();
        for (JsonNode input : inputs(canonicalJson)) {
            if (!declared(input)) {
                violations.add(new Violation(null, name(input), "declares no mask, and"
                        + " decision-log.require-explicit-mask requires every input to declare mask: true or"
                        + " mask: false"));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException(violations);
        }
    }

    /**
     * Returns the masking status of every input of a document, in input order.
     *
     * @param canonicalJson the canonical document
     * @param maskByDefault the tenant's {@code decision-log.mask-default}
     * @return the input names with their status
     */
    static List<Status> statuses(String canonicalJson, boolean maskByDefault) {
        List<Status> statuses = new ArrayList<>();
        for (JsonNode input : inputs(canonicalJson)) {
            statuses.add(new Status(name(input), status(input, maskByDefault)));
        }
        return statuses;
    }

    /** One input's masking status. */
    record Status(String input, Masking masking) {
    }

    private static Masking status(JsonNode input, boolean maskByDefault) {
        JsonNode mask = input.get("mask");
        if (mask == null || mask.isNull()) {
            return maskByDefault ? Masking.MASKED_BY_DEFAULT : Masking.NOT_MASKED_BY_DEFAULT;
        }
        return mask.booleanValue() ? Masking.MASKED : Masking.NOT_MASKED;
    }

    private static boolean declared(JsonNode input) {
        JsonNode mask = input.get("mask");
        return mask != null && mask.isBoolean();
    }

    private static String name(JsonNode input) {
        return input.get("name").stringValue();
    }

    private static List<JsonNode> inputs(String canonicalJson) {
        List<JsonNode> inputs = new ArrayList<>();
        EngineValues.read(canonicalJson).path("inputs").forEach(inputs::add);
        return inputs;
    }
}
