package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.spi.ExpressionFunction;
import io.github.dhluong90.bpm.expr.spi.ValueType;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.ReplayNotPossibleException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.rules.internal.store.FunctionVersionStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Host functions in rule cells (RLE-055 to RLE-058, PD-119): a card fee table of bank-a that waives the fee for
 * the PRIORITY segment, computed by the host's pure function {@code segmentOf(balance, tenureMonths)}.
 */
class HostFunctionsTest {

    static final Set<String> AUDITOR = Set.of("rules:log:read");
    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: balance,      type: money }
              - { name: tenureMonths, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { balance: "segmentOf(balance, tenureMonths) == 'PRIORITY'" }, then: { fee: "0 VND" } }
              - { id: R2, when: {}, then: { fee: "50000 VND" } }
            """;

    final Clock clock = Clock.fixed(Instant.parse("2027-01-05T02:00:00Z"), ZoneOffset.UTC);
    final InMemoryStores stores = new InMemoryStores();
    final FunctionVersionStore approvals = FunctionVersionStore.inMemory();
    final TestCaller caller = new TestCaller("bank-a", "lan");

    static ExpressionFunction segmentOf(String version, int stepCost, ExpressionFunction.Implementation implementation) {
        return new ExpressionFunction("segmentOf", List.of(ValueType.MONEY, ValueType.NUMBER), ValueType.STRING,
                version, stepCost, implementation);
    }

    /** 500,000,000 VND and 24 months make a PRIORITY customer; the function reads its arguments only. */
    static ExpressionFunction segmentOf(String version) {
        return segmentOf(version, 1, arguments -> {
            Money balance = (Money) arguments.get(0);
            BigDecimal tenure = (BigDecimal) arguments.get(1);
            return balance.minorUnits() >= 500_000_000L && tenure.intValue() >= 24 ? "PRIORITY" : "MASS";
        });
    }

    RuleEngines engines(EngineSettings settings, HostFunctions.VersionChange change, ExpressionFunction... functions) {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores, settings,
                io.github.dhluong90.bpm.rules.spi.MaskingPolicy.none(),
                HostFunctions.of(List.of(functions), approvals, change)), stores, stores, stores);
        engines.cursorKeys().load();
        return engines;
    }

    RuleEngines engines(ExpressionFunction... functions) {
        return engines(EngineSettings.defaults(), HostFunctions.VersionChange.WARN, functions);
    }

    void publish(RuleEngines engines, String yaml) {
        caller.become("bank-a", "lan");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bao");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "teller-01");
    }

    static DecisionInput customer(Object balance, int tenureMonths) {
        Map<String, Object> values = new HashMap<>();
        values.put("balance", balance);
        values.put("tenureMonths", tenureMonths);
        return DecisionInput.of(values);
    }

    static Money vnd(long amount) {
        return Money.ofMinor("VND", amount);
    }

    Violation refused(RuleEngines engines, String condition) {
        caller.become("bank-a", "lan");
        ValidationFailedException e = catchThrowableOfType(ValidationFailedException.class, () -> engines.versionService()
                .importRuleSet(ImportRequest.yaml(CARD_FEE.replace("segmentOf(balance, tenureMonths) == 'PRIORITY'",
                        condition).getBytes(StandardCharsets.UTF_8))));
        assertThat(e).as("refused at import: " + condition).isNotNull();
        assertThat(stores.versions).as("a refused document stores nothing").isEmpty();
        return e.violations().getFirst();
    }

    @Test
    void RLE_055_a_registered_host_function_is_called_from_a_rule_cell() {
        RuleEngines engines = engines(segmentOf("2027.1"));
        publish(engines, CARD_FEE);

        DecisionResult priority = engines.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36));
        DecisionResult shortTenure = engines.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 12));
        DecisionResult noBalance = engines.ruleEngine().evaluate("card-fee", customer(null, 36));

        assertThat(priority.matchedRuleIds()).containsExactly("R1");
        assertThat(priority.outputs()).containsExactly(Map.entry("fee", vnd(0)));
        assertThat(shortTenure.matchedRuleIds()).containsExactly("R2");
        assertThat(shortTenure.outputs()).containsExactly(Map.entry("fee", vnd(50_000)));
        // a null argument makes the call null (FEEL), so R1 does not match and R2 gives the default fee
        assertThat(noBalance.matchedRuleIds()).containsExactly("R2");
        assertThat(noBalance.outputs()).containsExactly(Map.entry("fee", vnd(50_000)));
        assertThat(stores.decisions).hasSize(3);
        // the replay of (a) gives the same rule and fee
        caller.become("bank-a", "audrey", AUDITOR);
        DecisionResult replayed = engines.ruleEngine().replay(priority.decisionId());
        assertThat(replayed.matchedRuleIds()).containsExactly("R1");
        assertThat(replayed.outputs()).isEqualTo(priority.outputs());
        assertThat(stores.decisions).as("a replay writes nothing").hasSize(3);
    }

    @Test
    void RLE_055_a_function_never_sees_the_tenant_and_functions_are_host_wide() {
        // The function gets argument values only; the same host function serves bank-a and bank-b alike.
        RuleEngines engines = engines(segmentOf("2027.1"));
        publish(engines, CARD_FEE);
        caller.become("bank-b", "lan");
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36)))
                .as("bank-b has no card-fee: another tenant's rule set is never read").hasMessageContaining("card-fee");
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_056_unknown_or_ill_typed_function_call_is_refused_at_import() {
        RuleEngines engines = engines(segmentOf("2027.1"));

        Violation unknown = refused(engines, "riskScore(balance) == 'HIGH'");
        assertThat(unknown.ruleId()).isEqualTo("R1");
        assertThat(unknown.column()).isEqualTo("balance");
        assertThat(unknown.code()).isEqualTo("FORBIDDEN_CONSTRUCT");
        assertThat(unknown.message()).contains("Unknown function 'riskScore'");
        assertThat(unknown.position()).isZero();

        Violation swapped = refused(engines, "segmentOf(tenureMonths, balance) == 'PRIORITY'");
        assertThat(swapped.code()).isEqualTo("TYPE_MISMATCH");
        assertThat(swapped.message()).contains("segmentOf: parameter 1 is a money, but the argument is a number");

        Violation fewer = refused(engines, "segmentOf(balance) == 'PRIORITY'");
        assertThat(fewer.code()).isEqualTo("TYPE_MISMATCH");
        assertThat(fewer.message()).contains("segmentOf takes 2 arguments (money, number), found 1");

        Violation asNumber = refused(engines, "segmentOf(balance, tenureMonths) > 3");
        assertThat(asNumber.code()).isEqualTo("TYPE_MISMATCH");
        assertThat(asNumber.ruleId()).isEqualTo("R1");
        assertThat(asNumber.position()).isNotNull();
    }

    @Test
    void RLE_056_the_check_runs_again_at_request_publish_and_approval_after_the_host_removes_the_function() {
        RuleEngines withFunction = engines(segmentOf("2027.1"));
        caller.become("bank-a", "lan");
        withFunction.versionService().importRuleSet(ImportRequest.yaml(CARD_FEE.getBytes(StandardCharsets.UTF_8)));
        RuleEngines withoutFunction = engines();

        ValidationFailedException requested = catchThrowableOfType(ValidationFailedException.class,
                () -> withoutFunction.versionService().requestPublish("card-fee", 1, WriteOptions.none()));
        assertThat(requested.violations().getFirst().code()).isEqualTo("FORBIDDEN_CONSTRUCT");

        // requested while the function was registered, approved after the host removed it
        withFunction.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bao");
        ValidationFailedException approved = catchThrowableOfType(ValidationFailedException.class,
                () -> withoutFunction.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null)));
        assertThat(approved.violations().getFirst().code()).isEqualTo("FORBIDDEN_CONSTRUCT");
        assertThat(approvals.approvedWith("bank-a", "card-fee", 1)).as("nothing was approved").isEmpty();
    }

    @Test
    void RLE_057_a_failing_host_function_fails_the_decision_closed_without_leaking() {
        RuleEngines throwing = engines(segmentOf("2027.1", 1, arguments -> {
            throw new IllegalStateException("upstream timeout for 079090001234");
        }));
        publish(throwing, CARD_FEE);

        DecisionResult failed = throwing.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36));

        assertThat(failed.isError()).isTrue();
        DecisionResult.Failed outcome = (DecisionResult.Failed) failed.outcome();
        assertThat(outcome.code()).isEqualTo("EVALUATION_ERROR");
        assertThat(outcome.detail()).contains("FUNCTION_FAILED").contains("segmentOf")
                .doesNotContain("upstream").doesNotContain("IllegalStateException").doesNotContain("079090001234");
        assertThat(failed.outputs()).isEmpty();
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.errorCode()).isEqualTo("FUNCTION_FAILED");
            assertThat(row.outputsJson()).isNull();
        });
    }

    @Test
    void RLE_057_a_function_that_returns_the_wrong_type_fails_closed_and_a_costly_one_is_refused_at_import() {
        RuleEngines wrongType = engines(segmentOf("2027.1", 1, arguments -> Integer.valueOf(7)));
        publish(wrongType, CARD_FEE);

        DecisionResult failed = wrongType.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36));

        assertThat(failed.isError()).isTrue();
        assertThat(((DecisionResult.Failed) failed.outcome()).detail()).contains("FUNCTION_FAILED").contains("segmentOf");
        assertThat(stores.decisions).singleElement().satisfies(row -> assertThat(row.errorCode())
                .isEqualTo("FUNCTION_FAILED"));

        // each call counts its declared step cost against the step budget: 20,000 steps over a budget of 10,000
        EngineSettings tight = EngineSettings.defaults().withEvaluationLimits(
                new EvaluationLimits(10_000, EvaluationLimits.TIMEOUT_MILLIS));
        RuleEngines costly = engines(tight, HostFunctions.VersionChange.WARN,
                segmentOf("2027.1", 20_000, arguments -> "MASS"));
        stores.decisions.clear();
        stores.versions.clear();
        caller.become("bank-a", "lan");
        ValidationFailedException refused = catchThrowableOfType(ValidationFailedException.class,
                () -> costly.versionService().importRuleSet(ImportRequest.yaml(CARD_FEE.getBytes(StandardCharsets.UTF_8))));
        assertThat(refused.violations().getFirst().code()).isEqualTo("EVALUATION_BUDGET_EXCEEDED");
        assertThat(stores.versions).isEmpty();
    }

    @Test
    void RLE_057_a_call_counts_its_declared_step_cost_against_the_budget_of_each_decision() {
        // approved at the default budget, the call costs 20 steps and the first condition 24 in all
        RuleEngines engines = engines(segmentOf("2027.1", 20, arguments -> "PRIORITY"));
        publish(engines, CARD_FEE);
        // a host that lowers the budget to 15 steps stops the same decision, at the same step, deterministically
        RuleEngines tight = engines(EngineSettings.defaults().withEvaluationLimits(
                new EvaluationLimits(15, EvaluationLimits.TIMEOUT_MILLIS)), HostFunctions.VersionChange.WARN,
                segmentOf("2027.1", 20, arguments -> "PRIORITY"));

        DecisionResult stopped = tight.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36));

        assertThat(stopped.isError()).isTrue();
        assertThat(((DecisionResult.Failed) stopped.outcome()).detail()).startsWith("EVALUATION_BUDGET_EXCEEDED");
        assertThat(stores.decisions.getLast().errorCode()).isEqualTo("EVALUATION_BUDGET_EXCEEDED");
    }

    @Test
    void RLE_058_registry_is_checked_at_startup_and_decisions_record_function_versions() {
        // (a) twice, (b) a built-in name, (c) an invalid name: each stops start-up naming the function
        assertThatThrownBy(() -> HostFunctions.of(List.of(segmentOf("2027.1"), segmentOf("2027.1")), approvals,
                HostFunctions.VersionChange.WARN)).hasMessage("Function 'segmentOf' is registered twice");
        assertThatThrownBy(() -> HostFunctions.of(List.of(ExpressionFunction.of("round", "1", ValueType.NUMBER,
                a -> a.get(0), ValueType.NUMBER)), approvals, HostFunctions.VersionChange.WARN))
                .hasMessageContaining("Function 'round'");
        assertThatThrownBy(() -> ExpressionFunction.of("Segment-Of", "1", ValueType.NUMBER, a -> a.get(0),
                ValueType.NUMBER)).hasMessageContaining("Function 'Segment-Of'");

        // (d) card-fee v1 approved with 2027.1, a decision D1, then the host redeploys with 2027.2
        RuleEngines before = engines(segmentOf("2027.1"));
        publish(before, CARD_FEE);
        DecisionResult d1 = before.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36));
        assertThat(stores.decisions.getFirst().functionVersions()).isEqualTo("segmentOf@2027.1");
        assertThat(before.functionCheck().changes()).isEmpty();

        RuleEngines after = engines(segmentOf("2027.2"));
        assertThat(after.functionCheck().changes()).singleElement().satisfies(change -> {
            assertThat(change.function()).isEqualTo("segmentOf");
            assertThat(change.approvedVersion()).isEqualTo("2027.1");
            assertThat(change.currentVersion()).isEqualTo("2027.2");
            assertThat(change.describedUses()).containsExactly("bank-a/card-fee v1");
        });
        // warn is the default: evaluation goes on, and the new row records the version it used
        DecisionResult d2 = after.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36));
        assertThat(d2.isError()).isFalse();
        assertThat(stores.decisions.getLast().functionVersions()).isEqualTo("segmentOf@2027.2");
        // the replay of D1 names the function and both labels
        caller.become("bank-a", "audrey", AUDITOR);
        assertThatThrownBy(() -> after.ruleEngine().replay(d1.decisionId())).isInstanceOf(ReplayNotPossibleException.class)
                .hasMessageContaining("segmentOf").hasMessageContaining("2027.1").hasMessageContaining("2027.2");
    }

    @Test
    void RLE_058_refuse_fails_closed_with_function_changed_until_a_new_version_is_approved() {
        RuleEngines before = engines(segmentOf("2027.1"));
        publish(before, CARD_FEE);

        RuleEngines refusing = engines(EngineSettings.defaults(), HostFunctions.VersionChange.REFUSE,
                segmentOf("2027.2"));
        caller.become("bank-a", "teller-01");
        DecisionResult refused = refusing.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36));

        assertThat(refused.isError()).isTrue();
        assertThat(((DecisionResult.Failed) refused.outcome()).detail()).startsWith("FUNCTION_CHANGED")
                .contains("segmentOf").contains("2027.1").contains("2027.2");
        assertThat(stores.decisions.getLast().errorCode()).isEqualTo("FUNCTION_CHANGED");

        // with the label it was approved with, the same host evaluates
        RuleEngines unchanged = engines(EngineSettings.defaults(), HostFunctions.VersionChange.REFUSE,
                segmentOf("2027.1"));
        assertThat(unchanged.ruleEngine().evaluate("card-fee", customer(vnd(600_000_000L), 36)).isError()).isFalse();
    }
}
