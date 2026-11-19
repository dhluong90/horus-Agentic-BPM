# Example host: Rule Engine and Process Engine

A Spring Boot 4 service that embeds both engines on its own PostgreSQL database. A teller submits a card application; a BPMN process loads the customer's profile, prices the card with the `card-fee` rule set, approves a zero fee automatically and sends any other fee to a card officer.

```mermaid
flowchart LR
    classDef task fill:#E0F2F1,stroke:#00796B,color:#16231F
    classDef human fill:#E3F2FD,stroke:#1565C0,color:#16231F
    classDef gate fill:#FFF8E1,stroke:#F9A825,color:#16231F

    S((Application received)) --> L[Load customer profile<br/>serviceTask cardProfiles.load]:::task
    L --> P[Price the card<br/>businessRuleTask card-fee]:::task
    P --> G{Fee waived?}:::gate
    G -->|pricing.fee == 0 VND| A[Approve automatically]:::task
    G -->|default| R[Officer reviews the fee<br/>userTask role:CARD_OFFICER]:::human
    R --> O{Approved?}:::gate
    O -->|review.approved == true| AP[Approve]:::task
    O -->|default| RJ[Reject]:::task
    A --> E1((Card approved))
    AP --> E1
    RJ --> E2((Card rejected))
```

The process is [`processes/card-fee-review.bpmn`](src/main/resources/processes/card-fee-review.bpmn), a BPMN 2.0 file with diagram interchange, so it opens in BPMN modellers. Engine settings are attributes and elements of the namespace `urn:horus-bpm:bpmn:extensions:v1`: `horus:handler`, `horus:ruleSet`, `horus:assignee`, `horus:resultVariable` and `horus:input`. Conditions are in `urn:horus-bpm:expr:v1`.

What the host adds:

1. Two dependencies: `rule-engine-spring-boot-starter` and `process-engine-spring-boot-starter`.
2. One configuration block, `horus.bpm` in [`application.yml`](src/main/resources/application.yml): role-to-permission mappings for both engines and the BPMN file to import.
3. Two `ServiceTaskHandler` beans, the only code the process calls: [`CardProfileHandler`](src/main/java/io/github/dhluong90/bpm/examples/ruleandprocess/CardProfileHandler.java) (`cardProfiles.load`) and [`CardDecisionHandler`](src/main/java/io/github/dhluong90/bpm/examples/ruleandprocess/CardDecisionHandler.java) (`cardApplications.decide`). The engine finds them by name, never by reflection.
4. Its own use case, [`CardApplicationService`](src/main/java/io/github/dhluong90/bpm/examples/ruleandprocess/CardApplicationService.java), which stores the application and starts the process in one `@Transactional` method.

## One transaction per call

Every step up to the first wait state runs in the caller's transaction: the host's insert, the profile query, the rule evaluation and its `rle_decision` row, the engine's `pre_*` rows. If any step fails, all of it rolls back.

```mermaid
sequenceDiagram
    autonumber
    participant T as Teller
    participant H as Host service
    participant PE as Process Engine
    participant RE as Rule Engine
    participant DB as PostgreSQL
    T->>H: POST /api/cards/applications
    H->>DB: INSERT card_application (SUBMITTED)
    H->>PE: start(card-fee-review)
    PE->>DB: lock and insert pre_instance, root scope token
    PE->>H: cardProfiles.load (same transaction)
    H->>DB: SELECT card_customer
    PE->>RE: evaluate(card-fee, tier, txCount)
    RE->>DB: INSERT rle_decision
    alt fee is 0 VND
        PE->>H: cardApplications.decide AUTO_APPROVED
        PE->>DB: tokens COMPLETED, pre_history
    else fee charged
        PE->>DB: token WAITING_USER, pre_task OPEN
    end
    H-->>T: 201, or 422 STEP_FAILED with everything rolled back
```

## Run locally

Needs JDK 21 or later and Docker.

```bash
docker run -d --name horus-pe-pg -e POSTGRES_DB=horus -e POSTGRES_USER=horus \
  -e POSTGRES_PASSWORD=change-me -p 5432:5432 postgres:16-alpine

mvn -B -pl examples/rule-and-process -am package -DskipTests      # from the repository root
SPRING_PROFILES_ACTIVE=dev HORUS_DEMO_PASSWORD=demo-pass HORUS_DB_PASSWORD=change-me \
  java -jar examples/rule-and-process/target/example-rule-and-process-0.1.0-SNAPSHOT.jar
```

