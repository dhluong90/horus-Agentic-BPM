package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionSearch;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RLE-029 on the in-memory stores: the bounds, the cursor and tenant isolation of the decision log search. The
 * PostgreSQL statement, its partition pruning and its query plan are checked by DecisionLogSearchPostgresIT.
 */
class DecisionLogQueryTest {

    static final Instant JAN = Instant.parse("2027-01-01T00:00:00Z");
    static final Instant FEB = Instant.parse("2027-02-01T00:00:00Z");
    static final Set<String> AUDITOR = Set.of("rules:log:read");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "dan");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2027-02-10T00:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    @BeforeEach
    void setUp() {
        engines.cursorKeys().load();
        caller.become("bank-a", "dan", AUDITOR);
    }

    @Test
    void RLE_029_decision_search_by_rule_set_and_period_is_bounded() {
        // Given 250 card-fee and 40 kyc-risk decisions of bank-a in January 2027, 10 card-fee decisions of bank-b
        List<String> cardFee = log("bank-a", "card-fee", 250);
        log("bank-a", "kyc-risk", 40);
        log("bank-b", "card-fee", 10);

        // When
        Page<DecisionLogEntry> first = search(DecisionSearch.of("card-fee", JAN, FEB), null);
        Page<DecisionLogEntry> second = search(DecisionSearch.of("card-fee", JAN, FEB), first.nextCursor());

        // Then 200 newest first and a cursor, then the other 50 and no cursor; no other rule set or tenant
        assertThat(first.items()).hasSize(200);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.items()).hasSize(50);
        assertThat(second.nextCursor()).isNull();
        List<String> seen = new ArrayList<>();
        first.items().forEach(e -> seen.add(e.decisionId()));
        second.items().forEach(e -> seen.add(e.decisionId()));
        assertThat(seen).isEqualTo(cardFee.reversed());
        assertThat(first.items()).allSatisfy(e -> {
            assertThat(e.ruleSet()).isEqualTo("card-fee");
            assertThat(e.inputs().get("nationalId")).asString().startsWith("hmac:");
            assertThat(e.outcome()).isEqualTo(DecisionLogEntry.Outcome.MATCHED);
            assertThat(e.outputs()).containsEntry("fee", Money.ofMinor("VND", 10000));
        });
        assertThat(stores.decisions).hasSize(300);
    }

    @Test
    void RLE_029_a_range_over_31_days_or_a_missing_rule_set_is_query_invalid() {
        assertThatThrownBy(() -> search(DecisionSearch.of("card-fee", JAN, Instant.parse("2027-02-02T00:00:00Z")),
                null)).isInstanceOf(QueryInvalidException.class).hasMessageContaining("from and to");
        assertThatThrownBy(() -> search(DecisionSearch.of(null, JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("ruleSet");
        assertThatThrownBy(() -> search(DecisionSearch.of("card-fee", null, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("from");
        assertThatThrownBy(() -> search(DecisionSearch.of("card-fee", JAN, null), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("to");
        assertThatThrownBy(() -> search(DecisionSearch.of("card-fee", FEB, JAN), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("after");
        assertThatThrownBy(() -> engines.decisionLogQuery().search(DecisionSearch.of("card-fee", JAN, FEB),
                new PageRequest(201, null))).isInstanceOf(QueryInvalidException.class).hasMessageContaining("limit");
        assertThat(search(DecisionSearch.of("card-fee", JAN, JAN.plus(Duration.ofDays(31))), null).items()).isEmpty();
    }

    @Test
    void RLE_029_another_tenant_sees_only_its_own_decisions() {
        // Given
        log("bank-a", "card-fee", 25);
        List<String> bankB = log("bank-b", "card-fee", 10);

        // When
        caller.become("bank-b", "dan-b", AUDITOR);
        Page<DecisionLogEntry> page = search(DecisionSearch.of("card-fee", JAN, FEB), null);

        // Then
        assertThat(page.items()).extracting(DecisionLogEntry::decisionId).isEqualTo(bankB.reversed());
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void RLE_029_a_caller_without_rules_log_read_is_refused() {
        caller.become("bank-a", "teller-01", Set.of("rules:evaluate", "rules:read"));

        assertThatThrownBy(() -> search(DecisionSearch.of("card-fee", JAN, FEB), null))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void RLE_029_a_cursor_is_refused_for_another_period_rule_set_or_tenant() {
        // Given
        log("bank-a", "card-fee", 5);
        log("bank-a", "kyc-risk", 5);
        String cursor = engines.decisionLogQuery().search(DecisionSearch.of("card-fee", JAN, FEB),
                new PageRequest(2, null)).nextCursor();

        // Then
        assertThatThrownBy(() -> engines.decisionLogQuery().search(
                DecisionSearch.of("card-fee", JAN.plusSeconds(1), FEB), new PageRequest(2, cursor)))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.decisionLogQuery().search(DecisionSearch.of("kyc-risk", JAN, FEB),
                new PageRequest(2, cursor))).isInstanceOf(QueryInvalidException.class);
        caller.become("bank-b", "dan-b", AUDITOR);
        assertThatThrownBy(() -> engines.decisionLogQuery().search(DecisionSearch.of("card-fee", JAN, FEB),
                new PageRequest(2, cursor))).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.decisionLogQuery().search(DecisionSearch.of("card-fee", JAN, FEB),
                new PageRequest(2, Cursors.encode("decisions", "2027-01-01T00:00:00Z|" + Ulid.next(JAN)))))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void RLE_029_rows_with_the_same_time_page_by_id_without_loss() {
        // Given five decisions logged in the same microsecond
        Instant at = Instant.parse("2027-01-15T08:00:00Z");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = Ulid.next(at);
            ids.add(id);
            stores.decisions.add(row("bank-a", "card-fee", id, at));
        }

        // When reading two at a time
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            Page<DecisionLogEntry> page = engines.decisionLogQuery().search(DecisionSearch.of("card-fee", JAN, FEB),
                    new PageRequest(2, cursor));
            page.items().forEach(e -> seen.add(e.decisionId()));
            cursor = page.nextCursor();
        } while (cursor != null);

        // Then
        assertThat(seen).isEqualTo(ids.reversed());
    }

    @Test
    void RLE_029_query_bounds_may_be_lowered_but_not_raised() {
        assertThat(DecisionQuerySettings.defaults().maxRange()).isEqualTo(Duration.ofDays(31));
        assertThat(new DecisionQuerySettings(Duration.ofDays(7), Duration.ofSeconds(1)).maxRange())
                .isEqualTo(Duration.ofDays(7));
        assertThatThrownBy(() -> new DecisionQuerySettings(Duration.ofDays(32), Duration.ofSeconds(2)))
                .hasMessageContaining("horus.bpm.rules.query.max-range");
        assertThatThrownBy(() -> new DecisionQuerySettings(Duration.ofDays(31), Duration.ofSeconds(31)))
                .hasMessageContaining("horus.bpm.rules.query.statement-timeout");
    }

    private Page<DecisionLogEntry> search(DecisionSearch search, String cursor) {
        return engines.decisionLogQuery().search(search, new PageRequest(null, cursor));
    }

    /** Logs {@code count} decisions spread over January 2027, oldest first; returns their ids in that order. */
    private List<String> log(String tenant, String ruleSet, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant at = JAN.plus(Duration.ofMinutes(10L * i + (tenant.hashCode() & 7) + ruleSet.length()));
            String id = Ulid.next(at);
            ids.add(id);
            stores.decisions.add(row(tenant, ruleSet, id, at));
        }
        return ids;
    }

    private static DecisionRecord row(String tenant, String ruleSet, String id, Instant at) {
        return new DecisionRecord(tenant, id, at, ruleSet, 1, "FIRST", "OK", null,
                "{\"tier\":\"SILVER\",\"txCount\":5,\"nationalId\":\"hmac:9f2c41d07a\"}",
                "{\"fee\":{\"currency\":\"VND\",\"amount\":10000}}", "[\"R2\"]", "teller-01", null, at, 31);
    }
}
