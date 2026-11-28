package io.github.dhluong90.bpm.rules.internal.dmn.model;

import java.util.List;
import java.util.Map;

/**
 * The roots of the DMN semantic model. Owned by the DMN XML stream (#200); the evaluators read it. Adding a member is
 * additive; changing one is a contract change. A schema version 1 rule set lowers to this model with one decision,
 * as ADR-0018 requires, but the stored v1 document and its hash never pass through it.
 */
public final class DmnModel {

    private DmnModel() {
    }

    /** An expression kept as text. @param language FEEL URI or {@code urn:horus-bpm:expr:v1}; null means the model default */
    public record Text(String text, String language, int line) { }

    /**
     * @param namespace the model namespace
     * @param expressionLanguage default language of the model
     * @param errorMode {@code spec} or {@code strict}
     * @param timeZone IANA zone of {@code today()} and {@code now()}
     * @param imports models this one imports, by namespace and location (#217)
     * @param items item definitions by name
     * @param elements decisions, input data, knowledge models, services and sources, in document order
     * @param diagram diagram information kept as read, opaque to evaluation (#200, #217)
     */
    public record Definitions(String id, String name, String namespace, String expressionLanguage, String errorMode,
            String timeZone, List<Import> imports, Map<String, ItemDefinition> items, List<DrgElement> elements,
            List<DiagramShape> diagram) {
        public Definitions {
            imports = List.copyOf(imports);
            items = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(items));
            elements = List.copyOf(elements);
            diagram = List.copyOf(diagram);
        }
    }

    /** @param importType model or knowledge-model kind URI; @param prefix name the importer uses */
    public record Import(String namespace, String locationUri, String importType, String prefix) { }

    /** Position and size of an element on a diagram (DMNDI). @param elementId the element it draws */
    public record DiagramShape(String elementId, double x, double y, double width, double height) { }

    /**
     * An item definition: a type name, a base type or members, allowed values, collection flag.
     *
     * @param typeRef FEEL type name of the base, or null when the item has members
     * @param allowedValues unary tests text, or null
     * @param members member items in order
     */
    public record ItemDefinition(String name, String typeRef, Text allowedValues, boolean collection,
            List<ItemDefinition> members) {
        public ItemDefinition {
            members = List.copyOf(members);
        }
    }

    /** One element of the decision requirements graph. */
    public sealed interface DrgElement {
        /** @return the element id, unique in the model */
        String id();

        /** @return the element name, unique among its kind */
        String name();
    }

    /** An input of the model. @param typeRef FEEL type name */
    public record InputData(String id, String name, String typeRef) implements DrgElement { }

    /**
     * A requirement edge. @param kind what it requires; @param targetId the required element or an import-prefixed id
     */
    public record Requirement(Kind kind, String targetId) {
        /** The requirement kinds of DMN 1.5. */
        public enum Kind { INFORMATION, KNOWLEDGE, AUTHORITY }
    }

    /** A decision: logic, the type of its result and its requirements. @param logic null for a decision without logic */
    public record Decision(String id, String name, String typeRef, BoxedExpression logic,
            List<Requirement> requirements) implements DrgElement {
        public Decision {
            requirements = List.copyOf(requirements);
        }
    }

    /** A business knowledge model: a function. @param parameters names and type names in order */
    public record KnowledgeModel(String id, String name, Map<String, String> parameters, BoxedExpression logic,
            List<Requirement> requirements) implements DrgElement {
        public KnowledgeModel {
            parameters = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters));
            requirements = List.copyOf(requirements);
        }
    }

    /**
     * A decision service.
     *
     * @param outputDecisions ids the service returns
     * @param encapsulatedDecisions ids hidden inside it
     * @param inputDecisions ids it takes as inputs from the caller
     * @param inputData ids of input data it takes
     */
    public record DecisionService(String id, String name, List<String> outputDecisions,
            List<String> encapsulatedDecisions, List<String> inputDecisions, List<String> inputData)
            implements DrgElement {
        public DecisionService {
            outputDecisions = List.copyOf(outputDecisions);
            encapsulatedDecisions = List.copyOf(encapsulatedDecisions);
            inputDecisions = List.copyOf(inputDecisions);
            inputData = List.copyOf(inputData);
        }
    }

    /** A knowledge source: documentation only, never evaluated. */
    public record KnowledgeSource(String id, String name) implements DrgElement { }

    /** The boxed expression kinds of DMN 1.5. */
    public sealed interface BoxedExpression {
        /** @return the declared result type name, or null */
        String typeRef();
    }

    /** Literal expression (#198 evaluates its text). */
    public record LiteralExpression(String typeRef, Text text) implements BoxedExpression { }

    /** One input column, output column and rule row of a decision table. */
    public record InputColumn(String id, Text expression, Text allowedValues) { }

    /** @param defaultValue text of the default output entry, or null */
    public record OutputColumn(String id, String name, String typeRef, Text allowedValues, Text defaultValue) { }

    /** @param inputEntries unary tests, one per input column; @param outputEntries one per output column */
    public record RuleRow(String id, List<Text> inputEntries, List<Text> outputEntries) {
        public RuleRow {
            inputEntries = List.copyOf(inputEntries);
            outputEntries = List.copyOf(outputEntries);
        }
    }

    /** The hit policies of DMN 1.5: U, A, P, F, R, O and C with its four aggregations. */
    public enum HitPolicy { UNIQUE, ANY, PRIORITY, FIRST, RULE_ORDER, OUTPUT_ORDER, COLLECT, COLLECT_SUM, COLLECT_MIN,
        COLLECT_MAX, COLLECT_COUNT }

    /** A decision table. @param outputValues allowed output lists per column, used by PRIORITY and OUTPUT ORDER */
    public record DecisionTable(String typeRef, HitPolicy hitPolicy, List<InputColumn> inputs,
            List<OutputColumn> outputs, List<RuleRow> rules) implements BoxedExpression {
        public DecisionTable {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            rules = List.copyOf(rules);
        }
    }

    /** One named entry of a context box; a null name is the result entry. */
    public record ContextEntry(String name, String typeRef, BoxedExpression value) { }

    /** Context. */
    public record ContextBox(String typeRef, List<ContextEntry> entries) implements BoxedExpression {
        public ContextBox {
            entries = List.copyOf(entries);
        }
    }

    /** List. */
    public record ListBox(String typeRef, List<BoxedExpression> items) implements BoxedExpression {
        public ListBox {
            items = List.copyOf(items);
        }
    }

    /** Relation: named columns and rows of expressions. */
    public record Relation(String typeRef, List<String> columns, List<List<BoxedExpression>> rows)
            implements BoxedExpression {
        public Relation {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    /** Conditional {@code if}/{@code then}/{@code else}. */
    public record Conditional(String typeRef, BoxedExpression condition, BoxedExpression then,
            BoxedExpression otherwise) implements BoxedExpression { }

    /** Iterator: {@code for}, {@code some} or {@code every}. */
    public record Iterator(String typeRef, Kind kind, String variable, BoxedExpression in, BoxedExpression body)
            implements BoxedExpression {
        /** The iterator kinds. */
        public enum Kind { FOR, SOME, EVERY }
    }

    /** Filter: a list expression and a predicate. */
    public record Filter(String typeRef, BoxedExpression in, BoxedExpression match) implements BoxedExpression { }

    /** Invocation of a knowledge model by name with named bindings. */
    public record Invocation(String typeRef, String callee, Map<String, BoxedExpression> bindings)
            implements BoxedExpression {
        public Invocation {
            bindings = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(bindings));
        }
    }

    /** Function definition. {@code kind} FEEL runs; {@code JAVA} and {@code PMML} are refused by name at import. */
    public record FunctionDefinition(String typeRef, Kind kind, Map<String, String> parameters, BoxedExpression body)
            implements BoxedExpression {
        /** The function kinds. */
        public enum Kind { FEEL, JAVA, PMML }

        public FunctionDefinition {
            parameters = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters));
        }
    }
}
