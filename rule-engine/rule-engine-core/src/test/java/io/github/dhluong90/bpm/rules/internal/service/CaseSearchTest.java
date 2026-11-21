package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.CorrelationSearch;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputSearch;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Decision log search by the host's case reference (RLE-070) and by the exact value of a plain input (RLE-071).
 * Example: loan application {@code LA-2027-000123} of bank-a touches {@code kyc-risk}, {@code loan-pricing} and
 * {@code card-fee} on 10 January 2027, and a complaint arrives with that number.
 */
class CaseSearchTest {

    static final Instant JAN = Instant.parse("2027-01-01T00:00:00Z");
    static final Instant FEB = Instant.parse("2027-02-01T00:00:00Z");
    static final String APPLICATION = "LA-2027-000123";
    static final Set<String> AUDITOR = Set.of("rules:log:read");
    static final byte[] KEY = MaskedInputsTest.KEY_A;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final MaskedInputsTest.MovingClock clock = new MaskedInputsTest.MovingClock(Instant.parse("2027-01-10T09:00:00Z"));

    static final String KYC_RISK = """
            schemaVersion: 1
            ruleSet: kyc-risk
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string, mask: true }
            outputs:
              - { name: risk, type: string }
            rules:
              - { id: K1, when: {}, then: { risk: "'LOW'" } }
            """;

    static final String LOAN_PRICING = """
            schemaVersion: 1
            ruleSet: loan-pricing
            hitPolicy: first
            inputs:
              - { name: productCode, type: string, mask: false }
              - { name: amount, type: money }
              - { name: marginBps, type: decimal }
              - { name: channel, type: string }
              - { name: tags, type: "list<string>", mask: false }
              - { name: nationalId, type: string, mask: true }
            outputs:
              - { name: rate, type: decimal }
            rules:
              - { id: R1, when: {}, then: { rate: 0.085 } }
            """;

    RuleEngines engines(boolean maskByDefault) {
        MaskingPolicy masking = new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.of(MaskingKey.of(KEY));
            }

