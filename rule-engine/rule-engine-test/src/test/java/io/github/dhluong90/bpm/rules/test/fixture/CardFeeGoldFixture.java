package io.github.dhluong90.bpm.rules.test.fixture;

import io.github.dhluong90.bpm.rules.test.RuleSetTest;
import io.github.dhluong90.bpm.rules.test.RuleSetUnderTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A fixture the coverage tests run through the launcher: it matches R1 of card-fee. Not run by the build itself. */
@RuleSetTest("rules/card-fee.yaml")
public class CardFeeGoldFixture {

    @Test
    void gold_customers_pay_nothing(RuleSetUnderTest rules) {
        rules.evaluate(Map.of("tier", "GOLD", "txCount", 0));
    }
}