| Variable | Default | Meaning |
|---|---|---|
| `HORUS_DB_URL` | `jdbc:postgresql://localhost:5432/horus` | Host database; both engines use it with their own tables |
| `HORUS_DB_USERNAME` | `horus` | Database user |
| `HORUS_DB_PASSWORD` | empty | Database password; never in a file |
| `HORUS_DEMO_PASSWORD` | empty | Password of the demo users, `dev` profile only |
| `PORT` | `8080` | HTTP port |
| `SPRING_PROFILES_ACTIVE` | none | `dev` turns on the demo users, the fixed tenant `bank-a`, the start-up import and auto-approval of the sample process |

At start-up the log shows, in order: the host's own migration, the Rule Engine schema (`rle_schema_history`), the Process Engine schema (`pre_schema_history`), the sample process imported as version 1 `ACTIVE` for `bank-a`, and the `card-fee` rule set published by two seed identities (one requests, the other approves).

```
Rule Engine schema at version 2 (2 migration(s) applied)
Process Engine schema at version 1 (1 migration(s) applied)
Process definition card-fee-review v1 ACTIVE for tenant bank-a from classpath:processes/card-fee-review.bpmn (hash 3fa54143...)
Rule set card-fee v1 PUBLISHED (requested by seed-author, approved by seed-approver)
```

## Walkthrough

Demo users on the `dev` profile, all with the password from `HORUS_DEMO_PASSWORD`, using HTTP Basic:

| User | Role | Engine permissions |
|---|---|---|
| `tuan` | `TELLER` | `processes:start`, `processes:read`, `rules:evaluate` |
| `an` | `CARD_OFFICER` | `processes:read`, `processes:task:work`, `rules:evaluate` |
| `lan` | `PRICING_AUTHOR`, `PROCESS_DEPLOYER` | `rules:read`, `rules:author`, `processes:deploy`, `processes:read` |
| `minh` | `PRICING_APPROVER` | `rules:read`, `rules:approve` |

Seed customers of `bank-a`: `C-001` (GOLD, 0 VND), `C-002` (STANDARD with 5 transactions, 10,000 VND), `C-003` (STANDARD with 1 transaction, 50,000 VND).

A gold customer is approved automatically:

```bash
P=demo-pass
curl -s -u tuan:$P -H 'Content-Type: application/json' \
  -d '{"applicationId":"APP-1001","customerId":"C-001"}' localhost:8080/api/cards/applications
# {"instanceStatus":"COMPLETED","instanceId":"01M7S2PPN8A0AHH84EQRT3N0HP","applicationId":"APP-1001"}

curl -s -u tuan:$P localhost:8080/api/cards/applications/APP-1001
# {"applicationId":"APP-1001","customerId":"C-001","status":"AUTO_APPROVED","fee":{"currency":"VND","amount":0}}
```

A charged fee waits for a card officer. The instance shows the execution tree (the root scope token and the flow token at the user task) and the open task:

```bash
curl -s -u tuan:$P -H 'Content-Type: application/json' \
  -d '{"applicationId":"APP-1002","customerId":"C-002"}' localhost:8080/api/cards/applications
# {"instanceStatus":"RUNNING","instanceId":"01M7S2PPTM395C88YQ14BX2E6F","applicationId":"APP-1002"}

curl -s -u an:$P localhost:8080/api/bpm/processes/v1/instances/01M7S2PPTM395C88YQ14BX2E6F
# {"id":"01M7S2PPTM395C88YQ14BX2E6F","definitionKey":"card-fee-review","definitionVersion":1,"businessKey":"APP-1002",
#  "status":"RUNNING","variables":{"pricing":{"fee":{"currency":"VND","amount":10000},"decisionId":"01M7S2PPTQSV0G0CEY4FYJB1S5"},...},
#  "tokens":[{"kind":"SCOPE","nodeId":"card-fee-review","status":"WAITING_SCOPE",...},
#            {"kind":"FLOW","nodeId":"officer-review","status":"WAITING_USER",...}],
#  "tasks":[{"id":"01M7S2PPTM395C88YQ14000005","nodeId":"officer-review","assignee":"role:CARD_OFFICER","status":"OPEN","lockVersion":0,...}],...}
```

