package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.FormSpec;
import io.github.dhluong90.bpm.process.spi.FormRegistry;
import io.github.dhluong90.bpm.process.spi.InputMasker;
import io.github.dhluong90.bpm.process.spi.OutputSchemaValidator;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Task forms (PRE-031, PRE-219, PRE-220): checks what a {@code userTask} declares at import and approval, resolves
 * the form of one task for the cockpit, and validates a completion against it in the service layer, so Java and REST
 * behave the same.
 *
 * <p>A form schema is the task-form subset of JSON Schema: the engine's own validator subset (checked by the host's
 * {@link OutputSchemaValidator}) plus {@code format} {@code date} or {@code date-time} on a string property, and one
 * {@code if}/{@code then}/{@code else} at the root for a field required only in some cases. Nothing else runs: no
 * script, no network, no file read by the engine (the host's {@link FormRegistry} supplies documents). Violations
 * name a path and a rule, never a value.
 */
public final class TaskForms {

    /** Placeholder a masked field carries in a prefilled form. */
    public static final String MASKED = InputMasker.REDACTED;
    /** Most characters of a form link template. */
    public static final int MAX_LINK = 2000;

    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}(/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}){0,9}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(taskId|instanceId)}");
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");
    private static final Set<String> HINT_KEYS = Set.of("order", "widgets", "labels", "readOnly");
    private static final Set<String> WIDGETS = Set.of("text", "textarea", "select", "radio", "checkbox", "date",
            "datetime", "money", "readonly");
    private static final Set<String> FIELD_TYPES = Set.of("string", "integer", "number", "boolean", "object");
    private static final Set<String> FORMATS = Set.of("date", "date-time");
    private static final int MAX_FIELDS = 200;

    /** A host that registers no forms: nothing resolves, so a definition that declares one is refused. */
    static final TaskForms NONE = new TaskForms(FormRegistry.NONE, new JsonSchemaSubsetValidator(), List.of());

    /** What a step declares, resolved. */
    public sealed interface Resolved {

        /**
         * A form to fill.
         *
         * @param schema the result schema
         * @param hints display hints, the definition's over the registry's
         */
        record Form(Map<String, Object> schema, Map<String, Object> hints) implements Resolved {
        }

        /**
         * The host's own screen.
         *
         * @param template the link template as declared
         */
        record Link(String template) implements Resolved {
        }
    }

    private final FormRegistry registry;
    private final OutputSchemaValidator validator;
    private final List<String> linkOrigins;

    /**
     * @param registry the host's forms
     * @param validator checks the core of a form schema and a result; the host's or the engine default
     * @param linkOrigins origins ({@code https://loans.example}) a form link may point to besides relative links
     */
    public TaskForms(FormRegistry registry, OutputSchemaValidator validator, Collection<String> linkOrigins) {
        this.registry = registry == null ? FormRegistry.NONE : registry;
        this.validator = validator == null ? new JsonSchemaSubsetValidator() : validator;
        this.linkOrigins = linkOrigins == null ? List.of() : linkOrigins.stream()
                .map(o -> o.strip().toLowerCase(Locale.ROOT).replaceAll("/+$", "")).filter(o -> !o.isEmpty()).toList();
    }

    // ---- import and approval --------------------------------------------------------------------------------

    /**
     * Every form a definition declares can be used: a link is relative or on an allowed origin, a key or path is
     * registered, its schema and hints are in the task-form subset. One problem per step and fault.
     *
     * @param tenant the tenant of the caller
     * @param process the definition
     * @return the problems, empty when every form is usable
     */
    List<DefinitionProblem> check(String tenant, Process process) {
        List<DefinitionProblem> problems = new ArrayList<>();
        for (FlowElement element : process.allFlowElements()) {
            if (!(element instanceof UserTask task)) {
                continue;
            }
            Optional<FormSpec> spec = task.extensions().first(FormSpec.class);
            if (spec.isEmpty()) {
                continue;
            }
            FormSpec form = spec.get();
            if (form.isLink()) {
                if (!linkAllowed(form.link())) {
                    problems.add(DefinitionProblem.of("FORM_LINK_NOT_ALLOWED", task.id() + ": the form link is neither"
                            + " relative nor on an origin in tasks.form-link-origins", task.id()));
                }
                continue;
            }
            String reference = form.formKey() != null ? form.formKey() : form.schema();
            Optional<FormRegistry.FormDefinition> found = registry.find(tenant, reference);
            if (found.isEmpty()) {
                problems.add(DefinitionProblem.of("FORM_NOT_REGISTERED", task.id() + " -> " + safe(reference)
                        + ": no form of this name is registered in this host", task.id()));
                continue;
            }
            for (String problem : checkSchema(found.get().schema())) {
                problems.add(DefinitionProblem.of("FORM_SCHEMA_INVALID", task.id() + ": form schema " + problem,
                        task.id()));
            }
            Map<String, Object> hints = merge(found.get().hints(), hintsOf(form));
            for (String problem : checkHints(hints, found.get().schema())) {
                problems.add(DefinitionProblem.of("FORM_HINTS_INVALID", task.id() + ": form hints " + problem,
                        task.id()));
            }
        }
        return problems;
    }

    /**
     * @param reference a form key or schema path as written in a definition
     * @return true when it has the shape of one: relative, no {@code ..}, plain characters
     */
    public static boolean validReference(String reference) {
        return reference != null && REFERENCE.matcher(reference).matches() && !reference.contains("..");
    }

    /**
     * A form link is a relative path of the host, or an {@code http} or {@code https} URL on an origin the host
     * listed; it may carry {@code {taskId}} and {@code {instanceId}} and no other placeholder.
     *
     * @param template the link as declared
     * @return true when it may be handed to a browser
     */
    public boolean linkAllowed(String template) {
        if (template == null || template.isBlank() || template.length() > MAX_LINK
                || template.chars().anyMatch(c -> c < 0x21 || c == 0x7f || c == '\\')) {
            return false;
        }
        String probe = PLACEHOLDER.matcher(template).replaceAll("x");
        if (probe.indexOf('{') >= 0 || probe.indexOf('}') >= 0) {
            return false;
        }
        if (probe.startsWith("/")) {
            return !probe.startsWith("//");
        }
        try {
            URI uri = new URI(probe);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getRawUserInfo() != null
                    || uri.getHost() == null) {
                return false;
            }
            String origin = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            return linkOrigins.contains(origin);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    // ---- reading a task's form ------------------------------------------------------------------------------

    /**
     * @param tenant the tenant of the caller
     * @param node the task's step
     * @return what the step declares, or empty when it declares no form
     * @throws FormUnavailableException when the declared form is no longer registered or its schema fails the check
     */
    Optional<Resolved> resolve(String tenant, UserTask node) {
        Optional<FormSpec> spec = node.extensions().first(FormSpec.class);
        if (spec.isEmpty()) {
            return Optional.empty();
        }
        FormSpec form = spec.get();
        if (form.isLink()) {
            return Optional.of(new Resolved.Link(form.link()));
        }
        String reference = form.formKey() != null ? form.formKey() : form.schema();
        FormRegistry.FormDefinition found = registry.find(tenant, reference)
                .orElseThrow(() -> new FormUnavailableException("The host no longer registers the form "
                        + safe(reference)));
        List<String> problems = checkSchema(found.schema());
        if (!problems.isEmpty()) {
            throw new FormUnavailableException("The form " + safe(reference) + " has a schema the engine cannot"
                    + " evaluate: " + problems.get(0));
        }
        return Optional.of(new Resolved.Form(found.schema(), merge(found.hints(), hintsOf(form))));
    }

    /** The registered form of a step is gone or unusable: nothing can be shown or checked. */
    static final class FormUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        FormUnavailableException(String message) {
            super(message);
        }
    }

    /** Deepest object or array level {@link #maskValue} follows; a level below it is masked whole. */
    private static final int MASK_MAX_DEPTH = 16;

    /**
     * The value with every part its schema marks {@code "mask": true} replaced by {@value #MASKED}, at any depth: a
     * nested object property, and the items of an array. A masked part that is null stays null. What the schema does
     * not describe is kept, and a value nested deeper than {@value #MASK_MAX_DEPTH} levels is masked whole, because
     * the schema walk stops there (AUD-04, TR-SEC-05).
     *
     * @param schema the schema of the value, or null
     * @param value the value
     * @return a masked copy; the input is not changed
     */
    static Object maskValue(Object schema, Object value) {
        return maskValue(schema, value, 0);
    }

    private static Object maskValue(Object schema, Object value, int depth) {
        if (value == null || !(schema instanceof Map<?, ?> node)) {
            return value;
        }
        if (Boolean.TRUE.equals(node.get("mask")) || depth > MASK_MAX_DEPTH) {
            return MASKED;
        }
        if (value instanceof Map<?, ?> map && node.get("properties") instanceof Map<?, ?> properties) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()),
                        maskValue(properties.get(entry.getKey()), entry.getValue(), depth + 1));
            }
            return out;
        }
        if (value instanceof java.util.Collection<?> items && node.get("items") instanceof Map<?, ?> itemSchema) {
            List<Object> out = new java.util.ArrayList<>();
            for (Object item : items) {
                out.add(maskValue(itemSchema, item, depth + 1));
            }
            return out;
        }
        return value;
    }

    /**
     * The variables with the parts the schema marks {@code "mask": true} masked, at any depth.
     *
     * @param schema the schema of an object whose properties are variables
     * @param variables the variables
     * @return a masked copy
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> maskVariables(Map<String, Object> schema, Map<String, Object> variables) {
        return (Map<String, Object>) maskValue(schema, variables);
    }

    /**
     * Values for the fields the schema declares, from the instance variables of the same name. A field the schema
     * marks {@code "mask": true} is {@value #MASKED} whenever the variable exists, so the clear value never leaves
     * the engine through the form (TR-SEC-05).
     *
     * @param schema the form schema
     * @param variables the instance variables
     * @return the prefilled data, in the schema's field order
     */
    static Map<String, Object> prefill(Map<String, Object> schema, Map<String, Object> variables) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
            return data;
        }
        for (Map.Entry<?, ?> property : properties.entrySet()) {
            String name = String.valueOf(property.getKey());
            if (!variables.containsKey(name) || variables.get(name) == null) {
                continue;
            }
            data.put(name, maskValue(property.getValue(), variables.get(name)));
        }
        return data;
    }

    /**
     * @param template the declared link
     * @param taskId the task
     * @param instanceId the instance
     * @return the link with its placeholders replaced
     */
    static String link(String template, String taskId, String instanceId) {
        return template.replace("{taskId}", taskId).replace("{instanceId}", instanceId);
    }

    // ---- completion -----------------------------------------------------------------------------------------

    /**
     * Checks a completion result against a form schema.
     *
     * @param schema the form schema
     * @param result the submitted result
     * @return each failing path and rule, empty when the result is valid; never a value
     */
    List<String> validate(Map<String, Object> schema, Map<String, Object> result) {
        List<String> out = new ArrayList<>();
        Map<String, Object> core = new LinkedHashMap<>(schema);
        Object condition = core.remove("if");
        Object then = core.remove("then");
        Object otherwise = core.remove("else");
        core = stripFormats(core);
        out.addAll(nonNull(validator.validate(core, result)));
        out.addAll(formats(schema, result));
        if (condition instanceof Map<?, ?> test) {
            boolean holds = nonNull(validator.validate(asMap(test), result)).isEmpty();
            Object branch = holds ? then : otherwise;
            if (branch instanceof Map<?, ?> rules) {
                out.addAll(nonNull(validator.validate(asMap(rules), result)));
            }
        }
        return out.stream().distinct().limit(JsonSchemaSubsetValidator.MAX_VIOLATIONS).toList();
    }

    private static List<String> nonNull(List<String> violations) {
        return violations == null ? List.of("$: the schema check gave no answer") : violations;
    }

    private static List<String> formats(Map<String, Object> schema, Map<String, Object> result) {
        List<String> out = new ArrayList<>();
        if (!(schema.get("properties") instanceof Map<?, ?> properties)) {
            return out;
        }
        for (Map.Entry<?, ?> property : properties.entrySet()) {
            if (!(property.getValue() instanceof Map<?, ?> p) || !(p.get("format") instanceof String format)) {
                continue;
            }
            Object value = result.get(String.valueOf(property.getKey()));
            if (value instanceof String text && !parses(format, text)) {
                out.add("$." + property.getKey() + ": not a valid " + format);
            }
        }
        return out;
    }

    private static boolean parses(String format, String text) {
        try {
            if (format.equals("date")) {
                LocalDate.parse(text);
            } else {
                OffsetDateTime.parse(text);
            }
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // ---- schema and hints -----------------------------------------------------------------------------------

    /**
     * @param schema a form schema
     * @return the problems that keep the engine from evaluating it, empty when it is in the task-form subset
     */
    List<String> checkSchema(Map<String, Object> schema) {
        List<String> problems = new ArrayList<>();
        if (!"object".equals(schema.get("type"))) {
            problems.add("$: type must be object");
        }
        Map<String, Object> core = new LinkedHashMap<>(schema);
        for (String key : List.of("if", "then", "else")) {
            Object part = core.remove(key);
            if (part != null && !(part instanceof Map<?, ?>)) {
                problems.add("$: " + key + " is not a schema");
            } else if (part instanceof Map<?, ?> sub) {
                nonNull(validator.checkSchema(asMap(sub))).forEach(p -> problems.add(key + " " + p));
            }
        }
        if (schema.containsKey("then") && !schema.containsKey("if") || schema.containsKey("else")
                && !schema.containsKey("if")) {
            problems.add("$: then and else need if");
        }
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            if (properties.size() > MAX_FIELDS) {
                problems.add("$: more than " + MAX_FIELDS + " fields");
            }
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                fieldProblems(String.valueOf(property.getKey()), property.getValue(), problems);
            }
        } else {
            problems.add("$: properties is missing");
        }
        problems.addAll(nonNull(validator.checkSchema(stripFormats(core))));
        return problems.stream().distinct().limit(JsonSchemaSubsetValidator.MAX_VIOLATIONS).toList();
    }

    private static void fieldProblems(String name, Object field, List<String> problems) {
        if (!(field instanceof Map<?, ?> f)) {
            problems.add("$." + name + ": field is not a schema");
            return;
        }
        Object type = f.get("type");
        if (f.containsKey("enum")) {
            return;
        }
        if (!(type instanceof String t) || !FIELD_TYPES.contains(t)) {
            problems.add("$." + name + ": type must be string, integer, number, boolean, object (money) or an enum");
        } else if (t.equals("object") && !isMoney(f)) {
            problems.add("$." + name + ": an object field is the money object {currency, amount}");
        }
        if (f.containsKey("format") && (!"string".equals(type) || !FORMATS.contains(String.valueOf(f.get("format"))))) {
            problems.add("$." + name + ": format is date or date-time on a string");
        }
    }

    private static boolean isMoney(Map<?, ?> field) {
        return field.get("properties") instanceof Map<?, ?> p && p.keySet().equals(Set.of("currency", "amount"));
    }

    /** The core of a schema for the validator: {@code format} is checked here, not by the validator. */
    private static Map<String, Object> stripFormats(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>(schema);
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : properties.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> field && field.containsKey("format")) {
                    Map<String, Object> f = new LinkedHashMap<>(asMap(field));
                    f.remove("format");
                    copy.put(String.valueOf(e.getKey()), f);
                } else {
                    copy.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            out.put("properties", copy);
        }
        return out;
    }

    private static List<String> checkHints(Map<String, Object> hints, Map<String, Object> schema) {
        List<String> problems = new ArrayList<>();
        Set<String> fields = schema.get("properties") instanceof Map<?, ?> p
                ? p.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet()) : Set.of();
        for (String key : hints.keySet()) {
            if (!HINT_KEYS.contains(key)) {
                problems.add("has an unknown member " + safe(key));
            }
        }
        for (String key : List.of("order", "readOnly")) {
            if (hints.get(key) instanceof Collection<?> names) {
                names.stream().map(String::valueOf).filter(n -> !fields.contains(n)).findFirst().ifPresent(
                        n -> problems.add(key + " names " + safe(n) + ", which the schema does not declare"));
            } else if (hints.containsKey(key)) {
                problems.add(key + " is not a list");
            }
        }
        if (hints.get("widgets") instanceof Map<?, ?> widgets) {
            widgets.forEach((field, widget) -> {
                if (!fields.contains(String.valueOf(field)) || !WIDGETS.contains(String.valueOf(widget))) {
                    problems.add("widgets: " + safe(String.valueOf(field)) + " is not a declared field with a known widget");
                }
            });
        } else if (hints.containsKey("widgets")) {
            problems.add("widgets is not an object");
        }
        if (hints.get("labels") instanceof Map<?, ?> labels) {
            labels.forEach((field, byLocale) -> {
                if (!fields.contains(String.valueOf(field)) || !(byLocale instanceof Map<?, ?> texts)
                        || texts.entrySet().stream().anyMatch(t -> !LOCALE.matcher(String.valueOf(t.getKey())).matches()
                                || !(t.getValue() instanceof String s) || s.length() > 200)) {
                    problems.add("labels: " + safe(String.valueOf(field)) + " is not a declared field with texts per"
                            + " locale of at most 200 characters");
                }
            });
        } else if (hints.containsKey("labels")) {
            problems.add("labels is not an object");
        }
        return problems;
    }

    private static Map<String, Object> hintsOf(FormSpec form) {
        return form.hints() == null ? Map.of() : Values.readMap(form.hints());
    }

    private static Map<String, Object> merge(Map<String, Object> registry, Map<String, Object> definition) {
        Map<String, Object> merged = new LinkedHashMap<>(registry);
        merged.putAll(definition);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String safe(String text) {
        String clean = text.replaceAll("[\\p{Cntrl}]", "?");
        return clean.length() > 100 ? clean.substring(0, 100) + "..." : clean;
    }
}
