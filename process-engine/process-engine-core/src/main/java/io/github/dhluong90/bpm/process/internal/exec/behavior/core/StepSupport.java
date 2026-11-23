package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded inputs and result variables of task steps. */
public final class StepSupport {

    /** A whole-string reference {@code ${instance.a.b}}; no interpolation inside longer strings. */
    static final Pattern REFERENCE =
            Pattern.compile("^\\$\\{instance\\.([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\}$");

    private StepSupport() {
    }

    /**
     * Resolves the {@code horus:input} values of a node: references read variables, other strings are literals.
     *
     * @return input name to value, in declaration order
     */
    public static Map<String, Object> inputs(FlowNode node, Token token, ExecutionContext ctx) {
        Map<String, Object> visible = ctx.variables().visible(token);
        Map<String, Object> out = new LinkedHashMap<>();
        for (InputMapping input : node.extensions().all(InputMapping.class)) {
            Matcher m = REFERENCE.matcher(input.value());
            if (m.matches()) {
                Object value = visible;
                for (String part : m.group(1).split("\\.")) {
                    value = value instanceof Map<?, ?> map ? map.get(part) : null;
                    if (value == null) {
                        throw new ExecutionException("INPUT_VARIABLE_MISSING", node.id(), "Input " + input.name()
                                + " refers to ${instance." + m.group(1) + "}, which is not set");
                    }
                }
                out.put(input.name(), value);
            } else {
                out.put(input.name(), input.value());
            }
        }
        DataBindings.readInputs(node, token, ctx, out);
        return out;
    }

    /** Stores an output in the node's result variable, or merges it into the process variables. */
    static void storeResult(FlowNode node, Map<String, Object> output, Token token, ExecutionContext ctx) {
        String target = node.extensions().first(ResultVariable.class).map(ResultVariable::name).orElse(null);
        if (target != null) {
            ctx.variables().set(target, Map.copyOf(output));
        } else {
            output.forEach((k, v) -> ctx.variables().set(k, v));
        }
        DataBindings.writeOutputs(node, output, token, ctx);
    }
}
