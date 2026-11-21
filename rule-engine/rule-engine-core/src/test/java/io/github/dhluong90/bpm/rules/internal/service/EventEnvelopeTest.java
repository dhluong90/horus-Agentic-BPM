package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Every Rule Engine event carries the full envelope of DESIGN section 3.3 (#241): {@code eventId} (the outbox row id),
 * {@code correlationId}, {@code causationId}, {@code producer} and {@code schemaVersion}, next to the existing fields,
 * and every event type has a schema file that the written payload satisfies. At bank-a, lan authors the card-fee
 * table and minh approves or rejects it.
 */
class EventEnvelopeTest {

    static final Instant DEC_10 = Instant.parse("2026-12-10T03:00:00Z");

    /** The types the engine writes today; each one is driven below and checked against its schema file. */
    static final List<String> EMITTED = List.of("rules.version.created.v1", "rules.version.publish-requested.v1",
            "rules.version.published.v1", "rules.version.rejected.v1", "rules.version.withdrawn.v1");

    /** Every event type with a schema file: the emitted ones, and those whose emitting features follow. */
    static final List<String> ALL_TYPES = List.of("rules.decision-log.masked-input-searched.v1",
            "rules.decision.evaluated.v1", "rules.evidence.exported.v1", "rules.version.created.v1",
            "rules.version.draft-updated.v1", "rules.version.publish-requested.v1", "rules.version.published.v1",
            "rules.version.rejected.v1", "rules.version.retire-requested.v1", "rules.version.retired.v1",
            "rules.version.withdrawn.v1");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(DEC_10);
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final VersionService versions = RuleEngines.create(
            new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults().withRequireContentHash(false)),
            stores, stores, stores).versionService();

    void asLan() {
        caller.become("bank-a", "lan", Set.of("rules:read", "rules:author"));
    }

    void asMinh() {
        caller.become("bank-a", "minh", Set.of("rules:read", "rules:approve"));
    }

    JsonNode payload(InMemoryStores.OutboxRow row) {
        return EngineJson.JSON.readTree(row.payload());
    }

    JsonNode last() {
        return payload(stores.outbox.getLast());
    }

    @Test
    void TR_OBS_06_every_emitted_event_type_satisfies_its_schema_and_carries_the_envelope() {
        // Given card-fee v1 imported, requested and approved, v2 imported, requested and rejected, and v2 requested
        // again and withdrawn, each command with its own correlation id
        asLan();
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()).withCorrelationId("req-import-1"));
        versions.requestPublish("card-fee", 1, WriteOptions.none().withCorrelationId("req-request-1"));
        asMinh();
        versions.approve("card-fee", 1, new WriteOptions(null, 1L, null).withCorrelationId("req-approve-1"));
        asLan();
        versions.importVersion("card-fee", ImportRequest.yaml(CardFee.yaml()).withCorrelationId("req-import-2"));
        versions.requestPublish("card-fee", 2, WriteOptions.none().withCorrelationId("req-request-2"));
        asMinh();
        versions.reject("card-fee", 2, new WriteOptions(null, 1L, "Gold tier fee must stay at 0 VND")
                .withCorrelationId("req-reject-2"));
        RuleSetVersion rejected = versions.find("card-fee", 2, false).orElseThrow();
        asLan();
        RuleSetVersion requestedAgain = versions.requestPublish("card-fee", 2,
                new WriteOptions(null, rejected.lockVersion(), null).withCorrelationId("req-request-2b"));
        versions.withdraw("card-fee", 2, new WriteOptions(null, requestedAgain.lockVersion(), "Wrong effective date")
                .withCorrelationId("req-withdraw-2"));

        // Then every emitted type was written, each row satisfies its own schema file, and the envelope is complete
        Set<String> written = new LinkedHashSet<>();
        for (InMemoryStores.OutboxRow row : stores.outbox) {
            JsonNode event = payload(row);
            written.add(event.path("eventType").asString());
            EventSchemas.conforms(event);
            assertThat(EventSchemas.ENVELOPE).allSatisfy(member -> assertThat(event.has(member)).as(member).isTrue());
            assertThat(event.path("eventId").asString()).isEqualTo(row.id());
            assertThat(event.path("tenantId").asString()).isEqualTo("bank-a");
            assertThat(event.path("occurredAt").asString()).isEqualTo("2026-12-10T03:00:00Z");
            assertThat(event.path("causationId").isNull()).isTrue();
            assertThat(event.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(event.path("schemaVersion").isIntegralNumber()).isTrue();
            assertThat(event.path("producer").asString()).isEqualTo(EventEnvelope.PRODUCER);
        }
        assertThat(written).containsExactlyInAnyOrderElementsOf(EMITTED);
        assertThat(stores.outbox).extracting(row -> payload(row).path("correlationId").asString())
                .containsExactly("req-import-1", "req-request-1", "req-approve-1", "req-import-2", "req-request-2",
                        "req-reject-2", "req-request-2b", "req-withdraw-2");
        // and the withdrawal names who took the request back and why
        assertThat(last().path("withdrawnBy").asString()).isEqualTo("lan");
        assertThat(last().path("reason").asString()).isEqualTo("Wrong effective date");
    }

    @Test
    void TR_OBS_06_producer_names_the_engine_and_the_version_the_build_wrote() {
        assertThat(EventEnvelope.PRODUCER).startsWith("rule-engine/").doesNotContain("${");
        assertThat(EventEnvelope.PRODUCER.substring("rule-engine/".length())).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void TR_OBS_06_without_a_correlation_id_the_event_starts_its_own_chain() {
        // Given lan imports card-fee through the Java API without a correlation id
        asLan();
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));

        // Then the event's correlation id is its own event id, a ULID equal to the outbox row id
        JsonNode created = last();
        assertThat(created.path("correlationId").asString()).isEqualTo(created.path("eventId").asString())
                .isEqualTo(stores.outbox.getLast().id()).matches("[0-9A-HJKMNP-TV-Z]{26}");
        // and the fields of 0.1.0 are still there, next to the envelope (additive within v1)
        assertThat(created.path("ruleSet").asString()).isEqualTo("card-fee");
        assertThat(created.path("version").asInt()).isEqualTo(1);
        assertThat(created.path("actor").asString()).isEqualTo("lan");
    }

    @Test
    void TR_OBS_06_a_malformed_correlation_id_is_input_invalid_and_writes_nothing() {
        asLan();
        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(CardFee.yaml())
                .withCorrelationId("abc\n<script>"))).isInstanceOf(InputInvalidException.class);
        assertThat(stores.outbox).isEmpty();
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        assertThatThrownBy(() -> versions.requestPublish("card-fee", 1,
                WriteOptions.none().withCorrelationId("x".repeat(129)))).isInstanceOf(InputInvalidException.class);
        assertThat(stores.outbox).hasSize(1);
    }

    @Test
    void TR_OBS_06_a_replay_with_another_correlation_id_returns_the_original_and_writes_no_event() {
        // Given lan requested publication of card-fee v1 with key K-1 and correlation req-a
        asLan();
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.requestPublish("card-fee", 1, WriteOptions.idempotencyKey("K-1").withCorrelationId("req-a"));
        int rows = stores.outbox.size();

        // When the same command is retried with a new correlation id
        versions.requestPublish("card-fee", 1, WriteOptions.idempotencyKey("K-1").withCorrelationId("req-b"));

        // Then no second event is written and the first keeps its correlation id
        assertThat(stores.outbox).hasSize(rows);
        assertThat(last().path("correlationId").asString()).isEqualTo("req-a");
    }

    @Test
    void TR_OBS_06_one_schema_file_per_event_type_each_with_the_envelope_and_open_to_additions() {
        assertThat(EventSchemas.eventSchemaFiles()).extracting(f -> f.getFileName().toString())
                .containsExactlyElementsOf(ALL_TYPES.stream().map(t -> t + ".schema.json").toList());
        for (String type : ALL_TYPES) {
            Path file = EventSchemas.fileOf(type);
            JsonNode schema = EventSchemas.read(file);
            assertThat(schema.path("title").asString()).isEqualTo(type);
            assertThat(schema.path("$id").asString()).isEqualTo("urn:horus-bpm:schema:event:" + type);
            assertThat(schema.path("additionalProperties").asBoolean()).as(type).isTrue();
            assertThat(schema.path("properties").path("eventType").path("const").asString()).isEqualTo(type);
            List<String> required = new java.util.ArrayList<>();
            schema.path("required").forEach(member -> required.add(member.asString()));
            assertThat(required).as(type).containsAll(EventSchemas.ENVELOPE);
            // and the schema's own example satisfies it, so the types not emitted yet are checked too
            assertThat(schema.path("examples").size()).as(type).isPositive();
            schema.path("examples").forEach(example -> EventSchemas.conformsTo(file, example));
        }
    }
}