Only a card officer may complete it; `If-Match` carries the task's lock version and `Idempotency-Key` makes a retry safe:

```bash
T=01M7S2PPTM395C88YQ14000005
curl -s -u tuan:$P -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{"result":{"approved":true}}' "localhost:8080/api/bpm/processes/v1/tasks/${T}:complete"
# {"status":403,"code":"PERMISSION_DENIED","detail":"Needs processes:task:work",...}

curl -s -u an:$P -H 'Content-Type: application/json' -H 'If-Match: "0"' -H 'Idempotency-Key: review-1002' \
  -d '{"result":{"approved":true}}' "localhost:8080/api/bpm/processes/v1/tasks/${T}:complete"
# {...,"status":"COMPLETED","tokens":[{"kind":"SCOPE",...,"status":"COMPLETED"},{"kind":"FLOW","nodeId":"end-approved","status":"COMPLETED"}],
#  "tasks":[{...,"status":"COMPLETED","lockVersion":1,"completedBy":"an"}],...}

curl -s -u an:$P -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{"result":{"approved":true}}' "localhost:8080/api/bpm/processes/v1/tasks/${T}:complete"
# {"status":409,"code":"VERSION_CONFLICT","detail":"Task 01M7S2PPTM395C88YQ14000005 is at lock version 1",...}

curl -s -u tuan:$P localhost:8080/api/cards/applications/APP-1002
# {"applicationId":"APP-1002","customerId":"C-002","status":"APPROVED","fee":{"currency":"VND","amount":10000}}
```

An unknown customer fails the first step, and the host's row rolls back with the engine's:

```bash
curl -s -u tuan:$P -H 'Content-Type: application/json' \
  -d '{"applicationId":"APP-1099","customerId":"C-404"}' localhost:8080/api/cards/applications
# {"status":422,"code":"STEP_FAILED","detail":"Step load-profile failed (UNHANDLED_BUSINESS_ERROR): CUSTOMER_NOT_FOUND: No customer C-404 in this tenant","nodeId":"load-profile","reason":"UNHANDLED_BUSINESS_ERROR"}

docker exec horus-pe-pg psql -U horus -d horus -tAc \
  "SELECT count(*) FROM card_application WHERE application_id='APP-1099'"   # 0
```

## Import a BPMN file

`lan` may import BPMN files. The same document again creates no new version; an unsupported element is refused with its id and line; a `DOCTYPE` is refused before anything is resolved.

```bash
curl -s -u lan:$P -H 'Content-Type: application/xml' \
  --data-binary @examples/rule-and-process/src/main/resources/processes/card-fee-review.bpmn \
  localhost:8080/api/bpm/processes/v1/definitions
# {"key":"card-fee-review","version":1,"status":"ACTIVE","definitionHash":"3fa54143...","sourceFormat":"BPMN_XML",...}

curl -s -u lan:$P -H 'Content-Type: application/xml' \
  --data-binary @process-engine/process-engine-bpmn/src/test/resources/bpmn/unsupported.bpmn \
  localhost:8080/api/bpm/processes/v1/definitions
# {"status":422,"code":"BPMN_UNSUPPORTED_ELEMENT","problems":[
#   {"element":"boundaryEvent","elementId":"be-timeout","line":6,...},
#   {"element":"inclusiveGateway","elementId":"ig-1","line":7,...},
#   {"element":"endEvent with terminateEventDefinition","elementId":"end","line":8,...}],...}
```

Supported in this release: none start and end events, `serviceTask` (local), `businessRuleTask`, `userTask`, `exclusiveGateway` with conditions and a default flow. Everything else is refused by name with the stage that adds it.

## Tests

```bash
mvn -B -pl examples/rule-and-process -am verify
```

`RuleAndProcessIT` runs on PostgreSQL in Testcontainers: the start-up import, the automatic and the officer path over HTTP, a stale completion (`PRE-003`), the rollback of host and engine rows, a decision the caller may not evaluate, and tenant isolation (a second tenant cannot read the instance, complete its task, or start the process it has not imported).
