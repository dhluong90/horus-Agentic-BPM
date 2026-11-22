package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.StepCatalogue;
import io.github.dhluong90.bpm.process.api.StepCatalogueView;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The step catalogue under {@code /api/bpm/processes/v1} (#103, PRE-032): the handlers, agent adapters, rule sets,
 * calendars and roles the host registered. Needs {@code processes:author}. The tenant and the caller come from the
 * service; a tenant in a request is never read. The {@code ETag} is a hash of the body, so {@code If-None-Match} with
 * it answers {@code 304}.
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class StepCatalogueController {

    private final StepCatalogue catalogue;

    /** @param catalogue the step catalogue service */
    public StepCatalogueController(StepCatalogue catalogue) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue must not be null");
    }

    /** Lists what the host registered, and nothing else. */
    @GetMapping("/step-catalogue")
    public ResponseEntity<byte[]> read(@RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        byte[] body = write(view(catalogue.stepCatalogue()));
        String etag = "\"" + sha256(body) + "\"";
        if (ifNoneMatch != null && ifNoneMatch.strip().replace("W/", "").equals(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, etag).build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag).body(body);
    }

    static ObjectNode view(StepCatalogueView v) {
        ObjectNode node = EngineJson.NODES.objectNode();
        ArrayNode handlers = node.putArray("handlers");
        v.handlers().forEach(h -> {
            ObjectNode n = handlers.addObject();
            n.put("name", h.name());
            n.put("mode", h.mode());
            n.set("inputSchema", EngineJson.JSON.valueToTree(h.inputSchema()));
            n.put("compensable", h.compensable());
        });
        ArrayNode agents = node.putArray("agents");
        v.agents().forEach(a -> {
            ObjectNode n = agents.addObject();
            n.put("name", a.name());
            n.set("inputSchema", EngineJson.JSON.valueToTree(a.inputSchema()));
        });
        ObjectNode ruleSets = node.putObject("ruleSets");
        ruleSets.set("decision", ruleSets(v.decisionRuleSets()));
        ruleSets.set("guardrail", ruleSets(v.guardrailRuleSets()));
        ArrayNode calendars = node.putArray("calendars");
        v.calendars().forEach(calendars::add);
        ArrayNode roles = node.putArray("roles");
        v.roles().forEach(roles::add);
        return node;
    }

    private static ArrayNode ruleSets(java.util.List<StepCatalogueView.RuleSet> sets) {
        ArrayNode out = EngineJson.NODES.arrayNode();
        sets.forEach(s -> {
            ObjectNode n = out.addObject();
            n.put("name", s.name());
            n.put("version", s.version());
            n.put("hitPolicy", s.hitPolicy());
            n.set("inputs", EngineJson.JSON.valueToTree(s.inputs()));
            n.set("outputs", EngineJson.JSON.valueToTree(s.outputs()));
        });
        return out;
    }

    private static byte[] write(ObjectNode body) {
        try {
            return EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
