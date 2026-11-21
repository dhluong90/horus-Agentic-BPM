package io.github.dhluong90.bpm.rules.test.fixture;

import io.github.dhluong90.bpm.rules.test.RuleSetTest;
import io.github.dhluong90.bpm.rules.test.RuleSetUnderTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A fixture whose file fails import: the class fails before this test runs. */
@RuleSetTest("rules/broken.yaml")
public class BrokenRuleSetFixture {

    @Test
    void never_runs(RuleSetUnderTest rules) {
        rules.evaluate(Map.of("tier", "GOLD"));
    }
}
