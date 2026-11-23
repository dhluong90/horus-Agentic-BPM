package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.RuleSetTemplate;
import io.github.dhluong90.bpm.rules.api.RuleSetTemplateService;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rule set templates a host offers (PD-112, RLE-197). Read-only; the cockpit fills its grid from a template and
 * saves with the existing {@code POST /rule-sets}.
 */
@RestController
@RequestMapping(RestSupport.BASE + "/rule-set-templates")
public class RuleSetTemplateController {

    private final RuleSetTemplateService templates;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param templates the templates the host offers
     * @param callers resolves the caller and its permissions
     */
    public RuleSetTemplateController(RuleSetTemplateService templates, CallerResolver callers) {
        this.templates = Objects.requireNonNull(templates, "templates must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /**
     * {@code GET /rule-set-templates}: needs {@code rules:author}.
     *
     * @return {@code {"templates": [...]}}, the built-in ones first, then the host's
     */
    @GetMapping
    public ResponseEntity<byte[]> list() {
        RestSupport.require(callers, "rules:author");
        ObjectNode body = EngineJson.NODES.objectNode();
        ArrayNode array = body.putArray("templates");
        for (RuleSetTemplate template : templates.list()) {
            ObjectNode node = array.addObject();
            node.put("id", template.id());
            node.set("labels", texts(template.labels()));
            node.set("descriptions", texts(template.descriptions()));
            node.put("source", template.source());
            node.put("hitPolicy", template.hitPolicy());
            ArrayNode inputs = node.putArray("inputs");
            for (RuleSetTemplate.Input input : template.inputs()) {
                ObjectNode in = inputs.addObject();
                in.put("name", input.name());
                in.put("type", input.type());
                ArrayNode allowed = in.putArray("allowed");
                input.allowed().forEach(allowed::add);
            }
            ArrayNode outputs = node.putArray("outputs");
            for (RuleSetTemplate.Output output : template.outputs()) {
                outputs.addObject().put("name", output.name()).put("type", output.type());
            }
            ArrayNode rows = node.putArray("rows");
            for (RuleSetTemplate.Row row : template.rows()) {
                ObjectNode out = rows.addObject();
                out.put("id", row.id());
                out.set("when", cells(row.when()));
                out.set("then", cells(row.then()));
            }
        }
        return RestSupport.json(HttpStatus.OK, body);
    }

    private static ObjectNode texts(Map<String, String> byLanguage) {
        ObjectNode node = EngineJson.NODES.objectNode();
        new TreeMap<>(byLanguage).forEach(node::put);
        return node;
    }

    private static ObjectNode cells(Map<String, ?> cells) {
        ObjectNode node = EngineJson.NODES.objectNode();
        cells.forEach((name, value) -> node.set(name, EngineValues.value(value)));
        return node;
    }
}
