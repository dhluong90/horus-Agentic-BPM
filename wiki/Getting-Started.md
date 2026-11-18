# Getting Started

Ten minutes from an empty Spring Boot service to a first decision. _Status: design preview; the starter is not published yet._

## 1. Add the starter
```xml
<dependency>
  <groupId>com.horus.bpm</groupId>
  <artifactId>rule-engine-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```
Requirements: Java 21, Spring Boot 3.3+, PostgreSQL 14+. The starter registers its Flyway migrations under the schema prefix `rle_` in your application's database. Nothing else to install.

## 2. Configure
```yaml
horus:
  bpm:
    rules:
      enabled: true
      schema-prefix: rle_
      definitions: classpath:rules/**/*.yaml   # optional: load rule sets from files at start-up
      decision-log:
        enabled: true
```

## 3. Write a decision table
`src/main/resources/rules/loan-eligibility.yaml`
```yaml
ruleSet: loan-eligibility
version: 1
hitPolicy: first
inputs:
  - { name: age,     type: integer }
  - { name: income,  type: money }
  - { name: country, type: string }
outputs:
  - { name: eligible, type: boolean }
  - { name: reason,   type: string }
rules:
  - when: { age: "< 18" }
    then: { eligible: false, reason: "UNDER_AGE" }
  - when: { income: "< 5000000 VND" }
    then: { eligible: false, reason: "LOW_INCOME" }
  - when: { country: "not in ['VN', 'SG', 'ID']" }
    then: { eligible: false, reason: "UNSUPPORTED_COUNTRY" }
  - when: {}
    then: { eligible: true, reason: "OK" }
```

## 4. Evaluate from Java
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

## 5. Or evaluate over HTTP
```
POST /api/bpm/rules/decisions/loan-eligibility:evaluate
{ "inputs": { "age": 25, "income": { "currency": "VND", "amount": 8000000 }, "country": "VN" } }

200 OK
{ "ruleSet": "loan-eligibility", "version": 1, "outputs": { "eligible": true, "reason": "OK" },
  "matchedRules": [4], "decisionId": "01J...", "evaluatedAt": "2026-11-18T09:12:44Z" }
```

## 6. Look at it in the cockpit
Add `cockpit-spring-boot-starter` (or point a standalone cockpit at your service) and open `/bpm/cockpit`. You will see the rule set, its version, and every decision taken with its inputs and matched rules. See [[Cockpit]].

## Next
[[Concepts]] explains the lifecycle; [[Rule Engine Versioning and Publishing]] shows how a business user changes the table without a deployment.
