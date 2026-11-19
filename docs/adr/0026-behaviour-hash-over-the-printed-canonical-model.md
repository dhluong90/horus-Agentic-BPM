# ADR-0026: The behaviour hash covers the printed canonical model; Git comparison uses the source hash

- Status: accepted
- Date: 2026-11-19
- Deciders: maintainers
- Module: rule-engine, process-engine

## Context
ADR-0018 makes the DMN and BPMN semantic models canonical and our JSON their serialisation. `standards-compliance.md` section 3.4 promises that a definition exported from schema version 1 and imported again keeps its hash. The first DMN import and export (pull request #145) showed that the promise did not hold. `canonical_hash` was the SHA-256 of the RFC 8785 form of the document *as authored*, so every expression cell was hashed as typed, while the DMN reader writes each cell in one normal form. A valid YAML table therefore came back from DMN with a different hash, although its meaning was unchanged. Examples: `x >= 3` came back as `>= 3`, `in ['VN','SG']` as `in ['VN', 'SG']`, an instant written with `+07:00` as the same instant in `Z`, and `12.5 USD` as `12.50 USD`. Money cells also lost the hash on a DMN, import, export, import round trip.

Nothing has been released or logged in production yet. 0.1.0 is the first release.

## Decision
1. **The behaviour hash is computed over the canonical model as the engine prints it.** `canonical_hash` (rule sets) and `definition_hash` (processes) are the SHA-256 of the RFC 8785 form of the canonical JSON *written from the model*. In that JSON, every expression and every value is printed in the normal form of decision 2. The rest of section 3.4 is unchanged: the hash is taken in the stored schema version, never recomputed, without layout, foreign extensions or `effectiveFrom`. Two documents with the same meaning in any serialisation (YAML, JSON, DMN XML, later BPMN XML) have the same hash. Two documents with different behaviour never do.
2. **The normal form.** It merges only texts that are the same behaviour.
   - **JSON numbers.** A JSON number appears in the canonical document only for an integer within plus or minus 2^53 - 1. Every other number (a decimal output, a default, a constant) is a JSON string in plain `BigDecimal` form with its scale kept (`toPlainString`, never `stripTrailingZeros`): `"0.1"`, `"0.10"` and `"0.1000000000000000055511151231257827"` are three different values. Import reads numbers exactly, never through `double`. A JSON number that an RFC 8785 writer cannot reproduce exactly is refused with `422 VALIDATION_FAILED` naming its line (I-JSON, RFC 7493). The canonical writer never converts a number to `double`.
   - **Cells.** A `when` cell drops the column name only when the cell is a comparison whose left operand is that column's own input (`x >= 3` in column `x` becomes `>= 3`). A comparison of another input or of two inputs (`y >= 3` or `x > y` in column `x`) keeps its operands as written, in normal spacing.
   - **Lists** are printed with `, ` separators.
   - **Strings** are single-quoted. `'` and `\` are escaped. C0 and C1 control characters and Unicode format characters (category Cf, including U+200B to U+200F and U+2060 to U+2064) are printed as `\uXXXX` escapes. No Unicode normalisation is ever applied, because strings compare by code point and NFC and NFD forms are different behaviours. Bidirectional controls (U+202A to U+202E, U+2066 to U+2069) are refused at import in expressions, literals and identifiers.
   - **Instants.** The M1 `datetime` type (an `Instant`) is printed in UTC with `Z`. Zoned FEEL values from S1 (`date and time` with an offset or zone id) keep their offset or zone id, because day, hour and DST arithmetic can observe them.
   - **Money** is printed at the currency's minor-unit scale (`12.50 USD`, `10000 VND`). More fraction digits than the currency allows is refused at import with the line, never rounded.
   - **Null outputs.** An explicit `null` output cell for an output **without** a `default` is printed as the omitted cell, because both evaluate to `null`. For an output **with** a `default`, an explicit `null` is kept, because the omitted cell evaluates to the default and the two behave differently (amended 19 Nov, review of #145).
   The printer and parser are the same ones the DMN reader uses (`V1Forms`), so YAML and DMN share one normal form.
3. **The printer and the parser are frozen per schema version, as one contract.** The hash identifies text, and the parser decides what that text does. Neither may change behaviour inside a schema version. The following gates enforce it:
   - **Golden file, both directions.** For each schema version, cell text maps to a tree dump, and tree to text. Its own SHA-256 is pinned in the test, so any edit fails CI unless the schema version changes. CODEOWNERS requires a maintainer review for it.
   - **Coverage gate.** Every grammar production and every escape class has at least one golden entry. CI fails when a production has none.
   - **Property tests over generated trees**, in the `RLE-021` harness: `parse(print(t)) == t`, `print(parse(print(t))) == print(t)`, and the same evaluation result for `t` and `parse(print(t))` on generated inputs and clock.
   - **Corpus of earlier versions.** CI keeps canonical documents from every earlier schema version and checks that each still parses to the same tree dump.
   The Process Engine's `ExpressionLanguage` printer and parser follow the same rule from M2, with their own golden file.
4. **What runs is what was hashed and approved.**
   - The engine compiles a version only by parsing its stored `canonical_json`, on every replica and after every restart. It never compiles from the import-time model or from the source document.
   - At import, the engine checks that `parse(print(model))` is structurally equal to `model`. If not, it refuses with `422 VALIDATION_FAILED` and stores nothing (fail closed).
   - Before compiling a version, the engine checks that the SHA-256 of the RFC 8785 form of the stored `canonical_json` equals `canonical_hash`. If not, it refuses to serve that version: the evaluation fails with `EVALUATION_ERROR`, its detail naming the failed integrity check, and the engine raises an alert and writes an audit entry.
   - `canonical_json` is stored as `JSONB`. Because decimals are strings and integers are within plus or minus 2^53 - 1, reading it back and re-serialising it is exact.
5. **"Was this version imported from that file?" uses `source_hash`.**
   - `source_hash` is the SHA-256 of the raw request bytes, taken before any decoding or trimming.
   - Import refuses a source that is not valid UTF-8, contains NUL, or starts with a byte order mark. So `source_document TEXT` holds exactly the bytes that were hashed, and `?include=source` returns them.
   - A pipeline hashes the committed blob (`git show <commit>:<path> | sha256sum`), not the working-tree file, which line-ending settings and filters can change.
   - A matching `source_hash` proves where the version came from. It does not by itself prove what runs: that is decision 4 together with `canonical_hash`. A drift check compares both hashes.
   - `canonical_hash` means "same behaviour". It stays the hash approved under maker-checker, diffed, exported and logged.
6. **What the approver sees.**
   - The source document is kept byte for byte, comments and authoring style included.
   - The canonical document and the row-level diff show the normal form, which is exactly what is hashed, approved and run.
   - The diff marks every changed literal that contains a non-ASCII character, so a look-alike character or an escaped format character is visible to the approver.

## Options considered
1. **Carry the original YAML text on each exported DMN entry** (a `horus:` attribute, used on import when it parses to the same tree).
   - For: YAML hashes stay as authored.
   - Against: the round trip holds only through tools that keep unknown attributes, and many modellers drop them. Every exported entry carries a second copy of itself. A modeller who edits the FEEL text silently loses the attribute's meaning, and the two copies can disagree.
2. **Narrow section 3.4 to tables already in normal form, and record a deviation.**
   - For: no code.
   - Against: a valid YAML table and its DMN export would have different hashes for the same meaning. Hash, diff and approval would then depend on formatting, and the deviation would be permanent once 0.1.0 logs decisions.
3. **Hash the printed canonical model (chosen).**
   - For: one hash per meaning across every serialisation, as ADR-0018 intends (the canonical JSON *is* the model's serialisation). It is formatting-insensitive, so whitespace or style edits do not create a "changed" version. The DMN round trip holds through any tool. It is cheap now, because no version is stored outside development databases.
   - Against: a pipeline cannot compute `canonical_hash` with a generic JSON canonicaliser, which is why Git comparison moves to `source_hash` (decision 5). The printer and the parser become part of the hash contract, which is why both are frozen together per schema version (decision 3).

## Consequences
- **Updated in this change:**
  - `standards-compliance.md` section 3.4;
  - `TR-DEF-04` and `TR-STD-03`;
  - `RLE-020` and `RLE-024`, and the new `RLE-045` with its negative cases;
  - rule engine DESIGN: hash identity, effective date, compilation from `canonical_json` and `source_document`;
  - process engine DESIGN section 5.2;
  - the wiki sentence on comparing a version with Git.
- **The current canonical writer has a number collision.** It prints numbers through `double` (`CanonicalJson`), so `0.1` and `0.1000000000000000055511151231257827` share a hash today. The example `card-fee.rule-set.json` contains the second value. Pull request #145 rewrites the canonical writer and fixes this, together with the example file.
- **Pull request #145 implements decisions 1 to 6:**
  - the canonical writer prints from the model, with numbers exact;
  - the import checks of decisions 2, 4 and 5;
  - the load-time hash check;
  - the golden files, the coverage gate, the property tests and the corpus;
  - tests named by `RLE-020`, `RLE-024` and `RLE-045`, including the QA cases of review 5969673183 and the security cases of review 5969746899;
  - the RFC 8785 vector of `RLE-020`, recomputed over the printed form.
- **Follow-up issue:** a no-write validate operation, an API call and a command-line tool on the same printer, that returns `canonical_hash` and `source_hash` for a file. Until it exists, a pipeline reads `canonical_hash` from the draft after import.
- **Development databases.** Versions stored before this change keep their old hashes and fail the load-time check of decision 4. Delete them, or import them again, before 0.1.0. Nothing is released yet.
- **Process definitions** follow the same rules from M2, for gateway conditions, timer expressions and mappings, through the `ExpressionLanguage` printer.

## History
- 2026-11-19: accepted, in answer to QA review 5969673183 on #145.
- 2026-11-19: revised after security review 5969746899 on #150. Changes: exact numbers (H1); compilation only from the stored canonical document, with a round-trip check at import and a hash check at load (M1); the parser frozen with the printer, plus property tests, a coverage gate and a pinned golden file (M2, M3); narrower normal-form rules for instants, numbers, column names and strings (M4); escaped and refused characters, and non-ASCII marking in the diff (M5); raw-byte source hash, the committed blob, and UTF-8 only (L1).
- 2026-11-19: decision 2 gains the null-output rule (explicit null without a default prints as the omitted cell), confirmed in the review of #145.
