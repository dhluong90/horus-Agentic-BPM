# Audit and retention configuration

What the engines record, why it cannot be edited, how long it is kept, and how old data is dropped. **The retention figures are to be decided by the maintainers.** This page gives the options and the safe settings; it does not choose a period for you or for the project.

## What is audited

| Record | Table | Written when |
|---|---|---|
| Every rule decision: tenant, rule set, version, inputs (masked where declared), matched rules, outputs, caller, clock | `rle_decision` (the decision log) | Each evaluation, in the same transaction |
| The life of a rule version: author, requester, approver, comments, reseal, evidence export | `rle_version_history` | Each lifecycle step |
| Every process transition, task action, approval and operator action, with actor and time | `pre_history` | Each step |
| Each accepted tenant switch: actor, original tenant, new tenant, time, correlation id | The `io.github.dhluong90.bpm.audit` logger, or your own `TenantSwitchAudit` bean | Each switch |
| Each retention drop: partition, range, tenants, row counts, cutoff | `rle_decision_log_drop` | Each drop, in the same transaction |
| Each search by a masked input's plain value | Event `rules.decision-log.masked-input-searched` | Each search, without the value |

Guides: [Rule Engine Decision Log and Audit](../wiki/Rule-Engine-Decision-Log-and-Audit.md), [Multi-Tenancy and Security](../wiki/Multi-Tenancy-and-Security.md).

## Audit is immutable

- The engines have no update or delete path for `rle_decision` and `pre_history`.
- The database refuses `UPDATE`, `DELETE` and `TRUNCATE` on them and on the other append-only tables, with triggers. `rle_decision_default` is the one exception: it allows `DELETE`, which the roll-over needs.
- The application database role cannot switch the triggers off. A table owner can, so the host runs as a separate application role and the owner role is used only by the migration step. See [Database roles](../wiki/Operations-Runbook.md#database-roles).
- Append-only protects against the engines' code and the application role. It does not protect against a database administrator. For approvals, add a seal key ([Secrets and key rotation](SECRETS-AND-KEY-ROTATION.md)) and relay events to a store outside the administrator's control.

## Retention options

| Data | Setting | Options | Default |
|---|---|---|---|
| Decision log | `horus.bpm.rules.decision-log.retention` | `keep-forever`, or an ISO 8601 period in whole months, such as `P12M`, `P84M`, `P120M` | Required from 1.0. Unset stops start-up outside the `dev` profile |
| Shortest retention accepted | `horus.bpm.rules.decision-log.retention-floor` | `P12M` or longer, in whole months | `P12M` |
| Partitions created ahead | `horus.bpm.rules.decision-log.partitions-ahead` | `PT0S` to `P366D` | `P7D` |
| Process history (`pre_history`) | none on main | The engine drops nothing. A `history.retention` setting is planned in the design but not built; the policy is to be decided by the maintainers (D2) | Kept without limit |
| Hourly statistics (`pre_node_stats_hourly`, not audit) | `horus.bpm.processes.stats.rollup-retention` | ISO 8601 period of at least `P31D` | `P13M` |
| Idempotency keys | `idempotency.retention`, `horus.bpm.processes.idempotency-retention` | `PT1M` to `P30D` (Rule Engine) | `PT24H` |

Details of each key: [Rule Engine Configuration](../wiki/Rule-Engine-Configuration.md).

**Figures to be decided by the maintainers:**

| Figure | Value |
|---|---|
| Decision log retention for the reference deployment and the examples | to be decided by the maintainers |
| Process history retention | to be decided by the maintainers |
| Floor above `P12M` | to be decided by the maintainers |

The product owner has not decided these. They are part of owner decision D2 in [PRODUCTION-READINESS.md](PRODUCTION-READINESS.md). A host that must keep everything sets `keep-forever`. A bank sets the period its records policy requires.

## The retention-drop procedure

The drop is by whole monthly partition. No row is ever deleted. Do not run `DROP TABLE` on a partition yourself: nothing would record the drop.

1. Choose the period from your records policy. Set it, for example in the environment of the migration step and the host:

   ```bash
   export HORUS_BPM_RULES_DECISIONLOG_RETENTION=P84M
   ```

2. Back up first. A drop cannot be undone from the database; the data stays only in backups taken before it, and those hold it until they expire. See [Disaster recovery](DISASTER-RECOVERY.md).
3. Run the migration step as the owner role (`HORUS_BPM_MIGRATE_ONLY=true`). Only the owner can drop partitions; the serving host cannot. Schedule the step at least every `partitions-ahead` minus one month. See [The decision log partition step](../wiki/Operations-Runbook.md#the-decision-log-partition-step).
4. The daily run drops each monthly partition that ends at or before the host clock minus the retention. With `P12M` on 3 February 2028, `rle_decision_2027_01` goes and `rle_decision_2027_02` stays. Each drop is logged at WARN.
5. Check what was dropped:

   ```sql
   SELECT partition_name, tenant_id, decision_count, retention, cutoff, dropped_at
   FROM rle_decision_log_drop ORDER BY dropped_at, partition_name, tenant_id;
   ```

   Expected: one row per tenant per dropped month, and one row with tenant `*` for the total.
6. Confirm that rows are not piling up outside the monthly partitions: the alert `HorusDecisionLogDefaultPartitionRows` is quiet. See [Operations runbook](../wiki/Operations-Runbook.md#horusdecisionlogdefaultpartitionrows).

Dropped decisions can no longer be replayed. Keep the retention at least as long as the period in which you may need to prove a past decision.
