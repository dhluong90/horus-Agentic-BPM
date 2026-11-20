import { describe, expect, it } from 'vitest';
import { ruleSetVersion, versionDiff, type InputMasking } from '../../../api/rules/schemas';
import { changeNotes, flagged, maskRemoved } from './masking';
import { structureNotes } from './versionDiff';

const item = (from: InputMasking['from'], to: InputMasking['to']): InputMasking => ({ input: 'nationalId', from, to, changed: from !== to, personalDataName: null });

describe('masking column of the diff (RLE-051)', () => {
  it('CKP_037_a_mask_removed_is_masked_before_and_not_masked_now_by_declaration_or_default', () => {
    expect(maskRemoved(item('MASKED', 'NOT_MASKED'))).toBe(true);
    expect(maskRemoved(item('MASKED', 'NOT_MASKED_BY_DEFAULT'))).toBe(true);
    expect(maskRemoved(item('MASKED_BY_DEFAULT', 'NOT_MASKED'))).toBe(true);
    expect(maskRemoved(item('NOT_MASKED', 'MASKED'))).toBe(false);
    expect(maskRemoved(item('MASKED', 'MASKED_BY_DEFAULT'))).toBe(false);
    expect(maskRemoved(item(null, 'NOT_MASKED'))).toBe(false);
    expect(maskRemoved(item('MASKED', null))).toBe(false);
  });

  it('CKP_037_the_header_flag_marks_a_change_to_not_masked_and_nothing_else', () => {
    expect(flagged(item('MASKED', 'NOT_MASKED'))).toBe(true);
    expect(flagged(item(null, 'NOT_MASKED_BY_DEFAULT'))).toBe(true);
    expect(flagged(item('NOT_MASKED', 'MASKED'))).toBe(false);
    expect(flagged(item('MASKED', 'MASKED'))).toBe(false);
    expect(flagged(undefined)).toBe(false);
  });

  it('CKP_037_a_mask_only_column_change_reads_as_its_masking_sentence_and_an_added_column_names_its_masking', () => {
    const none = { added: [], removed: [], changed: [], reordered: false };
    const diff = versionDiff.parse({
      ruleSet: 'customer-screening', version: 2, base: 1, canonicalHash: 'a'.repeat(64), baseCanonicalHash: 'b'.repeat(64), sourceHashChanged: true,
      effectiveFrom: { from: null, to: null }, header: [], outputs: none, rows: [], unchangedCount: 2,
      inputs: { added: ['email'], removed: [], changed: [{ name: 'nationalId', fields: [{ path: '/mask', from: true, to: null, nonAscii: false }] }], reordered: false },
      masking: [
        { input: 'nationalId', from: 'MASKED', to: 'NOT_MASKED_BY_DEFAULT', changed: true, personalDataName: 'national id' },
        { input: 'phoneNumber', from: 'MASKED', to: 'MASKED', changed: false, personalDataName: null },
        { input: 'email', from: null, to: 'NOT_MASKED_BY_DEFAULT', changed: true, personalDataName: 'email' },
      ],
    });
    expect(changeNotes(diff, structureNotes(diff))).toEqual([
      { kind: 'masking', name: 'nationalId', from: 'MASKED', to: 'NOT_MASKED_BY_DEFAULT', removed: true },
      { kind: 'columnAdded', group: 'when', name: 'email', masking: 'NOT_MASKED_BY_DEFAULT' },
    ]);
  });
});

const emptyColumns = { added: [], removed: [], changed: [], reordered: false };
/** A diff body as the engine sends it, without the masking column. */
const diffBody = {
  ruleSet: 'customer-screening', version: 2, base: 1, canonicalHash: 'a'.repeat(64), baseCanonicalHash: 'b'.repeat(64), sourceHashChanged: false,
  effectiveFrom: { from: null, to: null }, header: [], inputs: emptyColumns, outputs: emptyColumns, rows: [], unchangedCount: 2,
};

describe('reading warnings and the diff tolerantly', () => {
  const version = {
    ruleSet: 'customer-screening', version: 2, status: 'PENDING_APPROVAL', hitPolicy: 'FIRST', lockVersion: 1, canonicalHash: 'c'.repeat(64), createdAt: '2026-11-19T02:00:00Z',
    sourceFormat: 'YAML', sourceHash: 'd'.repeat(64),
    definition: { schemaVersion: 1, ruleSet: 'customer-screening', hitPolicy: 'first', inputs: [], outputs: [], rules: [] },
  };

  it('CKP_037_an_engine_without_warnings_means_none', () => {
    expect(ruleSetVersion.parse(version).warnings).toEqual([]);
  });

  it('CKP_037_a_warning_with_an_unknown_code_or_shape_is_dropped_and_the_rest_kept', () => {
    const parsed = ruleSetVersion.parse({ ...version, warnings: [
      { code: 'UNMASKED_PERSONAL_DATA', input: 'nationalId', matchedName: 'national id', message: 'm' },
      { code: 'SOMETHING_NEWER', input: 'x', matchedName: null, message: 'm' },
      { code: 'UNMASKED_PERSONAL_DATA' },
    ] });
    // An engine that does not mark warnings marks none; its 409 WARNINGS_NOT_ACKNOWLEDGED still names the set.
    expect(parsed.warnings).toEqual([{ code: 'UNMASKED_PERSONAL_DATA', input: 'nationalId', matchedName: 'national id', message: 'm', requiresAcknowledgement: false }]);
  });

  it('CKP_037_requires_acknowledgement_is_read_from_the_engine_per_warning', () => {
    const parsed = ruleSetVersion.parse({ ...version, warnings: [
      { code: 'UNMASKED_PERSONAL_DATA', input: 'nationalId', matchedName: 'national id', message: 'm', requiresAcknowledgement: true },
      { code: 'UNMASKED_PERSONAL_DATA', input: 'email', matchedName: 'email', message: 'm', requiresAcknowledgement: false },
    ] });
    expect(parsed.warnings.map((w) => [w.input, w.requiresAcknowledgement])).toEqual([['nationalId', true], ['email', false]]);
  });

  it('CKP_037_a_masking_entry_with_an_unknown_status_is_dropped_and_the_others_kept', () => {
    const parsed = versionDiff.parse({ ...diffBody, masking: [
      { input: 'nationalId', from: 'MASKED', to: 'NOT_MASKED_BY_DEFAULT', changed: true, personalDataName: 'national id' },
      { input: 'email', from: 'MASKED', to: 'TOKENISED', changed: true, personalDataName: null },
      { input: 'segment', from: 'NOT_MASKED', to: 'NOT_MASKED' },
      { input: 'phoneNumber', from: 'MASKED', to: 'MASKED', changed: false, personalDataName: null },
    ] });
    expect(parsed.masking.map((item) => item.input)).toEqual(['nationalId', 'phoneNumber']);
  });

  it('CKP_037_a_diff_without_masking_gives_an_empty_column', () => {
    expect(versionDiff.parse(diffBody).masking).toEqual([]);
  });
});
