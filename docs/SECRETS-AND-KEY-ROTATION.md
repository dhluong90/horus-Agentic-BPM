# Secrets and key rotation

One page that lists every secret a host holds, where it lives, how it rotates, and which runbook section has the steps. The steps are not repeated here: follow the links.

Rules for all of them:

- Keep every secret in your secrets manager and inject it at start-up, as an environment variable or a mounted file. Never put one in the database, the image, the repository or a log.
- Give the people who can read the database backup no access to the keys, and the other way round.
- Rotate on a schedule your security team sets, and at once after a suspected leak.
- Test a restore of each key on a scratch host before you rely on its backup.

| Secret | Held by | Rotation in one line | Steps |
|---|---|---|---|
| Seal key (`ApprovalSealKey`) | Rule Engine host | Add the new key as `current()`, keep the old in `previous()`, re-seal every approved version, drop the old key only when no seal names it | [Seal key custody](../wiki/Operations-Runbook.md#seal-key-custody), [Seal key and rotation](../wiki/Rule-Engine-Versioning-and-Publishing.md#seal-key-and-rotation) |
| Variable-encryption keyring | Process Engine host | Add the new key, make it active, keep the old listed, run the re-encryption sweep, drop the old key only when the sweep reports nothing left | [Variable encryption key custody](../wiki/Operations-Runbook.md#variable-encryption-key-custody), [Key custody for variable encryption](../wiki/Multi-Tenancy-and-Security.md#key-custody-for-variable-encryption) |
| Database owner and application credentials | Migration step and serving host | Change the role password in the database and in the secret; restart the consumer | [Database roles](../wiki/Operations-Runbook.md#database-roles) |
| OTLP collector endpoint and its credentials | Host, if tracing export is on | Change the endpoint or token in the secret; restart | [Traces (OpenTelemetry)](../wiki/Operations-Runbook.md#traces-opentelemetry) |

Other keys the host can hold, each with its own rotation in the linked page: the masking key (`masking.hmac-key`, earlier keys under `masking.previous-keys`) and the list-cursor keys (`cursor.keys`). See [Rule Engine Configuration](../wiki/Rule-Engine-Configuration.md) and [Multi-Tenancy and Security](../wiki/Multi-Tenancy-and-Security.md).

## Seal keyring

The seal is `hmac-sha256:<keyId>:<hex>`. The key is at least 32 bytes and stays outside the database. Rotation re-seals each approved version with a second person (not the approver, author or requester). Each re-seal adds a `RESEALED` row to the history. Check which key ids are still in use before you drop one:

```sql
SELECT split_part(approval_seal, ':', 2) AS key_id, count(*)
FROM rle_version WHERE approval_seal LIKE 'hmac-sha256:%' GROUP BY 1;
```

Expected after a complete rotation: only the new id. Keep the old key in your offline backup until a backup cycle has captured a database with no seal that names it. A lost key leaves its versions refused. The scope of the seal on versions approved before it existed (`legacy:pre-seal`) is covered in [Upgrade to a later release](../wiki/Operations-Runbook.md#the-legacy-pre-seal-marker) (#644, #692).

## Variable-encryption keyring

The keys are `horus.bpm.processes.variable-encryption.keys.<id>` with `active-key-id`. Every replica gets the same keys. **Old keys must stay listed until every store is re-encrypted.** The sweep (`JdbcVariableReencryption.reencrypt(tenant, afterId, 500)`, once per tenant, until `rewritten` and `refused` are both 0) rewrites the instance rows only. Job payloads, tokens and history written under the old key remain readable only while that key is listed. Removing a key early refuses those values with `VARIABLE_KEY_UNAVAILABLE`, and a lost key cannot be recovered (#703).

## Database credentials

Two roles: `horus_owner` owns the tables and is used by the migration step only; the application role serves requests and cannot switch off the audit triggers (#704). To rotate either password, change it in the database (`ALTER ROLE ... PASSWORD`) and in the secret, then restart what uses it. The serving host must never receive the owner secret: check with `kubectl get pod <pod> -o yaml` or `docker compose config`. Outside the `dev` profile the host refuses to start if its role owns the tables (`HORUS-DB-ROLE-001`).

## OTLP endpoint credentials

Export is off by default. Turn it on only with an `https` endpoint of a collector you control. The exporter is the one in Spring Boot, so the engines add no setting of their own. If the collector needs a token, supply it through Spring Boot's OTLP tracing headers property (see the Spring Boot documentation for your version), from your secrets manager and not from a file in the repository. To rotate, change the secret and restart the hosts. A collector that is down never fails engine work.

## Check

- Each secret above has a named owner and a date of its last rotation in your [rehearsal log](../wiki/Operations-Runbook.md#rehearsal-log).
- `tools/readiness-check.sh` shows item R12 as `pass` once this page is on `main`.
