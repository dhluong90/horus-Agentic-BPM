package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.internal.json.CanonicalJson;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.MemoryEntry;
import io.github.dhluong90.bpm.process.spi.InputMasker;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Bounded memory of an agent step (#101, DESIGN section 4.1, PRE-100 to PRE-102): declared fields of earlier agent
 * steps' recorded results in the same instance. Pure functions over the definition and the recorded results the
 * caller read in the instance's tenant; nothing here reads storage, a variable or another instance.
 *
 * <p>A source must be an agent step: its output schema says which fields exist and which are masked, so an entry is
 * checked field by field at import and a masked field needs {@code includeMasked}, which the definition's four-eyes
 * approval shows.
 */
final class AgentMemory {

    /** A step failure: the resolved memory is larger than {@code agents.memory.max-bytes}; not retryable. */
    static final String TOO_LARGE = "AGENT_MEMORY_TOO_LARGE";
    /** A step failure: a {@code required} entry's step has no recorded result in the instance; not retryable. */
    static final String MISSING = "AGENT_MEMORY_MISSING";
    /** Import problem of an entry that names no upstream agent step, an undeclared field or too many entries. */
    static final String INVALID = "AGENT_MEMORY_INVALID";
    /** Import problem of a masked field listed without {@code includeMasked="true"}. */
    static final String MASKED_FIELD = "AGENT_MEMORY_MASKED_FIELD";

    private AgentMemory() {
    }

    /** What resolving a step's memory gave. */
    sealed interface Resolution {

        /**
         * @param memory what the agent receives: per entry name, the declared fields that the result holds
         * @param audit the same with every masked field as a keyed fingerprint, for history
         * @param missing names of optional entries whose step has no recorded result
         * @param bytes UTF-8 bytes of {@code memory} as canonical JSON
         */
        record Resolved(Map<String, Object> memory, Map<String, Object> audit, List<String> missing, int bytes)
                implements Resolution {
        }

        /**
         * @param code {@link #TOO_LARGE} or {@link #MISSING}
         * @param entry the entry's name, or null for the size
         * @param summary what failed, built from names and numbers only, never from a value
         */
        record Failed(String code, String entry, String summary) implements Resolution {
        }
    }

    /** @return the ids of the steps any step of the process reads as memory */
    static Set<String> sources(Process process) {
        Set<String> sources = new HashSet<>();
        for (FlowElement element : process.flowElements()) {
            if (element instanceof FlowNode node) {
                node.extensions().all(MemoryEntry.class).forEach(m -> sources.add(m.from()));
            }
        }
        return Set.copyOf(sources);
    }

    /**
     * The import check (PRE-101, PRE-102). Each entry must name an agent step of this process that is upstream of the
     * step, list only fields its output schema declares, and list a masked field only with {@code includeMasked}; a
     * step has at most {@code max-entries} entries. Problems name the step and the entry, never a value.
     *
     * @param process the definition
     * @param limits the host's bounds
     * @return the problems, empty when every step's memory is valid
     */
    static List<DefinitionProblem> check(Process process, AgentMemoryLimits limits) {
        Map<String, FlowNode> nodes = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();
        for (FlowElement element : process.flowElements()) {
            if (element instanceof FlowNode node) {
                nodes.put(node.id(), node);
            } else if (element instanceof SequenceFlow flow && flow.sourceRef() != null && flow.targetRef() != null) {
                incoming.computeIfAbsent(flow.targetRef(), k -> new ArrayList<>()).add(flow.sourceRef());
            }
        }
        List<DefinitionProblem> problems = new ArrayList<>();
        for (FlowNode node : nodes.values().stream().sorted(java.util.Comparator.comparing(FlowNode::id)).toList()) {
            List<MemoryEntry> entries = node.extensions().all(MemoryEntry.class);
            if (entries.isEmpty()) {
                continue;
            }
            if (entries.size() > limits.maxEntries()) {
                problems.add(problem(INVALID, node, entries.size() + " memory entries, at most " + limits.maxEntries()
                        + " (agents.memory.max-entries)"));
            }
            Set<String> upstream = upstream(node.id(), incoming);
            for (MemoryEntry entry : entries) {
                String name = "memory entry " + entry.as();
                FlowNode source = nodes.get(entry.from());
                if (source == null) {
                    problems.add(problem(INVALID, node, name + " reads step " + entry.from()
                            + ", which this process does not have"));
                    continue;
                }
                if (source.id().equals(node.id()) || !upstream.contains(source.id())) {
                    problems.add(problem(INVALID, node, name + " reads step " + entry.from()
                            + ", which is not upstream of this step"));
                    continue;
                }
                Map<String, Object> schema = outputSchema(source).orElse(null);
                if (schema == null) {
                    problems.add(problem(INVALID, node, name + " reads step " + entry.from()
                            + ", which is not an agent step with an output schema"));
                    continue;
                }
                for (String field : entry.fields()) {
                    Field declared = field(schema, field);
                    if (declared == null) {
                        problems.add(problem(INVALID, node, name + ": the output schema of " + entry.from()
                                + " does not declare field " + field));
                    } else if (declared.masked() && !entry.includeMasked()) {
                        problems.add(problem(MASKED_FIELD, node, name + ": field " + field + " of " + entry.from()
                                + " is masked (mask: true); list it only with includeMasked=\"true\", which the "
                                + "approval of this definition shows"));
                    }
                }
            }
        }
        return problems;
    }

    /**
     * Resolves a step's memory from the recorded results of its sources in this instance (PRE-100, PRE-101).
     *
     * @param tenantId the instance's tenant, for the masker
     * @param step the agent step being reached
     * @param nodes the definition's steps by id
     * @param recorded the latest recorded result of each source step in this instance, read in the instance's tenant
     * @param limits the host's bounds
     * @param masker the host's masker
     * @return the memory and its audit copy, or why the step fails
     */
    static Resolution resolve(String tenantId, FlowNode step, Function<String, Optional<FlowNode>> nodes,
            Map<String, Map<String, Object>> recorded, AgentMemoryLimits limits, InputMasker masker) {
        Map<String, Object> memory = new LinkedHashMap<>();
        Map<String, Object> audit = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (MemoryEntry entry : step.extensions().all(MemoryEntry.class)) {
            Map<String, Object> result = recorded.get(entry.from());
            if (result == null) {
                if (entry.required()) {
                    return new Resolution.Failed(MISSING, entry.as(), "Memory entry " + entry.as() + " needs the "
                            + "recorded result of step " + entry.from() + ", which this instance does not have");
                }
                missing.add(entry.as());
                continue;
            }
            Map<String, Object> schema = nodes.apply(entry.from()).flatMap(AgentMemory::outputSchema).orElse(Map.of());
            Map<String, Object> plain = new LinkedHashMap<>();
            Map<String, Object> masked = new LinkedHashMap<>();
            for (String path : entry.fields()) {
                Optional<Object> value = value(result, path);
                if (value.isEmpty()) {
                    continue; // an optional field the answer left out
                }
                Field declared = field(schema, path);
                // An undeclared field cannot pass the import check; fail closed if one does.
                boolean isMasked = declared == null || declared.masked();
                put(plain, path, value.get());
                put(masked, path, isMasked ? AgentAudit.mask(tenantId, value.get(), masker) : value.get());
            }
            memory.put(entry.as(), plain);
            audit.put(entry.as(), masked);
        }
        int bytes = CanonicalJson.write(Values.toJson(memory)).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxBytes()) {
            return new Resolution.Failed(TOO_LARGE, null, "Memory of " + bytes + " bytes, at most "
                    + limits.maxBytes() + " (agents.memory.max-bytes)");
        }
        return new Resolution.Resolved(memory, audit, List.copyOf(missing), bytes);
    }

    /** A declared field: whether it, or an object above it, is marked {@code mask: true}. */
    private record Field(boolean masked) {
    }

    /** The schema of the field at the path, or null when the schema does not declare it. */
    private static Field field(Map<String, Object> schema, String path) {
        Object current = schema;
        boolean masked = false;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> node) || !(node.get("properties") instanceof Map<?, ?> properties)
                    || !(properties.get(segment) instanceof Map<?, ?> child)) {
                return null;
            }
            masked |= Boolean.TRUE.equals(child.get("mask"));
            current = child;
        }
        return new Field(masked);
    }

    private static Optional<Map<String, Object>> outputSchema(FlowNode node) {
        return node.extensions().first(AgentTaskSpec.class).map(AgentTaskSpec::outputSchema)
                .filter(text -> !text.isBlank()).map(Values::readMap);
    }

    private static Optional<Object> value(Map<String, Object> result, String path) {
        Object current = result;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return Optional.empty();
            }
            current = map.get(segment);
        }
        return Optional.ofNullable(current);
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> target, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < segments.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(segments[i], k -> new LinkedHashMap<>());
        }
        current.put(segments[segments.length - 1], value);
    }

    /** Every step from which a path of sequence flows leads to the step. */
    private static Set<String> upstream(String step, Map<String, List<String>> incoming) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(incoming.getOrDefault(step, List.of()));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (seen.add(next)) {
                queue.addAll(incoming.getOrDefault(next, List.of()));
            }
        }
        return seen;
    }

    private static DefinitionProblem problem(String code, FlowNode node, String message) {
        return new DefinitionProblem(code, message, "ServiceTask", node.id(), 0);
    }
}
