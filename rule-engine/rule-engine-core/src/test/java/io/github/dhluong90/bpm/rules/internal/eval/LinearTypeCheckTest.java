package io.github.dhluong90.bpm.rules.internal.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.expr.internal.Expr;
import io.github.dhluong90.bpm.expr.internal.Parser;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
import io.github.dhluong90.bpm.rules.internal.model.ValueType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RLE-004 (#359): the cell type check works out the type of each node once, so checking an import costs time in
 * proportion to its size. The work is counted, never timed: a 5,000-rule import whose cells go 60 operators deep.
 */
class LinearTypeCheckTest {

    static final List<InputClause> INPUTS = List.of(
            new InputClause("txCount", ValueType.parse("integer"), false),
            new InputClause("balance", ValueType.parse("money"), false));
    static final Map<String, Integer> SLOTS = Map.of("txCount", 0, "balance", 1);

    static int nodes(Expr expr) {
        int count = 1;
        for (Expr child : ConditionTypes.childrenOf(expr)) {
            count += nodes(child);
        }
        return count;
    }

    static Expr parse(String text) {
        return Parser.parseLocated(text, name -> SLOTS.getOrDefault(name, -1), null).expr();
    }

    /** {@code txCount + 1 + 2 ... > 0} or {@code balance + 1 VND ... > 0 VND}, 60 operators deep. */
    static String cell(int rule) {
        StringBuilder text = new StringBuilder(rule % 2 == 0 ? "txCount" : "balance");
        for (int i = 1; i <= 60; i++) {
            text.append(" + ").append(i).append(rule % 2 == 0 ? "" : " VND");
        }
        return text.append(rule % 2 == 0 ? " > 0" : " > 0 VND").toString();
    }

    @Test
    void RLE_004_a_5000_rule_import_with_64_level_cells_works_out_each_nodes_type_once() {
        // Given 5,000 rules, each with a deep condition cell
        long totalNodes = 0;
        long totalKinds = 0;
        for (int rule = 0; rule < 5_000; rule++) {
            Expr expr = parse(cell(rule));
            ConditionTypes.Scope scope = new ConditionTypes.Scope(INPUTS);

            // When the cell is checked as the import checks it: operands, then the condition
            assertThat(ConditionTypes.operandProblem(expr, scope)).as("rule %d operands", rule).isNull();
            assertThat(ConditionTypes.conditionProblem(expr, scope)).as("rule %d condition", rule).isNull();

            // Then no node is typed twice, so the work is at most the size of the cell
            int size = nodes(expr);
            assertThat(scope.kindEvaluations()).as("rule %d", rule).isLessThanOrEqualTo(size);
            totalNodes += size;
            totalKinds += scope.kindEvaluations();
        }
        // and across the import the work is linear in the node count (the quadratic check did about 60 times more)
        assertThat(totalNodes).isGreaterThan(5_000L * 120);
        assertThat(totalKinds).isLessThanOrEqualTo(totalNodes);
    }
}
