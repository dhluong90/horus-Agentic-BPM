package io.github.dhluong90.bpm.rules.test.fixture;

import io.github.dhluong90.bpm.rules.test.RuleSetTest;
import io.github.dhluong90.bpm.rules.test.RuleSetUnderTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A fixture the coverage tests run through the launcher: one evaluation matches K1 and K2 together, which is
 * UNIQUE_HIT_VIOLATION, so it covers nothing.
 */
@RuleSetTest("rules/kyc-route.yaml")
public class KycRouteFixture {

    @Test
    void an_overlap_is_a_failed_evaluation(RuleSetUnderTest rules) {
        rules.evaluate(Map.of("age", 30, "country", "VN"));
    }
}
