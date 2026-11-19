# Getting Started

In about 30 minutes you add the Rule Engine to a Spring Boot service, load a loan-eligibility decision table, and evaluate it three ways: from a unit test, over HTTP, and from your own Java code.

Design preview: describes the 0.1 target. The artifacts named below are not published yet, so the steps cannot be run today.

To run the example host from a clone of the repository instead, with Docker and one command, see [Deploying with Docker Compose](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/DEPLOYING.md).

![Getting started flow: add the starter, load the table, evaluate, see the log](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/getting-started-flow.svg)

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

Generate a Spring Boot 4.x or 3.5.x project with the Web dependency (for example from `start.spring.io`, group `com.example`, artifact `loans`), or use an existing service.

## 2. Add the starters

In `pom.xml`:

```xml
<dependencies>
  <dependency>
    <groupId>io.github.dhluong90.bpm</groupId>
    <artifactId>rule-engine-spring-boot-starter</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>io.github.dhluong90.bpm</groupId>
    <artifactId>rule-engine-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

The starter runs its own Flyway migrations in your database; its tables are always named `rle_*`. There is nothing else to install.

The engine reads definitions and REST bodies with its own JSON library (Jackson 3, private to the engine). On a Spring Boot 3.5 host it sits next to your Jackson 2 without changing it. Your `spring.jackson.*` settings apply to your own endpoints only; engine endpoints always use camelCase names, ISO 8601 dates and the money object of [[Rule Engine Decision API]].

## 3. Configure

`src/main/resources/application.yaml`:

```yaml
spring:
  profiles:
    active: dev                             # development only; see the note below
  datasource:
    url: jdbc:postgresql://localhost:5432/loans
    username: postgres
    password: horus

horus:
  bpm:
    rules:
      definitions: classpath:rules/**/*.yaml
      definitions-tenant: demo
      definitions-auto-publish: true        # dev profile only; see the note below
    tenancy:                                # shared by both engines, so under horus.bpm
      dev-tenant: demo                      # dev profile only; every call without a tenant runs as tenant demo
```

`definitions-auto-publish: true` publishes the files you load at start-up without the maker-checker approval, and `tenancy.dev-tenant: demo` runs every call as tenant `demo` without an identity provider. The starter accepts both only in the `dev` profile and refuses to start with them anywhere else. Outside `dev` the tenant comes from the signed-in user ([[Multi-Tenancy and Security]]). In every other environment a version becomes effective only through the steps in [[Rule Engine Versioning and Publishing]]. All properties are listed in [[Rule Engine Configuration]].

## 4. Write the decision table

`src/main/resources/rules/loan-eligibility.yaml`:

```yaml
schemaVersion: 1
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
    then: { eligible: false, reason: "'UNDER_AGE'" }
  - id: R2
    when: { income: "< 5000000 VND" }
    then: { eligible: false, reason: "'LOW_INCOME'" }
  - id: R3
    when: { country: "not in ['VN', 'SG', 'ID']" }
    then: { eligible: false, reason: "'UNSUPPORTED_COUNTRY'" }
  - id: R4
    when: {}
    then: { eligible: true, reason: "'OK'" }
```

Every `then` value is an expression, so a text output needs inner quotes: `"'UNDER_AGE'"` is the text `UNDER_AGE`, while a bare `"UNDER_AGE"` would be read as the name of an input. `schemaVersion: 1` names the definition format. Rows are checked top to bottom and the first match wins (`hitPolicy: first`). The empty `when: {}` on R4 matches everything, so R4 is the default. The full format is in [[Rule Engine Authoring Decision Tables]].

## 5. Evaluate from a unit test

This step needs no database. `src/test/java/com/example/loans/LoanEligibilityTest.java`:

```java
package com.example.loans;

import io.github.dhluong90.bpm.rules.test.RuleSetTest;
import io.github.dhluong90.bpm.rules.test.RuleSetUnderTest;
import io.github.dhluong90.bpm.rules.api.Money;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@RuleSetTest("rules/loan-eligibility.yaml")
class LoanEligibilityTest {

  @Test
  void adult_in_vietnam_with_income_is_eligible(RuleSetUnderTest rules) {
    var r = rules.evaluate(Map.of("age", 25, "income", Money.ofMinor("VND", 8_000_000), "country", "VN"));
    assertThat(r.matchedRuleIds()).containsExactly("R4");
    assertThat(r.output("eligible", Boolean.class)).isTrue();
  }

  @Test
  void minor_is_rejected_before_income_is_checked(RuleSetUnderTest rules) {
    var r = rules.evaluate(Map.of("age", 17, "income", Money.ofMinor("VND", 8_000_000), "country", "VN"));
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
        "income", Money.ofMinor("VND", a.monthlyIncomeMinorUnits()),
        "country", a.country()));
    return r.output("eligible", Boolean.class);
  }
}
```

The decision is written to the decision log in your transaction. Both the HTTP call and this call appear in [[Rule Engine Decision Log and Audit]] with their matched rule ids.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `404 NO_EFFECTIVE_VERSION` | The file was loaded as a draft but not published, or it was loaded under another tenant | Check `definitions-auto-publish: true`, and that `definitions-tenant` equals `tenancy.dev-tenant`; or publish through [[Rule Engine Versioning and Publishing]] |
| `400 INPUT_INVALID` | An input is missing or has the wrong type, for example `income` sent as a number instead of `{ "currency", "amount" }` | Match the `inputs` declared in the table |
| `401` or `403` on the curl call | No principal, or the principal lacks `rules:evaluate` | Pass credentials of a user with the permission; see [[Rule Engine Configuration]] |
| Flyway fails at start-up | The database user cannot create tables | Grant `CREATE` on the schema, or set `migrate-on-start: false` and run the migrations yourself |
| The host's own Flyway fails with "Found non-empty schema(s) ... but no schema history table" | The host added Flyway after the engine's `rle_*` tables existed | See "Install order" below |

## Install order

The engine keeps its migrations in `rle_schema_history` and never reads or writes the host's `flyway_schema_history`.

| Situation | What happens | What you do |
|---|---|---|
| New service, host Flyway and engine together | The host's Flyway runs first, then the engine records a baseline at version 0 and applies its migrations | Nothing |
| Existing database with host tables, engine added | The engine baselines its own history at version 0 below its first migration, so every engine migration runs | Nothing |
| Engine tables exist, host adds Flyway later | Flyway refuses a schema that is not empty but has no history table | For that first run set `spring.flyway.baseline-on-migrate=true` and `spring.flyway.baseline-version=0`. With Spring's default baseline version `1`, Flyway would record your own `V1` as the baseline and never run it |

## Next

- Change the table without a deployment: [[Rule Engine Versioning and Publishing]].
- Check a change before approving it: [[Rule Engine Simulation and Testing]].
- See decisions in a UI: [[Cockpit]].
- Understand the terms: [[Concepts]].
