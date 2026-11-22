package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.GraphDiff;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The graph diff of two versions of one definition (PRE-025, PRE-176, DESIGN section 5.3), a pure function of the
 * two models. Steps are matched by BPMN id and compared on their canonical form, the form that is hashed and
 * approved, without the members that name the following step (those are the links). Links are matched by
 * {@code (from, to, kind)}. Layout is not in the model, so it can never appear. O(N + E) apart from sorting; every
 * list is sorted, so the answer depends only on the two models.
 */
public final class GraphDiffs {

    /** The link kinds of DESIGN section 5.3. */
    static final String NEXT = "next";
    static final String DEFAULT = "default";
    static final String FORK = "fork";

    private static final Comparator<GraphDiff.Edge> EDGE_ORDER = Comparator.comparing(GraphDiff.Edge::from)
            .thenComparing(GraphDiff.Edge::to).thenComparing(GraphDiff.Edge::kind);

    private GraphDiffs() {
    }

    /**
     * @param definitionKey the definition key
     * @param base the base version and its model
     * @param target the target version and its model
     * @return the diff from base to target
     */
    public static GraphDiff diff(String definitionKey, GraphDiff.Side base, Process baseModel, GraphDiff.Side target,
            Process targetModel) {
        return compute(definitionKey, base, baseModel, target, targetModel, GraphDiff.MAX_CHANGES);
    }

    /**
     * The complete change set, never cut off (PRE-199, #426): the same lists as {@link #diff}, with every changed
     * value. It is bounded by the size of the two definitions (at most 500 steps and 1 MB each), and it is the input of
     * the paged read and of the change-set hash.
     *
     * @param definitionKey the definition key
     * @param base the base version and its model
     * @param target the target version and its model
     * @return the diff from base to target, with {@code truncated} false
     */
    public static GraphDiff complete(String definitionKey, GraphDiff.Side base, Process baseModel,
            GraphDiff.Side target, Process targetModel) {
        return compute(definitionKey, base, baseModel, target, targetModel, Integer.MAX_VALUE);
    }

    private static GraphDiff compute(String definitionKey, GraphDiff.Side base, Process baseModel,
            GraphDiff.Side target, Process targetModel, int maxChanges) {
        Objects.requireNonNull(baseModel, "baseModel");
        Objects.requireNonNull(targetModel, "targetModel");
        Graph from = Graph.of(baseModel);
        Graph to = Graph.of(targetModel);

        List<GraphDiff.Node> added = new ArrayList<>();
        List<GraphDiff.Node> removed = new ArrayList<>();
        List<GraphDiff.NodeChange> changed = new ArrayList<>();
        Changes changes = new Changes(changed, maxChanges);
        TreeSet<String> ids = new TreeSet<>(from.steps.keySet());
        ids.addAll(to.steps.keySet());
        for (String id : ids) {
            ObjectNode before = from.steps.get(id);
            ObjectNode after = to.steps.get(id);
            if (before == null) {
                added.add(node(after));
            } else if (after == null) {
                removed.add(node(before));
            } else {
                changes.compare(id, text(after, "name"), text(after, "type"), "", before, after);
            }
        }

        List<GraphDiff.Edge> edgesAdded = new ArrayList<>();
        List<GraphDiff.Edge> edgesRemoved = new ArrayList<>();
        to.edges.forEach((key, edge) -> {
            if (!from.edges.containsKey(key)) {
                edgesAdded.add(edge);
            }
        });
        from.edges.forEach((key, edge) -> {
            if (!to.edges.containsKey(key)) {
                edgesRemoved.add(edge);
            }
        });
        edgesAdded.sort(EDGE_ORDER);
        edgesRemoved.sort(EDGE_ORDER);
        return new GraphDiff(definitionKey, base, target, added, removed, changed, edgesAdded, edgesRemoved,
                changes.truncated);
    }

