# Guide: testing your processes and rules

Test a rule set in plain JUnit with no database. Test a process against a running host with the same calls the walkthrough uses.

## Rule sets: `@RuleSetTest`

Add `rule-engine-test` (test scope), put the YAML on the test classpath and write:

```java
@RuleSetTest("rules/loan-eligibility.yaml")
class LoanEligibilityTest {

    @Test
    void adult_with_income_is_eligible(RuleSetUnderTest rules) {
        var r = rules.evaluate(Map.of("age", 25, "income", Money.ofMinor("VND", 8_000_000)));
        assertThat(r.matchedRuleIds()).containsExactly("R3");
        assertThat(r.output("eligible", Boolean.class)).isTrue();
    }

    @Test
    void minor_is_declined_before_income_is_checked(RuleSetUnderTest rules) {
        var r = rules.evaluate(Map.of("age", 17, "income", Money.ofMinor("VND", 8_000_000)));
        assertThat(r.matchedRuleIds()).containsExactly("R1");
        assertThat(r.output("reason", String.class)).isEqualTo("UNDER_AGE");
    }
}
```

The imports are `io.github.dhluong90.bpm.rules.test.RuleSetTest`, `RuleSetUnderTest` and `io.github.dhluong90.bpm.expr.api.Money`. Run it:

```bash
mvn -B -q test -Dtest=LoanEligibilityTest
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`, and a line `Rule coverage loan-eligibility: 2 of 3 rows covered; R2 not covered`. Add a test for the income case and the coverage reaches 3 of 3. The extension also fixes the clock for date rules.

## Processes: drive a test host

There is no public in-memory kit for processes in this release. Start your service against a test database (Testcontainers, or the container from [[Getting Started: your own service|Getting-Started-Your-Own-Service]]) with the `dev` profile and assert on the REST answers:

1. Import or start-up import the BPMN (`auto-approve` on `dev`).
2. Publish the rule sets your process calls.
3. `POST /instances` with a `businessKey`, then assert `status`, `variables` and the open `tasks`.
4. Claim and complete tasks, then assert the instance is `COMPLETED`.
5. For each error path, start with bad data and assert the `code` and `reason` of the `422 STEP_FAILED`.

A start that fails before its first wait state leaves no instance, so a test can reuse its `businessKey`. Use a new `Idempotency-Key` for every command, or the replay returns the first answer.

Before approving a rule change, replay past decisions against the draft: [[Rule Engine: simulate and test|Rule-Engine-Simulation-and-Testing]].
