package io.github.dhluong90.bpm.rules.test.fixture;

import io.github.dhluong90.bpm.rules.test.RuleSetTest;
import io.github.dhluong90.bpm.rules.test.RuleSetUnderTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A fixture the coverage tests run through the launcher: it matches R2 of card-fee and never R3. */
@RuleSetTest("rules/card-fee.yaml")
public class CardFeeActiveFixture {

    @Test
    void active_silver_cards_pay_the_reduced_fee(RuleSetUnderTest rules) {
        rules.evaluate(Map.of("tier", "SILVER", "txCount", 5));
    }
}
