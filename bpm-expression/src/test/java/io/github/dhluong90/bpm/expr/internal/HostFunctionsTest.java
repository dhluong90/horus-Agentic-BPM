package io.github.dhluong90.bpm.expr.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.spi.ExpressionFunction;
import io.github.dhluong90.bpm.expr.spi.FunctionLibrary;
import io.github.dhluong90.bpm.expr.spi.ValueType;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Host functions in the parser, the evaluator and the step budget (RLE-055, RLE-057). */
class HostFunctionsTest {

    private static final Money PRIORITY_BALANCE = Money.ofMinor("VND", 600_000_000L);
    private final AtomicInteger calls = new AtomicInteger();

    private ExpressionFunction segmentOf(int stepCost) {
        return new ExpressionFunction("segmentOf", List.of(ValueType.MONEY, ValueType.NUMBER), ValueType.STRING, "2027.1",
                stepCost, arguments -> {
                    calls.incrementAndGet();
                    Money balance = (Money) arguments.get(0);
                    BigDecimal tenure = (BigDecimal) arguments.get(1);
                    return balance.minorUnits() >= 500_000_000L && tenure.intValue() >= 24 ? "PRIORITY" : "MASS";
                });
    }

    private Expr parse(String source, FunctionLibrary library) {
        return Parser.parseLocated(source, name -> switch (name) {
            case "balance" -> 0;
            case "tenureMonths" -> 1;
            default -> -1;
        }, null, library).expr();
    }

    @Test
    void a_registered_function_is_called_with_argument_values_and_its_result_is_used() {
        FunctionLibrary library = FunctionLibrary.of(List.of(segmentOf(1)));
        Expr expr = parse("segmentOf(balance, tenureMonths) == 'PRIORITY'", library);
        Evaluator evaluator = new Evaluator(java.math.RoundingMode.HALF_EVEN);

        assertThat(evaluator.forDecision().evaluate(expr, new Object[] {PRIORITY_BALANCE, new BigDecimal(36)}))
                .isEqualTo(true);
        assertThat(evaluator.forDecision().evaluate(expr, new Object[] {PRIORITY_BALANCE, new BigDecimal(12)}))
                .isEqualTo(false);
    }

    @Test
    void a_null_argument_gives_null_and_the_function_is_not_called() {
        Expr expr = parse("segmentOf(balance, tenureMonths)", FunctionLibrary.of(List.of(segmentOf(1))));

        Object result = new Evaluator(java.math.RoundingMode.HALF_EVEN).forDecision()
                .evaluate(expr, new Object[] {null, new BigDecimal(36)});

        assertThat(result).isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    void an_unregistered_name_is_still_a_forbidden_construct() {
        assertThatThrownBy(() -> parse("riskScore(balance)", FunctionLibrary.of(List.of(segmentOf(1)))))
                .isInstanceOfSatisfying(ExpressionException.class, e -> {
                    assertThat(e.error()).isEqualTo(ExpressionError.FORBIDDEN_CONSTRUCT);
                    assertThat(e.problem()).contains("Unknown function 'riskScore'");
                });
    }

    @Test
    void a_call_with_the_wrong_number_of_arguments_is_a_type_mismatch_naming_the_signature() {
        assertThatThrownBy(() -> parse("segmentOf(balance)", FunctionLibrary.of(List.of(segmentOf(1)))))
                .isInstanceOfSatisfying(ExpressionException.class, e -> {
                    assertThat(e.error()).isEqualTo(ExpressionError.TYPE_MISMATCH);
                    assertThat(e.problem()).contains("takes 2 arguments (money, number), found 1");
                });
    }

    @Test
    void the_declared_step_cost_counts_against_the_step_budget_and_the_worst_case() {
        Expr expr = parse("segmentOf(balance, tenureMonths)", FunctionLibrary.of(List.of(segmentOf(500))));
        Evaluator evaluator = new Evaluator(java.math.RoundingMode.HALF_EVEN, Evaluator.STRING_MAX_LENGTH,
                RegexLimits.defaults(), new EvaluationLimits(400, EvaluationLimits.TIMEOUT_MILLIS));

        assertThat(EvaluationLimits.worstCase(expr)).isEqualTo(502);
        assertThatThrownBy(() -> evaluator.forDecision().evaluate(expr, new Object[] {PRIORITY_BALANCE, new BigDecimal(36)}))
                .isInstanceOfSatisfying(EvaluationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(EvaluationLimits.EVALUATION_BUDGET_EXCEEDED);
                    assertThat(e.limit()).isEqualTo("STEPS");
                });
        assertThat(calls).hasValue(0);
    }

    @Test
    void an_exception_is_function_failed_without_the_exception_text() {
        ExpressionFunction failing = ExpressionFunction.of("segmentOf", "1", ValueType.STRING, arguments -> {
            throw new IllegalStateException("upstream timeout for 970412345678");
        }, ValueType.MONEY, ValueType.NUMBER);
        Expr expr = parse("segmentOf(balance, tenureMonths)", FunctionLibrary.of(List.of(failing)));

        assertThatThrownBy(() -> new Evaluator(java.math.RoundingMode.HALF_EVEN).forDecision()
                .evaluate(expr, new Object[] {PRIORITY_BALANCE, new BigDecimal(36)}))
                .isInstanceOfSatisfying(EvaluationException.class, e -> {
                    assertThat(e.reason()).isEqualTo("FUNCTION_FAILED");
                    assertThat(e.getMessage()).contains("segmentOf").doesNotContain("upstream").doesNotContain("9704");
                    assertThat(e.getCause()).hasMessageContaining("upstream timeout");
                });
    }

    @Test
    void a_result_of_another_type_is_function_failed() {
        ExpressionFunction wrong = ExpressionFunction.of("segmentOf", "1", ValueType.STRING, arguments -> 7,
                ValueType.MONEY, ValueType.NUMBER);
        Expr expr = parse("segmentOf(balance, tenureMonths)", FunctionLibrary.of(List.of(wrong)));

        assertThatThrownBy(() -> new Evaluator(java.math.RoundingMode.HALF_EVEN).forDecision()
                .evaluate(expr, new Object[] {PRIORITY_BALANCE, new BigDecimal(36)}))
                .isInstanceOfSatisfying(EvaluationException.class,
                        e -> assertThat(e.reason()).isEqualTo("FUNCTION_FAILED"));
    }

    @Test
    void the_library_refuses_duplicates_built_in_names_and_bad_names_naming_the_function() {
        assertThatThrownBy(() -> FunctionLibrary.of(List.of(segmentOf(1), segmentOf(1))))
                .hasMessage("Function 'segmentOf' is registered twice");
        assertThatThrownBy(() -> FunctionLibrary.of(List.of(
                ExpressionFunction.of("round", "1", ValueType.NUMBER, a -> a.get(0), ValueType.NUMBER))))
                .hasMessageContaining("Function 'round'").hasMessageContaining("built-in");
        assertThatThrownBy(() -> ExpressionFunction.of("Segment-Of", "1", ValueType.NUMBER, a -> a.get(0),
                ValueType.NUMBER)).hasMessageContaining("Function 'Segment-Of' has an invalid name");
    }
}
