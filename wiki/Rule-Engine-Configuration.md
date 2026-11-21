# Configuration

Reference for every `horus.bpm.rules.*` property of the Rule Engine starter and the permission strings its REST layer checks. _Design preview: the properties marked Built are read today; the rest are planned for the milestone named in their row._

## Status of each property

| Status | Meaning |
|---|---|
| Built | Read by the starter on `main`. Not yet released |
| 0.1 (M1) | Planned for Rule Engine 0.1, milestone M1, 15 Jan 2027. Setting it today has no effect |
| 0.1 (M1), may move | Planned for 0.1; the [[Roadmap]] names the milestone it moves to if M1 runs late |
| M2, M3 | Planned for that milestone (M2 26 Mar 2027, M3 21 May 2027). Setting it today has no effect |

## Properties

All names are under `horus.bpm.rules.`. Table names are fixed (`rle_*`, Flyway history `rle_schema_history`) and have no property.

| Property | Default | Status | Meaning |
|---|---|---|---|
| `enabled` | `true` | Built | Register the engine beans. The engine needs exactly one `DataSource` in the host (or one marked `@Primary`); without it the starter stays inactive and logs a warning |
| `migrate-on-start` | `true` | Built | Run the engine's Flyway migrations at start-up with the host `DataSource`; `false` for hosts that run migrations in a separate job |
| `definitions` | unset | 0.1 (M1) | Resource pattern of YAML rule sets to load as drafts at start-up |
| `definitions-tenant` | `default` | 0.1 (M1) | Tenant that owns rule sets loaded from `definitions`. A document may override it with a top-level `tenant:` field; multi-tenant hosts load per-tenant files or use the import endpoint |
| `definitions-auto-publish` | `false` | 0.1 (M1) | Publish loaded drafts without maker-checker. Refused unless the `dev` profile is active |
| `rest.enabled` | `true` | 0.1 (M1) | Expose the REST API |
| `rest.base-path` | `/api/bpm/rules/v1` | 0.1 (M1) | Base path of the REST API. Keep the version segment when you change it; the cockpit reads the same value |
| `rest.cors.allowed-origins` | empty | 0.1 (M1) | Origins allowed to call the REST API, needed only for a standalone cockpit. Empty disables CORS |
| `decision-log.enabled` | `true` | 0.1 (M1) | Write `rle_decision` rows |
| `decision-log.mode` | `transactional` | `transactional` 0.1 (M1); `async` M2 (March 2027), may move to M3 | `transactional`: one `INSERT` in the caller's transaction. `async`: insert into `rle_decision_outbox`, relayed by the scheduler thread, for high-volume hosts. Until `async` ships, setting it stops start-up with a message naming the milestone |
| `decision-log.async.batch-size` | `100` | M2, may move to M3 | Outbox rows relayed per poll in `async` mode |
| `decision-log.partitions-ahead` | `P7D` | Built (0.2) | How far ahead of the host clock monthly `rle_decision` partitions are created, at start-up and daily while the host runs; `PT0S` to `P366D`. The current and the next month always exist. See [Decision log partitions](#decision-log-partitions) |
| `decision-log.retention` | unset: keeps everything, warns | Built (0.2) | How long decisions are kept: `keep-forever`, or an ISO 8601 period in whole months, at least `decision-log.retention-floor`, for example `P12M`, `P120M` or `P10Y`. Retention is never on by default, so an existing host upgrades without setting it. The engine refuses a value below the floor or in days, naming the property. The daily partition job drops each whole monthly partition that ends at or before the host clock minus this period, and records each drop in `rle_decision_log_drop`. Unset, on every profile, nothing is ever dropped and start-up is not affected, but a WARN at every start-up says the setting is unset and everything is kept. `keep-forever` is the explicit way to say so and logs INFO. Only an explicit period enables drops. A later release, 1.0, requires the setting; see [[Rule Engine Decision Log and Audit]]. No decision row is ever deleted by date. See [[Rule Engine Decision Log and Audit]] |
| `decision-log.mask-default` | `false` | Built | Mask every input that declares no `mask`; an input with `mask: false` is never masked by this default. A change is logged at start-up, one line per tenant with the old and new value, and the value in force is shown in `/api/bpm/meta`. See [Personal data guards](#personal-data-guards) |
| `decision-log.require-explicit-mask` | `false` | 0.2 | Strict mode: every input must declare `mask: true` or `mask: false`. An undeclared input is `422 VALIDATION_FAILED` naming it, at import, draft replacement, publication request and approval. Shown in `/api/bpm/meta`. Turn it on for production |
| `lifecycle.acknowledge-warnings` | `[UNMASKED_PERSONAL_DATA]` | 0.2 | Warning codes an approver must accept before a version is published: the approve request lists the exact `{ code, input }` pairs shown in `acknowledgedWarnings`, else `409 WARNINGS_NOT_ACKNOWLEDGED`. The acceptance is recorded on the approval history row and the published event. An input masked only by the tenant's `decision-log.mask-default` needs no acceptance; the approval records it in `maskedByDefaultInputs`. Removing `UNMASKED_PERSONAL_DATA` relaxes a control: it is logged at WARN at start-up and shown in `/api/bpm/meta` |
| `decision-log.personal-data-names` | the built-in list | 0.2 | Input names that look like personal data, replacing the built-in list. An undeclared input that matches one gets a warning the approver sees |
| `decision-log.additional-personal-data-names` | empty | 0.2 | More names, added to the list |
| `masking.hmac-key` | unset | Built | Base64 key, 32 bytes or more after decoding, for HMAC-SHA256 of masked inputs, used by every tenant without its own key. Supply it from an environment variable (`HORUS_BPM_RULES_MASKING_HMAC_KEY`) or a secret store. When unset, the shared `horus.bpm.masking.hmac-key` is used. See [Masking keys](#masking-keys) |
| `masking.hmac-key-id`, `masking.tenants.<tenant>.hmac-key-id` | `k1` | Built | Label of the key, 1 to 32 letters, digits, `_` or `-`, stored in every fingerprint as `hmac:<keyId>:<hex>`, so each row says which key made it. Not secret; change it whenever you change the key. When unset, the shared `horus.bpm.masking.hmac-key-id` is used |
| `masking.search.max-per-minute`, `masking.tenants.<tenant>.search.max-per-minute` | `30` | Built | Masked-input searches one caller may run per minute on one replica, at least `1`; it cannot be switched off. Past it: `429 RATE_LIMITED` with `Retry-After`, and an audit entry with `outcome = RATE_LIMITED`. A value below 1 stops start-up |
| `masking.tenants.<tenant>.hmac-key` | unset | Built | The tenant's own key, same format. A tenant with this setting never writes with the host key, even when its own value is invalid. Its search still tries its subkeys of the host keys, so rows from before the move stay findable. The tenant id `*` is reserved and stops start-up |
| `masking.previous-keys[n].hmac-key`, `masking.previous-keys[n].hmac-key-id`, `masking.tenants.<tenant>.previous-keys[n].*` | empty | 0.2.0 | Earlier keys kept after a rotation, each with the id its tokens carry (required). Used by the masked-input search only, never for writing. A previous host key is also searched for tenants that now have a key of their own. See [Rotating a key](#rotating-a-masking-key) |
| `masking.tenants.<tenant>.mask-default` | `decision-log.mask-default` | Built | The masking default of one tenant |
| `masking.tenants.<tenant>.require-explicit-mask` | `decision-log.require-explicit-mask` | 0.2 | Strict mode for one tenant |
| `masking.tenants.<tenant>.personal-data-names` | the host's list | 0.2 | The tenant's own list, replacing the host's for that tenant only |
| `masking.tenants.<tenant>.additional-personal-data-names` | empty | 0.2 | More names for one tenant, added to whichever list applies |
| (key status in `/api/bpm/meta`) | | 0.2.0 | The rules settings show, for the caller's own tenant, `masking.hmac-key: set` or `unset`, and when set `masking.hmac-key-id` and `masking.hmac-key-check`, the key check value (the first 8 bytes of the HMAC-SHA256 of an empty message under `HKDF-SHA256(key, info = "horus-bpm/masking/v1/kcv")`, in hex, not secret). These describe the key the tenant writes with: its own key, or its subkey of the host key, so tenants that share the host key see different check values. For a tenant on the host key, the check value is therefore that of its subkey, not the one recorded for the host key in `rle_masking_key_check`. The key id is the same for every tenant on the host key, so those tenants can tell they share it; use an opaque host key id if that matters. The key itself is never shown. A `MaskingPolicy` bean of your own is described the same way |
| `evaluation.max-rows` | `5000` | Built | Most rules a version may contain, checked at import (`422 VALIDATION_FAILED`). `1` to `5000`; may be lowered, never raised |
| `evaluation.pin-retired` | `false` | 0.2 | Whether an evaluation pinned with `rules:pin` may name a `RETIRED` version. `false` answers `409 INVALID_STATE`, so a rule taken out of service never makes a new logged decision. Replay of a logged decision is not affected |
| `evaluation.max-output-bytes` | `1048576` | Built | Estimated JSON size of one decision's outputs, checked while `collect` values accumulate. A breach fails the decision with a logged `EVALUATION_ERROR` and no outputs. `1` to `1048576`; may be lowered, never raised |
| `evaluation.max-string-length` | `65536` | Built | Longest string input (`400 INPUT_INVALID`) and longest string that `+` may build (`EVALUATION_ERROR`, checked before the string is built), in UTF-16 code units. `1` to `65536`; may be lowered, never raised |
| `evaluation.max-batch` | `1000` | Built | Most items in one `:evaluate-batch`; more is `422 BATCH_TOO_LARGE`. `1` to `1000`; may be lowered, never raised |
| `simulation.max-history-decisions` | `50000` | Built | Most logged decisions one `:compare` over a past period reads (about 100,000 evaluations, a few seconds of one thread). `1` to `200000`; may be lowered or raised up to that ceiling. A larger period is `422 HISTORY_TOO_LARGE`, never sampled. Out of range stops start-up naming `horus.bpm.rules.simulation.max-history-decisions` |
| `simulation.max-inputs` | `1000` | Built | Most sample input sets per `:simulate` (`400 INPUT_INVALID` above it), and most logged decisions per `:simulate-history` run (`400 QUERY_INVALID` for a larger `limit`). `1` to `1000`; may be lowered, never raised |
| `evaluation.regex-max-reads` | `1000000` | Built | Characters one `matches()` may read, `1` to `1000000`; may be lowered, never raised. The primary bound: it is the same on every machine, so a decision that stops here replays the same. Past it the decision fails with `422 EVALUATION_ERROR`, `error_code = REGEX_TIMEOUT` |
| `evaluation.regex-max-reads-per-decision` | `5000000` | Built | Characters all the `matches()` of one decision may read together, `1` to `5000000`; may be lowered, never raised. Counted the same way as `regex-max-reads`, so it is the same on every machine. It bounds a rule set with many `matches()` rows: past it the decision fails with `422 EVALUATION_ERROR`, `error_code = REGEX_TIMEOUT`, and no outputs. An invalid value stops start-up |
| `evaluation.regex-max-reads-per-request` | `50000000` | Built | Characters all the `matches()` of one request of many decisions may read together, `1` to `5000000000`. Applies to `:simulate` (both sides of every sample) and to `:evaluate-batch`. Counted the same way as `regex-max-reads`, so the same request stops at the same item every time. The item that passes it fails with `EVALUATION_ERROR`, `REGEX_TIMEOUT`, and a `detail` naming this setting; earlier items keep their results, and later items are not evaluated (each reported failed with a `detail` starting `REGEX_TIMEOUT: not evaluated`). A host may raise it up to the ceiling, which is 1,000 items times the per-decision ceiling. An invalid value stops start-up |
| `evaluation.regex-timeout-ms` | `50` | Built | Backstop of the read budget: longest time one `matches()` may run, `1` to `1000` ms. Past it the match stops on its own thread and the decision fails with `422 EVALUATION_ERROR`; the decision row records `error_code = REGEX_TIMEOUT`. An invalid value stops start-up |
| `evaluation.step-budget` | `100000` | Built | Expression nodes one decision may visit, `1` to `1000000`. Counted, so a decision that stops stops at the same step on every machine. Past it the decision fails with `EVALUATION_ERROR`, reason `EVALUATION_BUDGET_EXCEEDED`, logged with that `error_code`. Import and request-publish refuse a table whose worst case is over it. A value outside the range stops start-up naming the property |
| `evaluation.function-version-change` | `warn` | Built | What evaluation does when the version label of a host function is not the one a rule set version was approved with. `warn`: evaluation goes on, start-up logs one warning, `/api/bpm/meta` lists the change and each decision records the version it used. `refuse`: the decision fails with `422 EVALUATION_ERROR`, `error_code = FUNCTION_CHANGED`, until a new version of the rule set is approved by a second person. Any other value stops start-up naming the property. See [[Rule Engine Expression Language]] |
| `evaluation.timeout-ms` | `10000` | Built | Longest time one decision may evaluate, `1` to `30000`: the backstop of the step budget on a slow machine, with the same reason. The step budget decides first |
| `evaluation.regex-max-input-length` | `4096` | Built | Longest text `matches()` looks at, `1` to `evaluation.max-string-length` (a lower `max-string-length` lowers this default with it). A longer text fails the decision without running the match, `error_code = REGEX_INPUT_TOO_LONG` |
| `evaluation.max-input-scale` | `34` | Built | Largest absolute scale of a number input (`1e-35` and `1e35` are refused with `400 INPUT_INVALID`; at most 34 significant digits always). `0` to `34`; may be lowered, never raised. An invalid value stops start-up |
| `evaluation.pin-retired` | `false` | 0.2 | Whether a pinned evaluation (`rules:pin`) may name a `RETIRED` version. `false` refuses it with `409 INVALID_STATE`, so a rule taken out of service produces no new decision; replay of logged decisions is not affected |
| `evaluation.reject-undeclared-inputs` | `true` | Built | Refuse an input name the rule set does not declare with `400 INPUT_INVALID`, so a misspelt name such as `txcount` never falls to a default row. With `false`, undeclared inputs are ignored; they are never written to the decision log either way |
| `cache.max-entries` | `1000` | Built (LRU from 0.2) | Compiled versions kept per replica, `1` to `100000`, one per rule set and tenant. When full, the least recently used entry leaves, counted by `horus.rle.cache.evictions{cause=size}`, so memory stays flat however many versions exist. An invalid value stops start-up |
| `cache.listen-notify` | `true` | Built (0.2) | Hold one connection from the host's pool for `LISTEN horus_rle_cache`, so every replica drops an approved rule set within about a second. Set `false` behind a transaction-mode pooler. See [Replicas and connection poolers](#replicas-and-connection-poolers) |
| `cache.epoch-check-interval` | `PT5S` | Built (0.2) | How often each replica compares its cached rule sets with their `cache_epoch`, `PT0.1S` to `PT5M`: one indexed read per cached tenant. The longest a replica serves a version after another replica's approval when no notification arrives |
| `idempotency.retention` | `PT24H` | Built | Lifetime of `rle_idempotency` rows, `PT1M` to `P30D`. An invalid value stops start-up |
| `query.max-range`, `query.max-page-size`, `query.statement-timeout` | `P31D`, `200`, `PT2S` | Built (0.2) | Bounds of `GET /decisions` and list reads. `max-range` is the longest period of one decision search or `:simulate-history` run (`PT1H` to `P31D`); `max-page-size` the largest `limit`, and the page size of `GET /decisions` when no `limit` is given; `statement-timeout` the longest one search page may run (`PT0.1S` to `PT30S`), set for that statement only. Range and page size may be lowered, never raised |
| `approval.require-content-hash` | `true` | Built (0.3) | Whether `:approve` and `:reject` must send `expectedCanonicalHash`, the `canonicalHash` of the content the reviewer saw (`RLE-084`, PD-139). A request without it is `400 CONTENT_HASH_REQUIRED`; a hash that no longer matches is `409 CONTENT_CHANGED` with `currentCanonicalHash`. `false` relaxes a control and is accepted only on the `dev` profile on its own (with `test` at most): any other profile stops start-up. It is logged at WARN at start-up and shown in `/api/bpm/meta` as `approval.require-content-hash` |
| `lifecycle.sweep-interval` | `PT1M` | 0.2 | How often superseded versions are marked retired, `PT1S` to `PT1H`; evaluation already switches at `effectiveFrom`. Runs on every replica, each retirement recorded once |
| `authoring.cockpit-drafts` | `false` | Built (published in `/meta`); enforced on `PUT` of a draft from 0.2.0 | Whether the cockpit may create and edit drafts on this host; off by default in 0.1.0 (PD-094). Always published in `/api/bpm/meta` as the rules setting `authoring.cockpit-drafts`. Turn it on with `authoring.cockpit-drafts: true`, as the example host does on its `dev` profile. Import of a document, approvals and simulation are not affected. From 0.2.0 the engine enforces it: with `false`, replacing a draft (`PUT /rule-sets/{name}/versions/{v}`, also through the Java API) is refused with `403 DRAFT_AUTHORING_DISABLED`. Restoring an earlier version as a new draft (`POST .../versions?copyOf={v}`) is not affected: it reuses content that was already approved and still needs a second approver |
| `readings.style` | `sentence` | 0.1 (M1) | Style of the plain-language readings of rules and of explanation `text`: `sentence` (patterns from the message catalogue) or `shorthand` (for example `tier: GOLD, PLATINUM`). If sentences miss the 8 Jan 2027 checkpoint, 0.1 accepts only `shorthand` until sentences ship. Shown in `/api/bpm/meta` as `rules.readings.style` |
| `lifecycle.reason-required`, `lifecycle.reason-max-length` | `true`, `2000` | 0.1 (M1) | Reason on reject and retire; may be made optional for reject only |
| `lifecycle.system-actor` | `system:supersession` | 0.2 | Actor recorded when the engine retires a superseded version. It must start with `system:`, a namespace reserved for the engine's own actors; a caller whose id starts with `system:` is refused with `403 PERMISSION_DENIED` on every operation |
| `lifecycle.retire-approval` (host property `horus.bpm.lifecycle.retire-approval`, shared with the Process Engine) | `single` | 0.1 (M1); `four-eyes` built | Approval mode for retiring a published version by hand. `single`: one `rules:approve` holder retires it with a mandatory reason. `four-eyes`: `:retire` only records a request and the version keeps serving; a second `rules:approve` holder confirms with `:confirm-retire`, and the requester's own confirmation is refused with `403 SELF_APPROVAL_FORBIDDEN`; any holder can withdraw the request with `:cancel-retire` and a reason. The last-version rule applies to the request and is checked again at the confirmation. The mode in force is shown in `/api/bpm/meta` and recorded on every retirement event and history row. Independent of the emergency single-approver path, which governs publication only. See [[Rule Engine Versioning and Publishing]] |
| `lifecycle.retire-last-version` (host property `horus.bpm.lifecycle.retire-last-version`, shared with the Process Engine) | `confirm` | 0.2 | Retiring the last version in effect of a rule set. `confirm`: the retire needs `"confirmNoVersionInEffect": true`, else `409 LAST_VERSION_IN_EFFECT`. `four-eyes`: the retire becomes a request that a second approver confirms with `:confirm-retire`, whatever `retire-approval` says. `refuse`: never; approve a replacement first. Shown in `/api/bpm/meta` |
| `lifecycle.approver-must-differ-from` (host property `horus.bpm.lifecycle.approver-must-differ-from`, shared with the Process Engine) | `makers` | 0.2 for the setting; `makers` behaviour built | Who may not approve a version. `makers`: nobody who imported, created, edited or requested it. `requester`: only the requester is excluded; this relaxes a control, so it is never the default, it appears in `/api/bpm/meta`, is logged at WARN at start-up and is recorded on every approval. The requester can never approve (PD-096). The Rule Engine always applies `makers`, and reports `makers` in `/meta`, because its database constraint refuses an approval by the author; `requester` affects the Process Engine only |
| `explain.default-language` | `en` | 0.1 (M1) | Language of the explanation `text` when the request asks for none, or for a language without a catalogue. English (`en`) and Vietnamese (`vi`) catalogues ship; a host can override or extend them |
| `publication.require-simulation-report` | `false` | M3 (May 2027) | `true`: `:request-publish` is refused with `422 VALIDATION_FAILED` unless a stored simulation report of the draft is attached. Applies to the whole host, or only to the rule sets listed in `publication.require-simulation-report.rule-sets`. Until M3 the approver runs the simulation herself |
| `decision-log.retention-floor` | `P12M` | Built (0.2) | Shortest `decision-log.retention` accepted, in whole months. Raise it to your own policy, so that a shorter retention set by mistake stops start-up. It cannot be set below `P12M` |
| `maker-checker.enabled` | `true` | 0.1 (M1) | `false` is refused unless the `dev` profile is active |
| `tenancy.claim` | `tenant_id` | Built as the host-wide `horus.bpm.tenancy.claim` | Claim or principal attribute read by the default `TenantResolver`, for a token or a server-side session login alike |
| `tenancy.dev-tenant` | unset | Built as the host-wide `horus.bpm.tenancy.dev-tenant` | Fixed tenant for local work without an identity provider. `dev` profile only: start-up is refused when it is set outside `dev` |
| `events.decision-evaluated` | `false` | 0.1 (M1) | Publish `rules.decision.evaluated.v1` for every decision |
| `scheduler.enabled` | `true` | 0.1 (M1) | Run the engine's scheduler thread (outbox relay, partition creation, partition drop, idempotency cleanup) on this replica |
| `security.role-permissions` | empty | Built | Map of host role authorities to engine permissions; see [Mapping permissions](#mapping-permissions-to-the-hosts-authorities). Read by `/api/bpm/me` today; the engine endpoints use it from 0.1. A permission outside the seven below stops the start-up |

### Masking keys

A rule set marks personal data with `mask: true`; the decision log then stores `hmac:<keyId>:<hex>`, the HMAC-SHA256 of the value under a key the host provides, never the value (`RLE-012`). The engine checks the keys once at start-up, after its migrations:

| Situation | What happens |
|---|---|
| A version that is not retired masks an input (by `mask: true` or by the tenant's masking default) and its tenant has no key | Start-up fails with a message that names `horus.bpm.rules.masking.hmac-key`, the tenant and the rule set |
| A key setting holds a value that is not Base64 or decodes to fewer than 32 bytes | Start-up fails naming the setting; the value is never printed |
| A tenant key equals the host key or another tenant's key | Start-up fails naming the setting; keys are compared in constant time and never printed |
| A tenant key's id equals the host key id (`k1` when neither is set) | Start-up fails naming `masking.tenants.<tenant>.hmac-key-id`; give each tenant key an id of its own |
| Two keys share one id among the host keys or one tenant's keys, current or previous, or a previous key equals a configured key | Start-up fails naming the setting; the key is never printed |
| A key id already used in this database comes back with different key bytes | Start-up fails naming the key id and both check values, never the key (see [Rotating a key](#rotating-a-masking-key)) |
| A key id once used for a host key is given to a tenant key, or the reverse, even after the first key was retired | Start-up fails naming the key id and the tenant; give the new key an id of its own |
| A tenant is configured as `masking.tenants.[*]` | Start-up fails naming the setting: `*` is the scope of the host keys |
| A `MaskingPolicy` bean of your own hands out a key, for a tenant with live rule sets, that it does not return from `configuredKeys()` | Start-up fails naming the policy class, the tenant and the key id. A bean that declares no keys at all is named in a start-up warning |
| No rule set masks anything and no key is set | The host starts |
| An output cell or default reads an input declared `mask: true` | Import is refused with `422 VALIDATION_FAILED` naming the output and the input |
| An output reads an input that only the tenant's masking default masks | Its decisions fail closed: `422 EVALUATION_ERROR`, `error_code = MASKED_INPUT_IN_OUTPUT`, no inputs or outputs logged |
| A rule set that masks an input is imported later into a tenant without a key | Its decisions fail closed: `422 EVALUATION_ERROR`, the row has `error_code = MASKING_KEY_MISSING` and no inputs, so nothing is logged in plain text |

```bash
# Generate each key like this: 32 random bytes, Base64. Keep it in your secret store and pass it as an environment variable
export HORUS_BPM_RULES_MASKING_HMAC_KEY="$(openssl rand -base64 32)"
```

### Personal data guards

The decision log can never be edited, so an input that holds personal data and was never marked `mask: true` leaves data you cannot erase. Four guards catch it before publication (`RLE-049` to `RLE-052`):

| Guard | What happens |
|---|---|
| Warning | An input that declares no `mask` and whose name looks like personal data gets a warning in `warnings` of the version: `{ code: UNMASKED_PERSONAL_DATA, input: nationalId, matchedName: "national id", message }`. It comes back from the import, the draft replacement and the publication request, and on every read of the version, so the approver sees it too |
| Strict mode | With `decision-log.require-explicit-mask: true`, an input that declares no `mask` is `422 VALIDATION_FAILED`, one violation per input with `column` naming it, and nothing is stored. A draft imported, or a request made, before strict mode was turned on can be neither sent for approval nor approved until every input is declared |
| Diff | The version diff lists every input's masking status in `masking`, and marks changes: `nationalId: MASKED to NOT_MASKED` |
| Masking default | A change of `decision-log.mask-default` since the last start-up is one log line per tenant with the old and new value, at WARN when masking is turned off. `/api/bpm/meta` shows the masking default and strict mode in force for the caller's tenant |

The built-in names cover full name, `ho ten`, `ho va ten`, national id, citizen id, `cccd`, `cmnd`, passport, `ho chieu`, phone, `so dien thoai`, `sdt`, email, address, `dia chi`, date of birth, `dob`, `ngay sinh`, account number, `so tai khoan`, `stk`, IBAN, card number, tax id, `ma so thue`, `mst`, driver licence, `giay phep lai xe`, `gplx` and `bang lai`. Names are compared as words, ignoring case, punctuation and accents, without regular expressions: `nationalId`, `national_id` and `NATIONAL_ID` match "national id", and `company` matches nothing. Only names are checked, never values.

```yaml
horus:
  bpm:
    rules:
      decision-log:
        require-explicit-mask: true              # every input must say mask: true or mask: false
        additional-personal-data-names: ["cif", "customer ref"]
      masking:
        tenants:
          sandbox:
            require-explicit-mask: false         # a test tenant keeps warnings only
          bank-b:
            personal-data-names: ["customer ref"] # bank-b's own list, for bank-b only
```

The start-up record lives in `rle_setting_seen` (one row per tenant, plus one for the host default), so the runtime database user needs `SELECT`, `INSERT` and `UPDATE` on it.

**Tenant subkeys.** The host key is never used directly. A tenant without its own key uses the subkey `HKDF-SHA256(IKM = host key, salt = none, info = "horus-bpm/masking/v1/tenant/" + tenantId, 32 bytes)` (RFC 5869), so two tenants that share the host key never store the same token for one value. A tenant's own key is used as it is. The key id is the host key's, and the `v1` in `info` is fixed for the `hmac:<keyId>:<hex>` format. To check an implementation of your own, use this vector:

| Host key (hex) | Tenant | Value | Token |
|---|---|---|---|
| `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f`, id `k1` | `bank-a` | `079090001234` | `hmac:k1:e33165b4a825adb209efdafa2da3414acec8cc341b041985c539c537f69beeac` |
| the same | `bank-b` | `079090001234` | `hmac:k1:4edfc45a138147c7adcfee3a67777fc17e3ac39db08af486b31929448eb809d7` |

The subkey of `bank-a` in that vector is `c8e45e83d5ee7574102467fccc635e7113045b1b05714cca14a9aab5f01c2ecb`. The token is the input's value fingerprinted with the tenant's key only, without the rule set or the input name, so one person has one token across a tenant's rule sets.

To keep one key per tenant, either set `masking.tenants.<tenant>.hmac-key` for each tenant, or declare a `MaskingPolicy` bean that reads the tenant's key from your vault; the bean replaces every `masking.*` setting, and with it the personal-data settings: its `requireExplicitMask(tenant)` and `personalDataNames(tenant)` default to off and to the built-in list. A bean that shares one root key between tenants derives each tenant's key with `MaskingKey.forTenant(tenantId)`. The key never appears in a log line, a response, an event or a metric. Decisions keep the fingerprint, and the key id, of the key in force when they were made; stored rows are never re-fingerprinted.

#### Rotating a masking key

1. Generate the new key and give it a new id. Keep the old key and its id under `previous-keys`:
   ```bash
   export HORUS_BPM_RULES_MASKING_HMAC_KEY="$(openssl rand -base64 32)"
   export HORUS_BPM_RULES_MASKING_HMACKEYID=k2
   export HORUS_BPM_RULES_MASKING_PREVIOUSKEYS_0_HMACKEY="$OLD_KEY"   # from your secret store
   export HORUS_BPM_RULES_MASKING_PREVIOUSKEYS_0_HMACKEYID=k1
   ```
2. Restart every replica. New decisions are stored as `hmac:k2:<hex>`. A search by a national id such as `079090001234` fingerprints it with `k2` and then with `k1`, so it finds decisions made under both keys. A tenant on the host key searches with its own subkey of each key; a tenant's own earlier keys go under `masking.tenants.<tenant>.previous-keys` and are used as they are.
3. To retire `k1`, remove it from `previous-keys`. Decisions made under `k1` stay in the log, but the search no longer finds them.

**Rolling deploys.** While replicas restart one by one, the ones still on the old settings do not know `k2`: their searches miss decisions that the new replicas wrote under `k2`, until they restart too. To close that gap, roll out in two steps: first add the new key under `previous-keys` with id `k2` and keep `k1` current, so every replica can search under `k2`; then make `k2` current and move `k1` to `previous-keys`.

**Moving a tenant off the host key.** When a tenant that shared the host key gets `masking.tenants.<tenant>.hmac-key`, it writes under its own key from then on. Its search still tries its subkey of the current host key and of every key under `previous-keys`, so the decisions it made before the move stay findable until that host key is retired. Give the tenant key an id the host never used: a host key id never comes back as a tenant key id, and the reverse.

**Key check values.** A key id names one key for good. At start-up the engine records a check value for every configured key, per tenant (or `*` for host keys) and key id, in `rle_masking_key_check`, and it refuses to start when a known id comes back with different key bytes, across replicas and over time, and also after the key is retired. It also refuses a key id that moves between the host and a tenant. The check value is computed under a key derived for it alone: `HKDF-SHA256(key, info = "horus-bpm/masking/v1/kcv")`, then the first 8 bytes of the HMAC-SHA256 of an empty message, in hex. It is not secret: it tells keys apart without revealing them, and it is never equal to a token. The key `000102…1f` gives `471be5b6391b902f`. A `MaskingPolicy` bean of your own must return every key it uses, current and previous, from `configuredKeys()`: a tenant's own keys under its id, host keys under `*`. At start-up, a key in use for a tenant with live rule sets that is not declared there stops the host.

## Host-wide properties

These are under `horus.bpm.` and apply to every engine in the host. They are read by the host metadata endpoints, `/api/bpm/meta` and `/api/bpm/me` ([[Host Metadata and Permissions]]).

| Property | Default | Status | Meaning |
|---|---|---|---|
| `host-id` | unset | Built | Id clients show for this host, 1 to 40 characters; returned as `hostId` by `/api/bpm/meta` |
| `host-metadata.enabled` | `true` | Built | `false` turns off `/api/bpm/meta` and `/api/bpm/me` only. The `no-store` header on engine responses, the tenant resolver and `EngineAccess` stay on: controls are not options (ADR-0024) |
| `tenancy.claim` | `tenant_id` | Built | Attribute of the authenticated principal that the default `TenantResolver` reads: the claim of a validated token, or the attribute of a session principal from an OIDC or OAuth2 login (PD-079). Whatever the resolver, a principal with this attribute must match the resolved tenant |
| `tenancy.dev-tenant` | unset | Built | Fixed tenant for principals that carry none, for local work without an identity provider. Accepted on the `dev` profile only; start-up is refused when it is set elsewhere |

There is no header-based tenant mode: the engines never take the tenant from what the browser sends, and `tenancy.resolver` and `tenancy.trusted-gateway` stop the start-up with a message (PD-079). A host whose principal has no tenant attribute declares its own `TenantResolver` bean.
| `security.challenge` | `Bearer` | Built | `WWW-Authenticate` value of a `401` the engine endpoints raise themselves |

### Decided settings without a property name yet

These settings are decided. Their property names and ranges arrive with the specification of their milestone, and this table then moves into the one above.

| Setting | Default | Milestone | What it does |
|---|---|---|---|
| Emergency single-approver path | Off | Set by its ADR | On: a logged path lets one person publish in an emergency; every use is reviewed afterwards. Never applies to retirement |

The cockpit's landing area is the host property `horus.bpm.cockpit.landing` (default `home`; also `rules` or `approvals`), published as `cockpit.landing` in `/api/bpm/meta`; see [[Cockpit]].

### Process Engine properties decided so far

The full Process Engine list arrives with its 0.2 configuration page. These are already decided, under `horus.bpm.processes.`:

| Property | Default | Status | Meaning |
|---|---|---|---|
| `agents.labels.max-count`, `agents.labels.max-key-length`, `agents.labels.max-value-length` | `16`, `64`, `256` | M2 | Bounds of the optional labels an agent adapter reports with a result, recorded in history and never acted on. `model` and `promptVersion` are reserved keys. May be lowered, never raised |
| `authoring.cockpit-drafts` | `true` | M2 | `false` refuses creating and editing process drafts from the cockpit with `403 DRAFT_AUTHORING_DISABLED`. Imports, approvals and layout saves still work |

Properties with a prefix of `tenancy`, `masking` and `rest.cors` are shared with the Process Engine starter under `horus.bpm.processes.*`; when both engines run in one host, set them once under `horus.bpm.*`.

There is no property to turn decision log partitioning on or off. `rle_decision` is partitioned by month in every install (ADR-0010).

There is no `clock` property either. The engine's time comes from the host's `Clock` bean (see [Host beans](#host-beans-the-starter-picks-up)), and a single decision can reason about another instant through the request's `clock` field, which needs `rules:simulate` over REST. To fix time in tests, declare a fixed `Clock` bean, for example `Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC)`.

### What the first build reads

```yaml
horus:
  bpm:
    host-id: loans                 # optional
    rules:
      enabled: true                # default
      migrate-on-start: true       # default
      security:
        role-permissions:
          ROLE_PRICING_AUTHOR:   [rules:read, rules:author]
          ROLE_PRICING_APPROVER: [rules:read, rules:approve]
```

On a clean database on 19 Nov 2026, start-up logs two lines from the engine:

```text
Rule Engine schema at version 1 (1 migration(s) applied)
Rule Engine created decision log partitions [rle_decision_2026_11, rle_decision_2026_12]
```

On the next start both partitions exist, so only the first line appears, with `0 migration(s) applied`.

## Decision log partitions

`rle_decision` is range-partitioned by calendar month in UTC from the engine's first migration, with a default partition `rle_decision_default` for rows outside any monthly partition. Partitions are named `rle_decision_YYYY_MM`. This is fixed, not configured (ADR-0010).

| Step | When | Status |
|---|---|---|
| Create the partitions of the current and the next month | Every start-up, after the engine's migrations. The month comes from the host `Clock` bean, or system UTC when the host has none, never from the database clock | Built |
| Move rows of a month out of `rle_decision_default` | When that month's partition is created, in the same transaction, before it is attached | Built |
| Create partitions up to `now() + decision-log.partitions-ahead` | At start-up and then daily, on the engine's own daemon thread `horus-rle-partitions`, without a restart | Built (0.2) |
| Drop partitions older than `decision-log.retention`, recording each drop | In the same daily run, right after the creation step, only when a retention is set | Built (0.2) |

Several replicas can start at once: a transaction-scoped advisory lock lets one replica create the partitions at a time, and the others find them present. Each creation waits at most 5 s for locks (`lock_timeout`). A daily run that times out logs a warning and the next run retries; the 7 days ahead leave room for it. No failure of one run ends the daily loop: any error is logged with the period it was creating and the next run retries. Only a JVM-fatal error (`OutOfMemoryError`, `StackOverflowError`) stops it; the `ruleEngine` health contributor then reports `DOWN` with `partitionMaintenance: STOPPED`, so the orchestrator restarts the host. The detail `partitionMaintenanceLastSuccess` shows the time of the last successful run (`never` before the first). The creation step issues no `UPDATE`, no `DELETE` on `rle_decision` and no `DROP`. The retention step issues one `DROP TABLE` per expired month and never a `DELETE`; it waits at most 2 s for locks, and a run that times out drops and records nothing and the next run retries.

The gauge `horus.rle.partition.default.rows` (Prometheus `horus_rle_partition_default_rows`) counts the rows in `rle_decision_default` each time the host's meter registry reads it. It should read 0; alert above 0. It is registered only when the host has Micrometer on its classpath, for example through Spring Boot Actuator, and reads `NaN` when the database cannot be reached. When the job creates a month, the rows of that month move out of the default partition and the gauge drops back.

With `migrate-on-start: false`, the engine creates partitions only if its schema is current. If migrations are pending, it skips the partitions, logs a warning, and the `ruleEngine` health contributor reports `DOWN`. Run the migrations, then restart.

The `ruleEngine` health contributor also reports whether next month's partition exists. A missing partition is a detail, not a failure: decisions of a month without a partition go to `rle_decision_default` until that partition is created.

## Replicas and connection poolers

Each replica keeps the compiled version of every rule set it evaluates in memory, so a cached evaluation reads nothing from the database and writes its one decision row. An approval increments the rule set's `cache_epoch` and sends `pg_notify('horus_rle_cache', ...)` in its own transaction; PostgreSQL delivers the notification after commit, and the approving replica drops its own entry at the same moment.

| Path | When it applies | Bound |
|---|---|---|
| Notification | `cache.listen-notify: true` on a direct connection. One connection of the host's pool stays open for `LISTEN` for the replica's lifetime; size the pool with one connection to spare | About 1 s |
| Epoch check | Always, every `cache.epoch-check-interval` | The interval, 5 s by default |
| Hand-over | A published version with a later `effectiveFrom` takes over at that instant on every replica, with no notification | Exact |

Behind PgBouncer or another pooler in transaction mode, `LISTEN` does not work: the pooler hands each transaction to any server connection and drops notifications. Set `cache.listen-notify: false` there. No session connection is opened, and the epoch check alone brings every replica to a new version within the interval. Sending the notification is transactional, so approvals work through the pooler either way. With the JDBC driver behind a transaction-mode pooler, also set `prepareThreshold=0` on the URL unless the pooler supports prepared statements.

If the listener loses its connection (a failover, a restart of PostgreSQL), it logs one warning, retries every second, and logs one line when it listens again; it runs one epoch check on reconnect, because PostgreSQL does not replay notifications sent while it was away.

No failure of one iteration ends the listener or the epoch check: any error, including an `Error` from a library, is logged and the loop carries on. Only a JVM-fatal error (`OutOfMemoryError`, `StackOverflowError`) stops them; the `ruleEngine` health contributor then reports `DOWN` with `cacheEpochCheck: STOPPED`, so the orchestrator restarts the host. The detail `cacheEpochCheckLastSuccess` shows the time of the last successful epoch check (`never` before the first).

| Meter | Prometheus | Meaning |
|---|---|---|
| `horus.rle.cache.size` | `horus_rle_cache_size` | Compiled entries held; at most `cache.max-entries` |
| `horus.rle.cache.evictions` | `horus_rle_cache_evictions_total` | Entries that left the cache, tag `cause`: `size`, `epoch` or `notify` |

The meters are registered only when the host has Micrometer on its classpath, for example through Spring Boot Actuator. No meter carries a tenant or a rule set.

The engine is tested behind PgBouncer in transaction mode on every build (CI job `pooler`): the whole rule set lifecycle, idempotent replays, the retirement sweep and partition maintenance from two replicas at once, with every statement checked for session state. The engine sends no session `SET`, no `LISTEN` (with `cache.listen-notify: false`), no session advisory lock and no named `PREPARE`. Run the Flyway migrations on a direct connection, or set `migrate-on-start: false` and migrate from a job that has one: Flyway's own lock is held for the session.

## Evaluation metrics

| Meter | Prometheus | Tags | Meaning |
|---|---|---|---|
| `horus.rle.evaluations` | `horus_rle_evaluations_seconds_count`, `_sum`, `_max` | `outcome`: `matched`, `no_match`, `failed`, `refused` | Every evaluation call and its duration, including the decision log write |
| `horus.rle.evaluation.errors` | `horus_rle_evaluation_errors_total` | `code`: the error code, for example `RULE_SET_NOT_FOUND`, `PERMISSION_DENIED`, `EVALUATION_ERROR`, `UNIQUE_HIT_VIOLATION` | Calls that failed or were refused |
| `horus.rle.lifecycle.retired` | `horus_rle_lifecycle_retired_total` | `reason`: `SUPERSEDED`, `MANUAL` | Versions retired, counted once the retirement commits |

`failed` is a decision that was logged with an error outcome; `refused` is a call refused before anything was logged, such as an unknown rule set, a missing permission or an invalid input. A `code` tag holds only engine error codes; any other value is reported as `OTHER`, so no tag ever carries a tenant, a rule set, a caller or an input. Percentiles are off by default; turn them on for the evaluation timer with `management.metrics.distribution.percentiles-histogram.horus.rle.evaluations: true`.

A host that wants other meters declares its own bean named `ruleEngineEvaluationMetrics`, which replaces the engine's.

## Evaluation speed

Every build runs the JMH benchmarks in `examples/rule-engine-benchmarks` on JDK 21 (CI job `benchmark`) and publishes the report in the job summary, with the JSON result kept for 90 days.

| Requirement | Benchmark | Budget | Gate |
|---|---|---|---|
| `TR-PERF-01` | One evaluation of a cached 200-row `first` table, input reaching the default row, no log write | p99 under 100 us | Fails the build |
| `TR-PERF-02` | Reading and compiling a 1,000-row table from YAML | p99 under 500 ms | Reported |

To run them locally:

```bash
mvn -pl examples/rule-engine-benchmarks -am install -Dcockpit.bundle.skip=true
cd examples/rule-engine-benchmarks
mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
  -Dmdep.includeScope=runtime -Dmdep.outputFile=target/classpath.txt
java -cp "target/classes:$(cat target/classpath.txt)" org.openjdk.jmh.Main -rf json -rff target/jmh-result.json
python3 ../../tools/benchmark-report.py target/jmh-result.json
```

The numbers hold for a cached version after warm-up. First-call latency, database and host transaction time, host GC pauses and other hardware are not part of the budget.

## Host beans the starter picks up

| Bean | Default | Override when |
|---|---|---|
| `TenantResolver` | Reads the `tenancy.claim` attribute of the authenticated principal in the security context: a validated token or a server-side session login. A call with no resolvable tenant gets `401 UNAUTHENTICATED` | Tenant comes from somewhere else, such as your user directory, realm, path or subdomain. No header-based resolver ships; a resolver you write that trusts a request header is your risk Built in `io.github.dhluong90.bpm.host.spi`; see [[Host Metadata and Permissions]] |
| `CallerResolver` | Principal name | Audit needs a different identity |
| `RuleFunctionRegistry` | Every `ExpressionFunction` bean of the host; none otherwise, so only the built-in functions | The host registers its own pure functions for rule cells; see [[Rule Engine Expression Language]]. Registered per host, never per tenant |
| `RuleEventPublisher` | Spring `ApplicationEventPublisher` | Events go to an outbox or a bus |
| `CurrencyRegistry` | ISO 4217 scales from the JDK | A currency has no ISO scale (for example `XAU`) or needs another scale |
| `DecisionExplainer` | Explanation text from a message catalogue per language (`en`, `vi`), default language `explain.default-language` | House wording beyond what catalogue overrides allow; the structured reasons stay the same |
| `LifecycleGuard` | Allows every transition | An extra veto, such as a change ticket on retire; it cannot let through what the engine refuses |
| `Clock` | System UTC | Tests and replays. The engine's only source of time: the default for `DecisionRequest.clock`, partition creation and the health check. The engine uses the host's bean when there is exactly one and never registers a `Clock` bean of its own |

The engine's default beans are `@ConditionalOnMissingBean`, so a host bean replaces them (see [[Configuration and Extension Points]]). `Clock` is read from the host and has no engine bean. The starter never component-scans the host. Today `Clock` and `TenantResolver` are read; the others arrive with 0.1.

## Permissions

| Permission | Grants |
|---|---|
| `rules:evaluate` | `:evaluate`, `:evaluate-batch` on published versions |
| `rules:read` | Read rule sets, versions, diffs, exports |
| `rules:author` | Create and edit drafts, validate, import, `:request-publish`, `:withdraw` of one's own request |
| `rules:approve` | `:approve`, `:reject`, `:retire`. Approval by the requester is refused regardless |
| `rules:simulate` | `:simulate`, set `clock` on `:evaluate` |
| `rules:pin` | Evaluate one named approved version (`/versions/{v}:evaluate`). No role holds it by default; grant it only to a service identity such as the Process Engine's. A retired version is refused unless `evaluation.pin-retired` is `true` |
| `rules:log:read` | Read the decision log and explanations, and replay a logged decision |
| `rules:masked:search` | Find decisions by the plain value of a masked input. Not implied by `rules:log:read`; grant it to as few people as possible |

### Mapping permissions to the host's authorities

The REST layer checks `hasAuthority('<permission>')` on the authenticated principal. Give the principal the permission strings as `GrantedAuthority` values; nothing else is needed. Two ways to do that:

| Way | How |
|---|---|
| Authorities from the token | A `JwtAuthenticationConverter` (or your OIDC mapper) emits the permission strings directly, for example from a `permissions` claim |
| Roles to permissions | `security.role-permissions` maps the host's role authorities to engine permissions; the starter adds the mapped permissions to the principal for engine endpoints only. `/api/bpm/me` lists them already |

```yaml
horus.bpm.rules.security.role-permissions:
  ROLE_RISK_ANALYST: [rules:read, rules:author, rules:simulate, rules:log:read]
  ROLE_RISK_MANAGER: [rules:read, rules:approve, rules:log:read]
  ROLE_SERVICE:      [rules:evaluate]
```

The endpoint-to-permission table is in [[Rule Engine Decision API]]. To see what a caller holds, call `GET /api/bpm/me` ([[Host Metadata and Permissions]]).

## Local development

This section describes Rule Engine 0.1. A host on the `dev` profile can run without an identity provider. The example hosts use this configuration:

```yaml
spring.profiles.active: dev
horus.bpm.rules:
  definitions: classpath:rules/*.yaml
  definitions-tenant: dev
  definitions-auto-publish: true          # dev profile only
horus.bpm.tenancy.dev-tenant: dev         # dev profile only; every call without a tenant runs as tenant dev
```
```java
@Bean @Profile("dev")
UserDetailsService devUsers(PasswordEncoder enc) {
  return new InMemoryUserDetailsManager(
    User.withUsername("dev").password(enc.encode("dev"))
        .authorities("rules:evaluate", "rules:read", "rules:author", "rules:approve", "rules:simulate", "rules:log:read").build(),
    User.withUsername("checker").password(enc.encode("checker")).authorities("rules:read", "rules:approve").build());
}
```
With HTTP basic enabled, `curl -u dev:dev ...` holds `rules:evaluate` and runs as tenant `dev`. Maker-checker still applies: `dev` requests publication and `checker` approves. Outside the `dev` profile the starter refuses to start with `definitions-auto-publish: true`, `maker-checker.enabled: false` or any `tenancy.dev-tenant` value.