            @Override
            public boolean maskByDefault(String tenantId) {
                return maskByDefault;
            }
        };
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults().withRequireContentHash(false), masking), stores, stores, stores);
        engines.cursorKeys().load();
        return engines;
    }

    void publish(RuleEngines engines, String tenant, String yaml, String name) {
        caller.become(tenant, "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        long lock = engines.versionService().requestPublish(name, 1, WriteOptions.none()).lockVersion();
        caller.become(tenant, "bob");
        try {
            engines.versionService().approve(name, 1, new WriteOptions(null, lock, null));
        } catch (io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException shown) {
            engines.versionService().approve(name, 1, new WriteOptions(null, lock, null).acknowledging(shown.warnings()
                    .stream().map(w -> new io.github.dhluong90.bpm.rules.api.AcknowledgedWarning(w.code(), w.input()))
                    .toList()));
        }
    }

    void decide(RuleEngines engines, String tenant, String ruleSet, Map<String, Object> inputs, String correlation) {
        caller.become(tenant, "svc-los", Set.of("rules:evaluate"));
        engines.ruleEngine().evaluate(DecisionRequest.of(ruleSet, DecisionInput.of(inputs))
                .withCorrelationId(correlation));
        clock.advance(Duration.ofSeconds(1));
    }

    /** A decision row of another application, written straight into the log. */
    void otherDecision(String tenant, String ruleSet, String correlation, int minute) {
        Instant at = Instant.parse("2027-01-20T00:00:00Z").plus(Duration.ofMinutes(minute));
        stores.append(new DecisionRecord(tenant, Ulid.next(at), at, ruleSet, 1, "FIRST", "OK", null, "{}", "{}", "[]",
                "svc-los", correlation, at, 10));
    }

    @Test
    void RLE_070_decisions_are_found_by_correlation_id_across_rule_sets() {
        // Given application LA-2027-000123 decided on 2027-01-10 by kyc-risk, loan-pricing and card-fee (twice),
        // 500 other bank-a decisions in January, and one bank-b decision with the same correlation id
        RuleEngines engines = engines(false);
        publish(engines, "bank-a", KYC_RISK, "kyc-risk");
        publish(engines, "bank-a", LOAN_PRICING, "loan-pricing");
        publish(engines, "bank-a", new String(CardFee.yaml(), StandardCharsets.UTF_8), "card-fee");
        publish(engines, "bank-b", new String(CardFee.yaml(), StandardCharsets.UTF_8), "card-fee");
        decide(engines, "bank-a", "kyc-risk", Map.of("nationalId", "079090001234"), APPLICATION);
        decide(engines, "bank-a", "loan-pricing", Map.of("productCode", "HOME-30Y"), APPLICATION);
        decide(engines, "bank-a", "card-fee", Map.of("tier", "GOLD", "txCount", 5), APPLICATION);
        decide(engines, "bank-a", "card-fee", Map.of("tier", "SILVER", "txCount", 4), APPLICATION);
        for (int i = 0; i < 500; i++) {
            otherDecision("bank-a", "card-fee", "LA-2027-" + (200000 + i), i);
        }
        decide(engines, "bank-b", "card-fee", Map.of("tier", "GOLD", "txCount", 5), APPLICATION);
        int rows = stores.decisions.size();
        int events = stores.outbox.size();
        caller.become("bank-a", "dan", AUDITOR);

        // When dan searches the case reference over January
        Page<DecisionLogEntry> page = engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of(APPLICATION, JAN, FEB), null);

        // Then the 4 bank-a decisions come back newest first, nationalId only as a fingerprint, no other row
        assertThat(page.items()).hasSize(4);
        assertThat(page.items()).extracting(DecisionLogEntry::ruleSet)
                .containsExactly("card-fee", "card-fee", "loan-pricing", "kyc-risk");
        assertThat(page.items()).extracting(DecisionLogEntry::correlationId).containsOnly(APPLICATION);
        assertThat(page.items()).allSatisfy(entry -> {
            assertThat(entry.version()).isEqualTo(1);
            assertThat(entry.evalClock()).isNotNull();
            assertThat(entry.clockChosen()).isFalse();
            assertThat(entry.pinned()).isFalse();
        });
        assertThat(page.items().getLast().inputs().get("nationalId")).asString().startsWith("hmac:")
                .doesNotContain("079090001234");
        assertThat(page.items()).extracting(DecisionLogEntry::loggedAt).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());
        assertThat(page.nextCursor()).isNull();

        // And ruleSet=card-fee narrows it to the 2 card-fee decisions
        assertThat(engines.decisionLogQuery().searchByCorrelationId(
                new CorrelationSearch(APPLICATION, "card-fee", JAN, FEB), null).items()).hasSize(2);

        // And the search writes nothing
        assertThat(stores.decisions).hasSize(rows);
        assertThat(stores.outbox).hasSize(events);

        // And a bank-b caller gets only its own decision
        caller.become("bank-b", "dan-b", AUDITOR);
        Page<DecisionLogEntry> bankB = engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of(APPLICATION, JAN, FEB), null);
        assertThat(bankB.items()).hasSize(1);
        assertThat(bankB.items().getFirst().ruleSet()).isEqualTo("card-fee");
        assertThat(stores.decisions.stream().filter(r -> r.tenantId().equals("bank-b")
                && bankB.items().getFirst().decisionId().equals(r.id()))).hasSize(1);
    }

    @Test
    void RLE_070_a_bad_period_a_bad_reference_or_a_missing_permission_is_refused() {
        RuleEngines engines = engines(false);
        caller.become("bank-a", "dan", AUDITOR);

        // no from or to, a 32-day period, an empty reference, a reference outside the stored format
        assertThatThrownBy(() -> engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of(APPLICATION, null, null), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("from is required");
        assertThatThrownBy(() -> engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of(APPLICATION, JAN, null), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("to is required");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByCorrelationId(
                CorrelationSearch.of(APPLICATION, JAN, Instant.parse("2027-02-02T00:00:00Z")), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("from and to");
        assertThatThrownBy(() -> engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of("", JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of("LA 2027/123", JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of("x".repeat(129), JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("correlationId");
        assertThatThrownBy(() -> engines.decisionLogQuery()
                .searchByCorrelationId(new CorrelationSearch(APPLICATION, "Card Fee", JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("ruleSet");

        // a caller without rules:log:read
        caller.become("bank-a", "teller-01", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of(APPLICATION, JAN, FEB), null))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void RLE_070_pages_are_bounded_and_the_cursor_is_bound_to_the_reference_and_period() {
        // Given 5 decisions of one application and 5 of another
        for (int i = 0; i < 5; i++) {
            otherDecision("bank-a", "card-fee", APPLICATION, i);
            otherDecision("bank-a", "card-fee", "LA-2027-000124", 10 + i);
        }
        caller.become("bank-a", "dan", AUDITOR);
        RuleEngines engines = engines(false);
        CorrelationSearch search = CorrelationSearch.of(APPLICATION, JAN, FEB);

        // When read two at a time
        List<String> seen = new ArrayList<>();
        PageRequest request = new PageRequest(2, null);
        int pages = 0;
        Page<DecisionLogEntry> page;
        do {
            page = engines.decisionLogQuery().searchByCorrelationId(search, request);
            page.items().forEach(entry -> seen.add(entry.decisionId()));
            request = new PageRequest(2, page.nextCursor());
            pages++;
        } while (page.nextCursor() != null);

        // Then 5 distinct rows in 3 pages
        assertThat(pages).isEqualTo(3);
        assertThat(new HashSet<>(seen)).hasSize(5);
        String cursor = engines.decisionLogQuery().searchByCorrelationId(search, new PageRequest(2, null)).nextCursor();

        // And the cursor is refused for another reference, another period and the plain list
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByCorrelationId(
                CorrelationSearch.of("LA-2027-000124", JAN, FEB), new PageRequest(2, cursor)))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByCorrelationId(
                CorrelationSearch.of(APPLICATION, JAN, FEB.minusSeconds(1)), new PageRequest(2, cursor)))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.decisionLogQuery().search(
                io.github.dhluong90.bpm.rules.api.DecisionSearch.of("card-fee", JAN, FEB), new PageRequest(2, cursor)))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByCorrelationId(search, new PageRequest(201, null)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("limit");
    }

    /** 30 January decisions of loan-pricing with productCode HOME-30Y, 2 of them for 2,500,000,000 VND. */
    RuleEngines loanBook() {
        RuleEngines engines = engines(false);
        publish(engines, "bank-a", LOAN_PRICING, "loan-pricing");
        for (int i = 0; i < 30; i++) {
            Map<String, Object> inputs = new java.util.LinkedHashMap<>();
            inputs.put("productCode", "HOME-30Y");
            inputs.put("amount", Money.ofMinor("VND", i < 2 ? 2_500_000_000L : 800_000_000L));
            inputs.put("marginBps", new BigDecimal("30000"));
            inputs.put("channel", "BRANCH");
            inputs.put("nationalId", "07909000" + (1000 + i));
            decide(engines, "bank-a", "loan-pricing", inputs, null);
        }
        decide(engines, "bank-a", "loan-pricing", Map.of("productCode", "car-5y"), null);
        caller.become("bank-a", "dan", AUDITOR);
        return engines;
    }

    InputSearch loans(String input, Object value) {
        return new InputSearch("loan-pricing", input, value, JAN, FEB);
    }

    @Test
    void RLE_071_decisions_are_found_by_a_plain_input_value_and_never_by_a_masked_one() {
        // Given loan-pricing with productCode (plain), amount (money), nationalId (masked) and 30 January decisions
        RuleEngines engines = loanBook();
        int rows = stores.decisions.size();
        int events = stores.outbox.size();

        // When dan searches productCode = HOME-30Y, then amount = 2,500,000,000 VND
        List<DecisionLogEntry> byProduct = engines.decisionLogQuery()
                .searchByInput(loans("productCode", "HOME-30Y"), null).items();
        List<DecisionLogEntry> byAmount = engines.decisionLogQuery()
                .searchByInput(loans("amount", Money.ofMinor("VND", 2_500_000_000L)), null).items();

        // Then 30 decisions, then the 2 of them, fingerprints for nationalId only
        assertThat(byProduct).hasSize(30);
        assertThat(byAmount).hasSize(2);
        assertThat(byProduct).extracting(DecisionLogEntry::ruleSet).containsOnly("loan-pricing");
        assertThat(byProduct.getFirst().inputs().get("nationalId")).asString().startsWith("hmac:");
        assertThat(byProduct).extracting(e -> e.inputs().get("nationalId").toString())
                .noneMatch(text -> text.contains("07909000"));
        assertThat(byProduct).extracting(DecisionLogEntry::loggedAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());

        // And a number compares by exact value, a string by code point with no case folding
        assertThat(engines.decisionLogQuery().searchByInput(loans("marginBps", new BigDecimal("30000.00")), null)
                .items()).hasSize(30);
        assertThat(engines.decisionLogQuery().searchByInput(loans("marginBps", 30000L), null).items()).hasSize(30);
        assertThat(engines.decisionLogQuery().searchByInput(loans("productCode", "home-30y"), null).items()).isEmpty();
        assertThat(engines.decisionLogQuery().searchByInput(loans("productCode", "HOME-30Y "), null).items())
                .isEmpty();
        assertThat(engines.decisionLogQuery().searchByInput(loans("productCode", "car-5y"), null).items()).hasSize(1);

        // And the search wrote nothing to the decision log or the outbox
        assertThat(stores.decisions).hasSize(rows);
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_071_a_masked_input_is_refused_and_never_answered_with_no_rows() {
        // Given the same book
        RuleEngines engines = loanBook();

        // Then a mask: true input is refused with a pointer to the masked search, whatever the value
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("nationalId", "079081000"), null))
                .isInstanceOf(QueryInvalidException.class)
                .hasMessageContaining("/decisions:find-by-masked-input").hasMessageContaining("rules:masked:search")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("079081000"));
        // And so is the same input with a value that matches nothing: no oracle
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("nationalId", "000000000000"), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("is masked");

        // And an input the tenant default masks is refused too, while an explicit mask: false still searches
        RuleEngines defaultMasks = engines(true);
        assertThatThrownBy(() -> defaultMasks.decisionLogQuery().searchByInput(loans("channel", "BRANCH"), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("is masked");
        assertThat(defaultMasks.decisionLogQuery().searchByInput(loans("productCode", "HOME-30Y"), null).items())
                .hasSize(30);
        // while with the default off the same input is plain and found
        assertThat(engines.decisionLogQuery().searchByInput(loans("channel", "BRANCH"), null).items()).hasSize(30);
    }

    @Test
    void RLE_071_an_unknown_input_the_wrong_type_or_a_bad_period_is_QUERY_INVALID() {
        RuleEngines engines = loanBook();

        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("branchCode", "HN01"), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("branchCode")
                .hasMessageContaining("not declared");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("productCode", 5L), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("value");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("amount", "2500000000"), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("value");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("amount", 2_500_000_000L), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("value");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("productCode", null), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("value");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("tags", "a"), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("list");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(
                new InputSearch("loan-pricing", "productCode", "HOME-30Y", JAN, Instant.parse("2027-02-02T00:00:00Z")),
                null)).isInstanceOf(QueryInvalidException.class).hasMessageContaining("from and to");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(
                new InputSearch("loan-pricing", "product code", "HOME-30Y", JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("input");
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(
                new InputSearch("mortgage-fee", "productCode", "HOME-30Y", JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("not declared");
        caller.become("bank-a", "teller-01", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(loans("productCode", "HOME-30Y"), null))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void RLE_071_another_tenant_sees_only_its_own_decisions_and_its_own_declarations() {
        // Given bank-a's book, and bank-b with its own loan-pricing that declares productCode too
        RuleEngines engines = loanBook();
        publish(engines, "bank-b", LOAN_PRICING, "loan-pricing");
        decide(engines, "bank-b", "loan-pricing", Map.of("productCode", "HOME-30Y"), null);

        // When a bank-b caller searches
        caller.become("bank-b", "dan-b", AUDITOR);
        List<DecisionLogEntry> items = engines.decisionLogQuery().searchByInput(loans("productCode", "HOME-30Y"), null)
                .items();

        // Then it gets its one decision, and a rule set bank-b does not have is not found
        assertThat(items).hasSize(1);
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(
                new InputSearch("card-fee", "tier", "GOLD", JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("not declared");
    }

    @Test
    void RLE_071_pages_follow_the_period_bound_and_the_cursor_is_bound_to_the_value() {
        RuleEngines engines = loanBook();

        Page<DecisionLogEntry> first = engines.decisionLogQuery()
                .searchByInput(loans("productCode", "HOME-30Y"), new PageRequest(12, null));
        Page<DecisionLogEntry> second = engines.decisionLogQuery()
                .searchByInput(loans("productCode", "HOME-30Y"), new PageRequest(12, first.nextCursor()));
        Page<DecisionLogEntry> third = engines.decisionLogQuery()
                .searchByInput(loans("productCode", "HOME-30Y"), new PageRequest(12, second.nextCursor()));

        assertThat(first.items()).hasSize(12);
        assertThat(second.items()).hasSize(12);
        assertThat(third.items()).hasSize(6);
        assertThat(third.nextCursor()).isNull();
        assertThat(new HashSet<>(List.of(first, second, third).stream().flatMap(p -> p.items().stream())
                .map(DecisionLogEntry::decisionId).toList())).hasSize(30);
        assertThatThrownBy(() -> engines.decisionLogQuery()
                .searchByInput(loans("productCode", "car-5y"), new PageRequest(12, first.nextCursor())))
                .isInstanceOf(QueryInvalidException.class);
    }
}
