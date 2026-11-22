package io.github.dhluong90.bpm.process.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The step catalogue of one tenant (PRE-032).
 *
 * @param handlers service handlers, local and remote, by name
 * @param agents agent adapters by name
 * @param decisionRuleSets rule sets with a version in effect, usable as decision steps
 * @param guardrailRuleSets the decision rule sets that also meet the guardrail contract (PRE-016)
 * @param calendars calendar keys of the tenant
 * @param roles roles the host lists for the tenant; empty when its {@code RoleCatalogue} lists none
 */
public record StepCatalogueView(List<Handler> handlers, List<Agent> agents, List<RuleSet> decisionRuleSets,
        List<RuleSet> guardrailRuleSets, List<String> calendars, List<String> roles) {

    public StepCatalogueView {
        handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers must not be null"));
        agents = List.copyOf(Objects.requireNonNull(agents, "agents must not be null"));
        decisionRuleSets = List.copyOf(Objects.requireNonNull(decisionRuleSets, "decisionRuleSets must not be null"));
        guardrailRuleSets = List.copyOf(Objects.requireNonNull(guardrailRuleSets,
                "guardrailRuleSets must not be null"));
        calendars = List.copyOf(Objects.requireNonNull(calendars, "calendars must not be null"));
        roles = List.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
    }

    /**
     * A service handler.
     *
     * @param name the name used in {@code horus:handler}
     * @param mode {@code local} or {@code remote}
     * @param inputSchema the JSON Schema of its input; empty when the host declared none
     * @param compensable whether the host can undo it
     */
    public record Handler(String name, String mode, Map<String, Object> inputSchema, boolean compensable) {
        public Handler {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(mode, "mode must not be null");
            inputSchema = copy(inputSchema);
        }
    }

    /**
     * An agent adapter.
     *
     * @param name the name used in {@code horus:agent}
     * @param inputSchema the JSON Schema of its input; empty when the host declared none
     */
    public record Agent(String name, Map<String, Object> inputSchema) {
        public Agent {
            Objects.requireNonNull(name, "name must not be null");
            inputSchema = copy(inputSchema);
        }
    }

    /**
     * A rule set with a version in effect.
     *
     * @param name the rule set name
     * @param version the version in effect
     * @param hitPolicy {@code FIRST}, {@code UNIQUE} or {@code COLLECT}
     * @param inputs each declared input and its rule set type
     * @param outputs each declared output and its rule set type
     */
    public record RuleSet(String name, int version, String hitPolicy, Map<String, String> inputs,
            Map<String, String> outputs) {
        public RuleSet {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(hitPolicy, "hitPolicy must not be null");
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        }
    }

    private static Map<String, Object> copy(Map<String, Object> schema) {
        return schema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(schema));
    }
}
