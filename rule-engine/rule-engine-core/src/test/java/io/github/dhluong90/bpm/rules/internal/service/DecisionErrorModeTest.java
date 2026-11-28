package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DecisionSearch;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * RLE-232: the decision log stores the error mode each decision was evaluated in, and a row without one is shown as
 * unknown, never as a guessed strict.
 */
class DecisionErrorModeTest {

    static final Instant NOW = Instant.parse("2027-01-15T02:00:00Z");
    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: tier, type: string }
            outputs:
              - { name: fee, type: integer }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0" } }
              - { id: R2, when: {}, then: { fee: "10000" } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(NOW, ZoneOffset.UTC), stores, stores,
                    EngineSettings.defaults().withRequireContentHash(false)),
            stores, stores, stores);

    DecisionResult evaluateCardFee() {
        engines.cursorKeys().load();
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(CARD_FEE.getBytes(StandardCharsets.UTF_8)));
        long lock = engines.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, lock, null));
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        return engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER")));
    }

    @Test
    void RLE_232_a_decision_row_stores_the_mode_the_engine_evaluated_in() {
        // Given card-fee v1 published and evaluated once
        DecisionResult result = evaluateCardFee();

        // When the stored row and both reads of it are taken
        DecisionRecord row = stores.decisions.getFirst();
        caller.become("bank-a", "auditor", Set.of("rules:log:read"));
        LoggedDecision logged = engines.decisionLog().get(result.decisionId());
        DecisionLogEntry entry = engines.decisionLogQuery()
                .search(DecisionSearch.of("card-fee", NOW.minusSeconds(3600), NOW.plusSeconds(3600)),
                        PageRequest.first()).items().getFirst();

        // Then the mode comes from the evaluation, not from the reader
        assertThat(row.errorMode()).isEqualTo("strict");
        assertThat(logged.errorMode()).isEqualTo("strict");
        assertThat(entry.errorMode()).isEqualTo("strict");
        assertThat(EngineValues.loggedDecision(logged).get("errorMode").asString()).isEqualTo("strict");
    }

    @Test
    void RLE_232_a_row_logged_before_the_mode_was_stored_shows_unknown_and_not_strict() {
        // Given a row written by a release that did not store the mode (error_mode is NULL)
        engines.cursorKeys().load();
        Instant at = Instant.parse("2027-01-10T09:00:00Z");
        String id = Ulid.next(at);
        stores.decisions.add(new DecisionRecord("bank-a", id, at, "card-fee", 1, "FIRST", "OK", null,
                "{\"tier\":\"SILVER\"}", "{\"fee\":10000}", "[\"R2\"]", "teller-01", null, at, 31, false, null, null));

        // When it is read
        caller.become("bank-a", "auditor", Set.of("rules:log:read"));
        LoggedDecision logged = engines.decisionLog().get(id);
        DecisionLogEntry entry = engines.decisionLogQuery()
                .search(DecisionSearch.of("card-fee", at.minusSeconds(3600), at.plusSeconds(3600)),
                        PageRequest.first()).items().getFirst();

        // Then no mode is invented
        assertThat(logged.errorMode()).isNull();
        assertThat(EngineValues.loggedDecision(logged).get("errorMode").asString()).isEqualTo("unknown");
        assertThat(EngineValues.decisionLogEntry(entry).get("errorMode").asString()).isEqualTo("unknown");
    }
}
