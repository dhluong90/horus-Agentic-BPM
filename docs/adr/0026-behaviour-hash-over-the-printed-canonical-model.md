# ADR-0026: The behaviour hash covers the printed canonical model; Git comparison uses the source hash

- Status: accepted
- Date: 2026-11-19
- Deciders: maintainers
- Module: rule-engine, process-engine

## Context
ADR-0018 makes the DMN and BPMN semantic models canonical and our JSON their serialisation. `standards-compliance.md` section 3.4 promises that a definition exported from schema version 1 and imported again keeps its hash. The first DMN import and export (pull request #145) showed that the promise did not hold. `canonical_hash` was the SHA-256 of the RFC 8785 form of the document *as authored*, so every expression cell was hashed as typed, while the DMN reader writes each cell in one normal form. A valid YAML table therefore came back from DMN with a different hash, although its meaning was unchanged. Examples: `x >= 3` came back as `>= 3`, `in ['VN','SG']` as `in ['VN', 'SG']`, an instant written with `+07:00` as the same instant in `Z`, and `12.5 USD` as `12.50 USD`. Money cells also lost the hash on a DMN, import, export, import round trip.

Nothing has been released or logged in production yet. 0.1.0 is the first release.

## Decision
1. **The behaviour hash is computed over the canonical model as the engine prints it.** `canonical_hash` (rule sets) and `definition_hash` (processes) are the SHA-256 of the RFC 8785 form of the canonical JSON *written from the model*. In that JSON every expression is printed in its normal form: the parsed tree, printed by the engine's canonical printer. The rest of section 3.4 is unchanged: the hash is taken in the stored schema version, never recomputed, without layout, foreign extensions or `effectiveFrom`. Two documents with the same meaning in any serialisation (YAML, JSON, DMN XML, later BPMN XML) have the same hash.
2. **The normal form, per expression kind:**
   - a `when` cell is printed as a unary test without the column name (`>= 3`, `between 1 and 5`, `is not null`, `!= 'VN'`);
   - lists are printed with `, ` separators and single-quoted strings;
   - instants are printed in UTC with `Z`;
   - money is printed at the currency's minor-unit scale (`12.50 USD`, `10000 VND`); more fraction digits than the currency allows is refused at import with the line;
   - numbers are printed in their plain form without exponent.
   The printer and parser are the same ones the DMN reader uses (`V1Forms`), so YAML and DMN share one normal form.
3. **The printer is frozen per schema version.** Its output for `rule-set.v1` is fixed by a golden test file of cell to normal form. Any change to it is a new schema version, never a patch. A stored hash is never recomputed (`TR-STD-03`), so stored versions are unaffected either way.
4. **"Does production match Git" uses `source_hash`.** That is the SHA-256 of the bytes as received (`TR-STD-01`), so a pipeline compares it with `sha256sum <file>`, which is still one string comparison (PD-036). `canonical_hash` answers a different question, "same behaviour", and stays the hash approved under maker-checker, diffed, exported and logged.
5. **The stored source does not change.** The document is kept byte for byte, comments and authoring style included, and `?include=source` returns it. The canonical document and the row-level diff show the normal form, which is exactly what is hashed and approved.

## Options considered
1. **Carry the original YAML text on each exported DMN entry** (a `horus:` attribute, used on import when it parses to the same tree).
   - For: YAML hashes stay as authored.
   - Against: the round trip holds only through tools that keep unknown attributes, and many modellers drop them. Every exported entry carries a second copy of itself. A modeller who edits the FEEL text silently loses the attribute's meaning, and the two copies can disagree.
2. **Narrow section 3.4 to tables already in normal form, and record a deviation.**
   - For: no code.
   - Against: a valid YAML table and its DMN export would have different hashes for the same meaning. Hash, diff and approval would then depend on formatting, and the deviation would be permanent once 0.1.0 logs decisions.
3. **Hash the printed canonical model (chosen).**
   - For: one hash per meaning across every serialisation, as ADR-0018 intends (the canonical JSON *is* the model's serialisation). It is formatting-insensitive, so whitespace or style edits do not create a "changed" version. The DMN round trip holds through any tool. It is cheap now, because no version is stored outside development databases.
   - Against: a pipeline cannot compute `canonical_hash` with a generic JSON canonicaliser, which is why Git comparison moves to `source_hash` (decision 4). The printer becomes part of the hash contract, which is why it is frozen (decision 3).

## Consequences
- `standards-compliance.md` section 3.4, `TR-DEF-04` and `TR-STD-03`, `RLE-020` and `RLE-024`, rule engine DESIGN "Hash identity" and process engine DESIGN section 5.2 are updated in the same change. So is the wiki sentence on comparing a version with Git.
- `RLE-039` is new: YAML cells in non-normal form and their DMN export have one hash.
- Pull request #145 implements it. The canonical writer prints from the model, the money import prints at the currency scale, and the tests named by `RLE-020`, `RLE-024` and `RLE-039` include the QA cases of review 5969673183. The hand-written RFC 8785 vector of `RLE-020` is recomputed over the printed form.
- Development databases with versions stored before this change keep their old hashes (never recomputed). Re-importing those documents gives a new version with the new hash, which is expected before 0.1.0.
- Process definitions follow the same rule from M2, for gateway conditions, timer expressions and mappings, through the `ExpressionLanguage` printer.
