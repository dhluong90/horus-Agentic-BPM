package io.github.dhluong90.bpm.expr.languages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException;
import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.spi.EvaluationContext;
import io.github.dhluong90.bpm.expr.spi.ExpressionLanguage;
import io.github.dhluong90.bpm.expr.spi.TypeEnvironment;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** PRE-498 and PRE-517: FEEL conditions mean what the Rule Engine's rules say, and a script budget holds. */
class ExpressionLanguagesTest {

    private static final ExpressionLanguage FEEL = ExpressionLanguages.of(ExpressionLanguages.FEEL_ID);

    private static boolean holds(ExpressionLanguage language, String source, Map<String, Object> variables) {
        return language.compile(source, TypeEnvironment.untyped()).holds(EvaluationContext.of(variables, null));
    }

    @Test
    void PRE_254_feel_chooses_the_branch_of_a_gold_loan_over_1000() {
        String condition = "amount > 1000 and tier = \"gold\"";

        assertThat(holds(FEEL, condition, Map.of("amount", new BigDecimal("5000"), "tier", "gold"))).isTrue();
        assertThat(holds(FEEL, condition, Map.of("amount", new BigDecimal("1000"), "tier", "gold"))).isFalse();
        assertThat(holds(FEEL, condition, Map.of("amount", new BigDecimal("5000"), "tier", "silver"))).isFalse();
    }

    @Test
    void PRE_255_null_follows_feel_three_valued_logic_and_only_true_holds() {
        Map<String, Object> noTier = new HashMap<>();
        noTier.put("amount", new BigDecimal("5000"));
        noTier.put("tier", null);

        assertThat(holds(FEEL, "amount > 1000 and tier = \"gold\"", noTier)).isFalse();
        assertThat(holds(FEEL, "tier != \"gold\"", noTier)).isTrue();
        assertThat(holds(FEEL, "tier = null", noTier)).isTrue();
        // false and null is false (so not() of it holds); true and null is null, which does not hold
        assertThat(holds(FEEL, "not(amount < 1000 and tier > \"a\")", noTier)).isTrue();
        assertThat(holds(FEEL, "amount > 1000 and tier > \"a\"", noTier)).isFalse();
    }

    @Test
    void PRE_255_money_compares_within_one_currency_and_a_mismatch_is_an_error_not_false() {
        Map<String, Object> vnd = Map.of("balance", Money.ofMinor("VND", 150_000_000));

        assertThat(holds(FEEL, "balance >= money(100000000, \"VND\")", vnd)).isTrue();
        assertThatThrownBy(() -> holds(FEEL, "balance >= money(100, \"USD\")", vnd))
                .isInstanceOf(ExpressionEvaluationException.class);
    }

    @Test
    void PRE_254_list_membership_and_dates_read_as_in_feel() {
        assertThat(holds(FEEL, "country in [\"VN\", \"SG\"]", Map.of("country", "SG"))).isTrue();
        assertThat(holds(FEEL, "country in [\"VN\", \"SG\"]", Map.of("country", "US"))).isFalse();
        assertThat(holds(FEEL, "opened < date(\"2027-01-01\")", Map.of("opened", java.time.LocalDate.parse("2026-12-31"))))
                .isTrue();
    }

    @Test
    void PRE_256_a_condition_in_v1_spelling_is_a_problem_in_feel_with_a_hint_and_a_position() {
        assertThatThrownBy(() -> FEEL.compile("tier == \"gold\"", TypeEnvironment.untyped()))
                .isInstanceOfSatisfying(ExpressionInvalidException.class, e -> {
                    assertThat(e.getMessage()).contains("FEEL equality is written =");
                    assertThat(e.position()).isEqualTo(5);
                });
        assertThatThrownBy(() -> FEEL.compile("tier = 'gold'", TypeEnvironment.untyped()))
                .hasMessageContaining("double quotes");
        assertThatThrownBy(() -> FEEL.compile("tier is null", TypeEnvironment.untyped()))
                .isInstanceOf(ExpressionInvalidException.class);
        assertThatThrownBy(() -> FEEL.compile("java.lang.Runtime.exit(1)", TypeEnvironment.untyped()))
                .isInstanceOf(ExpressionInvalidException.class);
    }

