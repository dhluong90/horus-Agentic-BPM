package com.horus.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The publication events carry what DESIGN section 3.3 lists (#237 step 1): the behaviour hash and the effective date,
 * and on {@code published} both people of the four eyes. Each payload is also checked against its schema in
 * {@code rule-engine/api/schema}, so the schema and the outbox cannot drift apart. Users at bank-a: lan authors,
 * minh approves.
 */
class LifecycleEventPayloadTest {

    static final Instant DEC_10 = Instant.parse("2026-12-10T03:00:00Z");
    static final Instant DEC_15 = Instant.parse("2026-12-15T02:00:00Z");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(DEC_10);
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final VersionService versions = RuleEngines.create(
            new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults()),
            stores, stores, stores).versionService();

    static byte[] cardFee(String effectiveFrom) {
        String yaml = new String(CardFee.yaml(), StandardCharsets.UTF_8);
        return (effectiveFrom == null ? yaml
                : yaml.replace("hitPolicy: first", "effectiveFrom: \"" + effectiveFrom + "\"\nhitPolicy: first"))
                .getBytes(StandardCharsets.UTF_8);
    }

    JsonNode lastEvent(String type) {
        JsonNode payload = EngineJson.JSON.readTree(stores.outbox.getLast().payload());
        assertThat(payload.path("eventType").asString()).isEqualTo(type);
        return payload;
    }

    @Test
    void RLE_009_publish_requested_event_carries_canonical_hash_requester_and_requested_date() {
        // Given card-fee imported by lan with effectiveFrom 1 Jan 2027, and fx-margin without a date
        caller.become("bank-a", "lan", Set.of("rules:read", "rules:author"));
        RuleSetVersion dated = versions.importRuleSet(ImportRequest.yaml(cardFee("2027-01-01T00:00:00Z")));
        versions.importRuleSet(ImportRequest.yaml(new String(cardFee(null), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: fx-margin").getBytes(StandardCharsets.UTF_8)));

        // When lan requests publication of each
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        JsonNode withDate = lastEvent("rules.version.publish-requested.v1");
        versions.requestPublish("fx-margin", 1, WriteOptions.none());
        JsonNode atApproval = lastEvent("rules.version.publish-requested.v1");

        // Then the event names the hash under review, the requester and the requested date (null: at approval)
        assertThat(withDate.path("canonicalHash").asString()).isEqualTo(dated.canonicalHash());
        assertThat(withDate.path("requestedBy").asString()).isEqualTo("lan");
        assertThat(withDate.path("effectiveFrom").asString()).isEqualTo("2027-01-01T00:00:00Z");
        assertThat(withDate.path("ruleSet").asString()).isEqualTo("card-fee");
        assertThat(withDate.path("version").asInt()).isEqualTo(1);
        assertThat(withDate.path("tenantId").asString()).isEqualTo("bank-a");
        assertThat(atApproval.has("effectiveFrom")).isTrue();
        assertThat(atApproval.path("effectiveFrom").isNull()).isTrue();
        // and both payloads satisfy the published schema
        conformsTo("rules.version.publish-requested.v1.schema.json", withDate);
        conformsTo("rules.version.publish-requested.v1.schema.json", atApproval);
    }

    @Test
    void RLE_009_published_event_carries_canonical_hash_effective_date_requester_and_approver() {
        // Given card-fee requested by lan without a date, and fx-margin requested with 1 Jan 2027
        caller.become("bank-a", "lan", Set.of("rules:read", "rules:author"));
        RuleSetVersion imported = versions.importRuleSet(ImportRequest.yaml(cardFee(null)));
        versions.importRuleSet(ImportRequest.yaml(new String(cardFee("2027-01-01T00:00:00Z"), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: fx-margin").getBytes(StandardCharsets.UTF_8)));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        versions.requestPublish("fx-margin", 1, WriteOptions.none());

        // When minh approves both on 15 Dec
        clock.set(DEC_15);
        caller.become("bank-a", "minh", Set.of("rules:read", "rules:approve"));
        versions.approve("card-fee", 1, WriteOptions.none());
        JsonNode atApproval = lastEvent("rules.version.published.v1");
        versions.approve("fx-margin", 1, WriteOptions.none());
        JsonNode scheduled = lastEvent("rules.version.published.v1");

        // Then the event names what was approved, from when, and both people of the four eyes
        assertThat(atApproval.path("canonicalHash").asString()).isEqualTo(imported.canonicalHash());
        assertThat(atApproval.path("effectiveFrom").asString()).isEqualTo("2026-12-15T02:00:00Z");
        assertThat(atApproval.path("requestedBy").asString()).isEqualTo("lan");
        assertThat(atApproval.path("approvedBy").asString()).isEqualTo("minh");
        assertThat(atApproval.path("actor").asString()).isEqualTo("minh");
        assertThat(scheduled.path("effectiveFrom").asString()).isEqualTo("2027-01-01T00:00:00Z");
        // and four eyes is visible in the event itself: the approver is never the requester
        for (JsonNode event : new JsonNode[] {atApproval, scheduled}) {
            assertThat(event.path("approvedBy").asString()).isNotEqualTo(event.path("requestedBy").asString());
        }
        conformsTo("rules.version.published.v1.schema.json", atApproval);
        conformsTo("rules.version.published.v1.schema.json", scheduled);
    }

    /** Checks the schema's required members, const, types and patterns: the subset these two schemas use. */
    static void conformsTo(String schemaFile, JsonNode payload) {
        JsonNode schema;
        try {
            schema = EngineJson.JSON.readTree(Files.readAllBytes(Path.of("../api/schema/" + schemaFile)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        for (JsonNode required : schema.path("required")) {
            assertThat(payload.has(required.asString())).as("%s requires %s", schemaFile, required.asString()).isTrue();
        }
        schema.path("properties").properties().forEach(property -> {
            JsonNode value = payload.get(property.getKey());
            JsonNode rule = property.getValue();
            if (value == null) {
                return;
            }
            if (rule.has("const")) {
                assertThat(value.asString()).as(property.getKey()).isEqualTo(rule.path("const").asString());
            }
            Set<String> types = new java.util.HashSet<>();
            JsonNode type = rule.path("type");
            if (type.isArray()) {
                type.forEach(t -> types.add(t.asString()));
            } else if (type.isString()) {
                types.add(type.asString());
            }
            if (!types.isEmpty()) {
                String actual = value.isNull() ? "null" : value.isString() ? "string" : value.isIntegralNumber() ? "integer"
                        : value.isBoolean() ? "boolean" : value.isObject() ? "object" : "other";
                assertThat(types).as("%s of %s", property.getKey(), schemaFile).contains(actual);
            }
            if (rule.has("pattern") && value.isString()) {
                assertThat(Pattern.compile(rule.path("pattern").asString()).matcher(value.asString()).find())
                        .as("%s matches %s", property.getKey(), rule.path("pattern").asString()).isTrue();
            }
        });
    }
}
