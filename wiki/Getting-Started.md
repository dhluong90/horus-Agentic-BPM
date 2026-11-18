# Getting Started

In about 30 minutes you add the Rule Engine to a Spring Boot service, load a loan-eligibility decision table, and evaluate it three ways: from a unit test, over HTTP, and from your own Java code.

Design preview: describes the 0.1 target. The artifacts named below are not published yet, so the steps cannot be run today.

## Before you start

| You need | Version | Check |
|---|---|---|
| Java | 21 | `java -version` prints `21` |
| Maven | 3.9 or later | `./mvnw -v` |
| PostgreSQL | 14 or later, reachable on `localhost:5432` | `psql -h localhost -U postgres -c 'select 1'` |
| curl | any | `curl --version` |

If you have Docker and no PostgreSQL, start one:

```bash
docker run --name horus-pg -e POSTGRES_PASSWORD=horus -e POSTGRES_DB=loans -p 5432:5432 -d postgres:16
```

Supported platform versions are listed in [[Technical Requirements]] (TR-EMB-02).

## 1. Create the service

Generate a Spring Boot 3.4 project with the Web dependency (for example from `start.spring.io`, group `com.example`, artifact `loans`), or use an existing service.

## 2. Add the starters

In `pom.xml`:

```xml
<dependencies>
  <dependency>
    <groupId>com.horus.bpm</groupId>
    <artifactId>rule-engine-spring-boot-starter</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>com.horus.bpm</groupId>
    <artifactId>rule-engine-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

The starter runs its own Flyway migrations in your database under the `rle_` prefix. There is nothing else to install.

## 3. Configure

`src/main/resources/application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/loans
    username: postgres
    password: horus

horus:
  bpm:
    rules:
      definitions: classpath:rules/**/*.yaml
      definitions-auto-publish: true        # development only; see the note below
      tenancy:
        resolver: header:X-Tenant-Id        # development only; the default reads the security context
```

`definitions-auto-publish: true` publishes the files you load at start-up without the maker-checker approval. Use it only on a developer machine. In every other environment a version becomes effective only through the steps in [[Rule Engine Versioning and Publishing]]. All properties are listed in [[Rule Engine Configuration]].

## 4. Write the decision table

`src/main/resources/rules/loan-eligibility.yaml`:

```yaml
ruleSet: loan-eligibility
hitPolicy: first
inputs:
  - { name: age,     type: integer }
  - { name: income,  type: money }
  - { name: country, type: string }
outputs:
  - { name: eligible, type: boolean }
  - { name: reason,   type: string }
rules:
  - id: R1
    when: { age: "< 18" }
    then: { eligible: false, reason: "UNDER_AGE" }
  - id: R2
    when: { income: "< 5000000 VND" }
    then: { eligible: false, reason: "LOW_INCOME" }
  - id: R3
    when: { country: "not in ['VN', 'SG', 'ID']" }
    then: { eligible: false, reason: "UNSUPPORTED_COUNTRY" }
  - id: R4
    when: {}
    then: { eligible: true, reason: "OK" }
```

Rows are checked top to bottom and the first match wins (`hitPolicy: first`). The empty `when: {}` on R4 matches everything, so R4 is the default. The full format is in [[Rule Engine Authoring Decision Tables]].

## 5. Evaluate from a unit test

This step needs no database. `src/test/java/com/example/loans/LoanEligibilityTest.java`:

```java
package com.example.loans;

import com.horus.bpm.rules.test.RuleSetTest;
import com.horus.bpm.rules.test.RuleSetUnderTest;
import com.horus.bpm.rules.Money;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@RuleSetTest("rules/loan-eligibility.yaml")
class LoanEligibilityTest {

  @Test
  void adult_in_vietnam_with_income_is_eligible(RuleSetUnderTest rules) {
    var r = rules.evaluate(Map.of("age", 25, "income", Money.of("VND", 8_000_000), "country", "VN"));
    assertThat(r.matchedRuleIds()).containsExactly("R4");
    assertThat(r.output("eligible", Boolean.class)).isTrue();
  }

  @Test
  void minor_is_rejected_before_income_is_checked(RuleSetUnderTest rules) {
    var r = rules.evaluate(Map.of("age", 17, "income", Money.of("VND", 8_000_000), "country", "VN"));
    assertThat(r.matchedRuleIds()).containsExactly("R1");
    assertThat(r.output("reason", String.class)).isEqualTo("UNDER_AGE");
  }
}
```

Run it:

```bash
./mvnw -q test
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

## 6. Evaluate over HTTP

Start the service:

```bash
./mvnw spring-boot:run
```

Expected: the application starts on port 8080 and the log shows Flyway applying the `rle_` migrations. Confirm the tables:

```bash
psql -h localhost -U postgres -d loans -c '\dt rle_*'
```

Expected: the `rle_*` tables listed in [[Architecture]] under Storage.

Evaluate the effective version:

```bash
curl -s -X POST http://localhost:8080/api/bpm/rules/v1/decisions/loan-eligibility:evaluate \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: demo' \
  -d '{ "inputs": { "age": 25, "income": { "currency": "VND", "amount": 8000000 }, "country": "VN" } }'
```

Expected (`200 OK`; your `decisionId` and timestamps differ):

```json
{ "decisionId": "01JD2X8K3N4P5Q6R7S8T9V0W1X", "ruleSet": "loan-eligibility", "version": 1, "hitPolicy": "first",
  "outputs": { "eligible": true, "reason": "OK" }, "matchedRuleIds": ["R4"],
  "evaluatedAt": "2026-11-18T09:12:44Z", "durationMicros": 85 }
```

Change `"age": 25` to `"age": 17` and run the command again. Expected: `"matchedRuleIds": ["R1"]` and `"reason": "UNDER_AGE"`.

Every REST call requires an authenticated principal with the `rules:evaluate` permission. If your project includes Spring Security, pass credentials of a user that holds that permission (for example `-u dev:dev`). Fields, headers and error codes are in [[Rule Engine Decision API]].

## 7. Evaluate from your own code

```java
@Service
class LoanService {
  private final RuleEngine rules;
  LoanService(RuleEngine rules) { this.rules = rules; }

  boolean isEligible(Applicant a) {
    DecisionResult r = rules.evaluate("loan-eligibility", DecisionInput.of(
        "age", a.age(),
        "income", Money.of("VND", a.monthlyIncomeMinorUnits()),
        "country", a.country()));
    return r.output("eligible", Boolean.class);
  }
}
```

The decision is written to the decision log in your transaction. Both the HTTP call and this call appear in [[Rule Engine Decision Log and Audit]] with their matched rule ids.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `404 NO_EFFECTIVE_VERSION` | The file was loaded as a draft but not published, or the tenant in `X-Tenant-Id` has no published version | Check `definitions-auto-publish: true` and the tenant header; or publish through [[Rule Engine Versioning and Publishing]] |
| `400 INPUT_INVALID` | An input is missing or has the wrong type, for example `income` sent as a number instead of `{ "currency", "amount" }` | Match the `inputs` declared in the table |
| `401` or `403` on the curl call | No principal, or the principal lacks `rules:evaluate` | Pass credentials of a user with the permission; see [[Rule Engine Configuration]] |
| Flyway fails at start-up | The database user cannot create tables | Grant `CREATE` on the schema, or set `migrate-on-start: false` and run the migrations yourself |

## Next

- Change the table without a deployment: [[Rule Engine Versioning and Publishing]].
- Check a change before approving it: [[Rule Engine Simulation and Testing]].
- See decisions in a UI: [[Cockpit]].
- Understand the terms: [[Concepts]].
