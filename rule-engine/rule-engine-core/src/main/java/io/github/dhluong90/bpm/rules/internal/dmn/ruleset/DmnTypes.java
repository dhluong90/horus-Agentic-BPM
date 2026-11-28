package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.Violation;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The types of one DMN document: the built-in FEEL type names and the {@code itemDefinition}s of the root, mapped to
 * the schema version 1 types (RLE-225). An {@code itemDefinition} is resolved only where a column or a decision uses
 * it, so an unused definition that cannot be mapped does not stop an import. What has no v1 type is refused with
 * {@link #CODE}, never read as another type.
 *
 * <p>Mapped: a built-in name, an alias of one ({@code typeRef}), a collection of a scalar ({@code isCollection},
 * {@code list<T>}), a scalar with {@code allowedValues}, and a structure of components (the shape of a table with
 * several outputs). Refused: a {@code typeConstraint}, a function item, a collection of structures, a nested
 * structure, {@code Any}, {@code time}, durations and a type that is not defined.
 */
final class DmnTypes {

    /** The code of every refusal of a type that has no v1 form. */
    static final String CODE = "DMN_TYPE_UNSUPPORTED";

    private static final int MAX_DEPTH = 16;

    /**
     * A resolved type.
     *
     * @param v1 the v1 type text ({@code decimal}, {@code list<string>}), or null for a structure
     * @param components the components of a structure by name, or null for a scalar or list
     * @param allowed the {@code allowedValues} element that closes the set of values, or null
     */
    record Resolved(String v1, Map<String, Resolved> components, XmlTree allowed) {

        boolean structured() {
            return components != null;
        }
    }

    private final Map<String, XmlTree> definitions = new HashMap<>();

    DmnTypes(XmlTree root, String namespace) {
        for (XmlTree child : root.children()) {
            if (child.namespace().equals(namespace) && child.name().equals("itemDefinition")) {
                child.attribute("name").ifPresent(name -> definitions.putIfAbsent(name, child));
            }
        }
    }

    /** Maps a FEEL built-in type name to the v1 type, or empty when v1 has no such type. */
    static Optional<String> builtIn(String name) {
        return Optional.ofNullable(switch (name.toLowerCase(Locale.ROOT)) {
            case "string" -> "string";
            case "number", "double", "decimal" -> "decimal";
            case "integer", "long", "int" -> "integer";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "date and time", "datetime" -> "datetime";
            default -> null;
        });
    }

    /**
     * Resolves a type reference.
     *
     * @param reference the {@code typeRef} as written
     * @param where what uses it, for the refusal
     * @param at the element that uses it
     * @return the type
     * @throws ValidationFailedException with {@link #CODE} when v1 has no such type
     */
    Resolved resolve(String reference, String where, XmlTree at) {
        return resolve(reference, where, at, 0);
    }

    private Resolved resolve(String raw, String where, XmlTree at, int depth) {
        String reference = raw.strip().replaceFirst("^feel:", "");
        XmlTree definition = definitions.get(reference);
        if (definition == null) {
            return new Resolved(builtIn(reference).orElseThrow(() -> refuse(
                    "typeRef " + Quote.of(reference) + " of " + where + " has no schema version 1 type; use string,"
                            + " number, integer, boolean, date, date and time, or an item definition of them", at)),
                    null, null);
        }
        if (depth > MAX_DEPTH) {
            throw refuse("Item definition " + Quote.of(reference) + " of " + where + " refers to itself or nests more"
                    + " than " + MAX_DEPTH + " levels", definition);
        }
        return definition(definition, reference, where, depth);
    }

    /** An {@code itemDefinition} or an {@code itemComponent}: they share their content. */
    private Resolved definition(XmlTree definition, String name, String where, int depth) {
        for (XmlTree child : definition.children()) {
            if (child.name().equals("typeConstraint") || child.name().equals("functionItem")) {
                throw refuse("Item definition " + Quote.of(name) + " of " + where + " has a " + child.name()
                        + ", which has no schema version 1 form", child);
            }
        }
        boolean collection = "true".equals(definition.attribute("isCollection").orElse("false"));
        XmlTree allowed = definition.child("allowedValues").orElse(null);
        Optional<XmlTree> typeRef = definition.child("typeRef");
        List<XmlTree> components = definition.children("itemComponent");
        Resolved base;
        if (typeRef.isPresent()) {
            Resolved inner = resolve(typeRef.get().text(), where, typeRef.get(), depth + 1);
            base = new Resolved(inner.v1(), inner.components(), allowed != null ? allowed : inner.allowed());
        } else if (!components.isEmpty()) {
            Map<String, Resolved> parts = new LinkedHashMap<>();
            for (XmlTree component : components) {
                String partName = component.attribute("name").orElseThrow(() ->
                        refuse("A component of item definition " + Quote.of(name) + " has no name", component));
                Resolved part = definition(component, partName, where, depth + 1);
                if (part.structured()) {
                    throw refuse("Component " + Quote.of(partName) + " of item definition " + Quote.of(name)
                            + " is itself a structure; v1 reads structures of scalars and lists", component);
                }
                parts.put(partName, part);
            }
            base = new Resolved(null, parts, null);
        } else {
            throw refuse("Item definition " + Quote.of(name) + " of " + where + " has neither a typeRef nor"
                    + " components", definition);
        }
        if (!collection) {
            return base;
        }
        if (base.structured() || base.v1().startsWith("list<")) {
            throw refuse("Item definition " + Quote.of(name) + " of " + where + " is a collection of "
                    + (base.structured() ? "structures" : "lists") + "; v1 reads a list of scalars", definition);
        }
        return new Resolved("list<" + base.v1() + ">", null, base.allowed());
    }

    /** A refusal with {@link #CODE}, naming what cannot be mapped and the line. */
    static ValidationFailedException refuse(String detail, XmlTree at) {
        return refuse(CODE, detail, at);
    }

    static ValidationFailedException refuse(String code, String detail, XmlTree at) {
        return new ValidationFailedException(List.of(new Violation(null, null, detail + " (line " + at.line() + ")",
                code, null)));
    }
}