    @Test
    void PRE_256_the_v1_language_still_reads_its_own_spelling_and_refuses_feels() {
        ExpressionLanguage v1 = ExpressionLanguages.of(ExpressionLanguages.V1_ID);

        assertThat(holds(v1, "amount > 1000 and tier == 'gold'", Map.of("amount", new BigDecimal("5000"), "tier", "gold")))
                .isTrue();
        assertThatThrownBy(() -> v1.compile("tier = \"gold\"", TypeEnvironment.untyped()))
                .isInstanceOf(ExpressionInvalidException.class);
    }

    @Test
    void PRE_256_the_same_meaning_in_either_language_has_one_normal_form() {
        String feel = ExpressionLanguages.normalForm(ExpressionLanguages.FEEL_ID,
                "amount > 1000 and (tier = \"gold\" or tier = \"it's\") and balance >= money(12.5, \"USD\")");
        String v1 = ExpressionLanguages.normalForm(ExpressionLanguages.V1_ID,
                "amount>1000 and (tier=='gold' or tier=='it\\'s') and balance >= 12.50 USD");

        assertThat(feel).isEqualTo(v1)
                .isEqualTo("amount > 1000 and (tier == 'gold' or tier == 'it\\'s') and balance >= 12.50 USD");
        // a different meaning is a different text: and binds tighter than or
        assertThat(ExpressionLanguages.normalForm(ExpressionLanguages.FEEL_ID, "(a = 1 or b = 2) and c = 3"))
                .isNotEqualTo(ExpressionLanguages.normalForm(ExpressionLanguages.FEEL_ID, "a = 1 or b = 2 and c = 3"));
    }

    @Test
    void PRE_256_every_dmn_feel_uri_names_feel_and_nothing_else_does() {
        assertThat(ExpressionLanguages.isFeel("https://www.omg.org/spec/DMN/20230324/FEEL/")).isTrue();
        assertThat(ExpressionLanguages.isFeel("http://www.omg.org/spec/DMN/20180521/FEEL/")).isTrue();
        assertThat(ExpressionLanguages.supports("http://www.w3.org/1999/XPath")).isFalse();
        assertThatThrownBy(() -> ExpressionLanguages.of("http://www.w3.org/1999/XPath"))
                .hasMessageContaining("http://www.w3.org/1999/XPath");
    }

    @Test
    void PRE_257_a_budgeted_language_stops_a_script_past_its_step_budget_in_both_languages() {
        String sum = "1" + " + 1".repeat(40);

        for (String id : new String[] {ExpressionLanguages.V1_ID, ExpressionLanguages.FEEL_ID}) {
            ExpressionLanguage tight = ExpressionLanguages.withBudget(id, 20, 1000);
            ExpressionLanguage roomy = ExpressionLanguages.withBudget(id, 500, 1000);

            assertThat(roomy.compile(sum, TypeEnvironment.untyped()).evaluate(EvaluationContext.of(Map.of(), null)))
                    .isEqualTo(new BigDecimal("41"));
            assertThatThrownBy(() -> tight.compile(sum, TypeEnvironment.untyped())
                    .evaluate(EvaluationContext.of(Map.of(), null)))
                    .isInstanceOf(ExpressionEvaluationException.class)
                    .hasMessageContaining("EVALUATION_BUDGET_EXCEEDED");
            assertThat(tight.id()).isEqualTo(id);
        }
    }

    @Test
    void PRE_257_a_bound_out_of_range_or_an_unknown_language_is_refused() {
        assertThatThrownBy(() -> ExpressionLanguages.withBudget(ExpressionLanguages.V1_ID, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExpressionLanguages.withBudget("javascript", 100, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
