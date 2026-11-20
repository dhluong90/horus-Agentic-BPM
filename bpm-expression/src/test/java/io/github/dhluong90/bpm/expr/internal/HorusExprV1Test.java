package io.github.dhluong90.bpm.expr.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException;
import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;
import io.github.dhluong90.bpm.expr.api.ExpressionValues;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.spi.CompiledExpression;
import io.github.dhluong90.bpm.expr.spi.EvaluationContext;
import io.github.dhluong90.bpm.expr.spi.EvaluationProfile;
import io.github.dhluong90.bpm.expr.spi.ExpressionLanguage;
import io.github.dhluong90.bpm.expr.spi.TypeEnvironment;
import io.github.dhluong90.bpm.expr.spi.ValueType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code urn:horus-bpm:expr:v1} through the SPI, with the card fee and loan examples of the rule cards. Declared
 * environments are how a rule set compiles; untyped ones are how a process condition compiles.
 */
class HorusExprV1Test {

    private final ExpressionLanguage language = new HorusExprV1();
    private static final Instant CLOCK = Instant.parse("2027-01-31T22:50:00Z");

    @Test
    void the_language_has_its_id_and_compiles_once_to_a_reusable_expression() {
        CompiledExpression fee = language.compile("amount * 0.01",
                TypeEnvironment.declared(Map.of("amount", ValueType.MONEY)));

        assertThat(language.id()).isEqualTo("urn:horus-bpm:expr:v1");
        assertThat(fee.profile()).isEqualTo(EvaluationProfile.COMPILED);
        assertThat(fee.source()).isEqualTo("amount * 0.01");
        assertThat(fee.evaluate(EvaluationContext.of(Map.of("amount", Money.ofMinor("VND", 5_000_000)), null)))
                .isEqualTo(Money.ofMinor("VND", 50_000));
        assertThat(fee.evaluate(EvaluationContext.of(Map.of("amount", Money.ofMinor("VND", 1_250_000)), null)))
                .isEqualTo(Money.ofMinor("VND", 12_500));
    }

    @Test
    void a_declared_environment_refuses_an_unknown_name_with_its_position() {
        TypeEnvironment loan = TypeEnvironment.declared(Map.of("income", ValueType.MONEY));

        assertThatThrownBy(() -> language.compile("income > 5000000 VND and tier == 'GOLD'", loan))
                .isInstanceOf(ExpressionInvalidException.class)
                .satisfies(e -> assertThat(((ExpressionInvalidException) e).position()).isEqualTo(25));
        assertThatThrownBy(() -> language.compile("customer.tier == 'GOLD'", loan))
                .isInstanceOf(ExpressionInvalidException.class);
    }

    @Test
    void RLE_006_null_follows_feel_three_valued_logic_and_only_true_holds() {
        Map<String, Object> nulls = new HashMap<>();
        nulls.put("income", null);
        nulls.put("tier", null);
        EvaluationContext context = EvaluationContext.of(nulls, null);

        assertThat(holds("income < 5000000 VND", context)).isFalse();
        assertThat(holds("income is null", context)).isTrue();
        assertThat(holds("tier != 'GOLD'", context)).isTrue();
        assertThat(holds("not(income < 5000000 VND)", context)).isFalse();
        assertThat(holds("income < 5000000 VND or tier is null", context)).isTrue();
        assertThat(language.compile("income < 5000000 VND and tier is null", TypeEnvironment.untyped())
                .evaluate(context)).isNull();
    }

    @Test
    void an_untyped_environment_reads_dotted_paths_into_nested_maps() {
        EvaluationContext context = EvaluationContext.of(Map.of("customer", Map.of("tier", "GOLD",
                "limit", Map.of("amount", 200_000_000L))), null);

        assertThat(holds("customer.tier == 'GOLD'", context)).isTrue();
        assertThat(holds("customer.limit.amount >= 100000000", context)).isTrue();
        assertThat(holds("customer.missing.value is null", context)).isTrue();
    }

    @Test
    void an_untyped_environment_refuses_values_of_the_wrong_type_instead_of_not_holding() {
        EvaluationContext context = EvaluationContext.of(Map.of("amount", new BigDecimal("0.1"),
                "fee", Money.ofMinor("VND", 150_000_000), "tier", "GOLD"), null);

        assertThat(holds("amount == 0.10", context)).isTrue();
        assertThat(holds("fee >= 100000000 VND", context)).isTrue();
        for (String wrong : List.of("fee > 1", "amount == 'x'", "fee > 0 USD", "tier and true", "not tier",
                "tier + 1 == 2")) {
            assertThatThrownBy(() -> holds(wrong, context)).as(wrong)
                    .isInstanceOf(ExpressionEvaluationException.class);
        }
    }

    @Test
    void a_declared_environment_keeps_the_rule_engine_semantics_where_a_type_mismatch_is_null() {
        CompiledExpression mixed = language.compile("tier == 5",
                TypeEnvironment.declared(Map.of("tier", ValueType.STRING)));

        assertThat(mixed.evaluate(EvaluationContext.of(Map.of("tier", "GOLD"), null))).isNull();
    }

    @Test
    void TR_DET_05_today_and_now_read_the_context_clock_only() {
        CompiledExpression today = language.compile("today()", TypeEnvironment.untyped());
        CompiledExpression now = language.compile("now()", TypeEnvironment.declared(Map.of()));

        assertThat(today.resultType()).isEqualTo(ValueType.DATE);
        assertThat(today.evaluate(EvaluationContext.of(Map.of(), CLOCK))).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(now.evaluate(EvaluationContext.of(Map.of(), CLOCK))).isEqualTo(CLOCK);
        assertThat(new HorusExprV1(new Evaluator(java.math.RoundingMode.HALF_EVEN), ZoneId.of("Asia/Ho_Chi_Minh"))
                .compile("today()", TypeEnvironment.untyped()).evaluate(EvaluationContext.of(Map.of(), CLOCK)))
                .isEqualTo(LocalDate.of(2027, 2, 1));
        assertThatThrownBy(() -> today.evaluate(EvaluationContext.of(Map.of(), null)))
                .isInstanceOf(ExpressionEvaluationException.class);
    }

    @Test
    void the_value_mapping_turns_integers_into_decimals_and_refuses_binary_floating_point() {
        assertThat(ExpressionValues.toExpressionValue(42)).isEqualTo(new BigDecimal("42"));
        assertThat(ExpressionValues.toExpressionValue(42L)).isEqualTo(new BigDecimal("42"));
        assertThat(ExpressionValues.toExpressionValue(List.of(1, "a"))).isEqualTo(List.of(new BigDecimal("1"), "a"));
        assertThatThrownBy(() -> ExpressionValues.toExpressionValue(0.1d))
                .isInstanceOf(ExpressionEvaluationException.class);
    }

    @Test
    void a_condition_must_give_a_boolean_and_a_blank_text_does_not_compile() {
        assertThatThrownBy(() -> holds("1 + 1", EvaluationContext.of(Map.of(), null)))
                .isInstanceOf(ExpressionEvaluationException.class);
        assertThatThrownBy(() -> language.compile("  ", TypeEnvironment.untyped()))
                .isInstanceOf(ExpressionInvalidException.class);
        assertThatThrownBy(() -> language.compile("amount >", TypeEnvironment.untyped()))
                .isInstanceOf(ExpressionInvalidException.class);
    }

    private boolean holds(String source, EvaluationContext context) {
        return language.compile(source, TypeEnvironment.untyped()).holds(context);
    }
}