    private static GraphDiff.Node node(ObjectNode step) {
        return new GraphDiff.Node(text(step, "id"), text(step, "name"), text(step, "type"));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** The changed values of matched steps, at most {@link GraphDiff#MAX_CHANGES}. */
    private static final class Changes {
        private final List<GraphDiff.NodeChange> out;
        private final int max;
        private boolean truncated;

        Changes(List<GraphDiff.NodeChange> out, int max) {
            this.out = out;
            this.max = max;
        }

        void compare(String id, String name, String type, String path, JsonNode before, JsonNode after) {
            if (before != null && after != null && before.isObject() && after.isObject()) {
                TreeSet<String> keys = new TreeSet<>(before.propertyNames());
                keys.addAll(after.propertyNames());
                for (String key : keys) {
                    compare(id, name, type, path + "/" + escape(key), before.get(key), after.get(key));
                }
            } else if (before != null && after != null && before.isArray() && after.isArray()) {
                int size = Math.max(before.size(), after.size());
                for (int i = 0; i < size; i++) {
                    compare(id, name, type, path + "/" + i, i < before.size() ? before.get(i) : null,
                            i < after.size() ? after.get(i) : null);
                }
            } else if (!Objects.equals(before, after)) {
                if (out.size() >= max) {
                    truncated = true;
                    return;
                }
                out.add(new GraphDiff.NodeChange(id, name, type, path, value(before), value(after)));
            }
        }

        private static Object value(JsonNode node) {
            return node == null || node.isNull() ? null : Values.fromJson(node);
        }

        /** RFC 6901: {@code ~} as {@code ~0}, {@code /} as {@code ~1}. */
        private static String escape(String key) {
            return key.replace("~", "~0").replace("/", "~1");
        }
    }

    /** The steps of one model in canonical form, without their links, and its links by {@code (from, to, kind)}. */
    private record Graph(Map<String, ObjectNode> steps, Map<String, GraphDiff.Edge> edges) {

        static Graph of(Process process) {
            Map<String, ObjectNode> steps = new TreeMap<>();
            ArrayNode written = (ArrayNode) CanonicalProcessWriter.write(process).get("steps");
            for (JsonNode step : written) {
                ObjectNode copy = ((ObjectNode) step).deepCopy();
                // The following steps are links, compared as edges; a branch keeps its condition.
                copy.remove("next");
                copy.remove("default");
                JsonNode branches = copy.get("branches");
                if (branches instanceof ArrayNode array) {
                    for (JsonNode branch : array) {
                        ((ObjectNode) branch).remove("next");
                    }
                }
                steps.put(copy.get("id").asString(), copy);
            }
            Map<String, FlowNode> nodes = new HashMap<>();
            List<SequenceFlow> flows = new ArrayList<>();
            for (FlowElement element : process.allFlowElements()) {
                if (element instanceof FlowNode node) {
                    nodes.put(node.id(), node);
                } else if (element instanceof SequenceFlow flow) {
                    flows.add(flow);
                }
            }
            Map<String, GraphDiff.Edge> edges = new TreeMap<>();
            Map<String, Integer> branchIndex = new HashMap<>();
            for (SequenceFlow flow : flows) {
                FlowNode source = nodes.get(flow.sourceRef());
                FlowNode sink = nodes.get(flow.targetRef());
                // branch[i] counts the conditional branches from 0 in document order, as the canonical form does.
                String kind = switch (source) {
                    case ExclusiveGateway g when flow.id().equals(g.defaultFlow()) -> DEFAULT;
                    case ExclusiveGateway g -> "branch[" + (branchIndex.merge(g.id(), 1, Integer::sum) - 1) + "]";
                    case ParallelGateway g when outgoing(flows, g.id()) > 1 -> FORK;
                    case null, default -> NEXT;
                };
                GraphDiff.Edge edge = new GraphDiff.Edge(flow.id(), flow.sourceRef(), source == null ? null
                        : source.name(), flow.targetRef(), sink == null ? null : sink.name(), kind);
                edges.putIfAbsent(edge.from() + "\u0000" + edge.to() + "\u0000" + edge.kind(), edge);
            }
            return new Graph(steps, edges);
        }

        private static long outgoing(List<SequenceFlow> flows, String nodeId) {
            return flows.stream().filter(f -> f.sourceRef().equals(nodeId)).count();
        }
    }
}
