import { describe, expect, it } from 'vitest';
import { ruleSetVersion, versionDiff, type InputMasking } from '../../../api/rules/schemas';
import { maskingMark, maskRemoved } from './masking';

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

  it('CKP_037_the_header_marks_a_changed_input_and_nothing_for_an_unchanged_one', () => {
    expect(maskingMark(item('MASKED', 'NOT_MASKED'))).toEqual({ kind: 'changed', from: 'MASKED', to: 'NOT_MASKED', removed: true });
    expect(maskingMark(item('NOT_MASKED', 'MASKED'))).toEqual({ kind: 'changed', from: 'NOT_MASKED', to: 'MASKED', removed: false });
    expect(maskingMark(item('MASKED', 'MASKED'))).toEqual({ kind: 'none' });
    expect(maskingMark(item(null, 'NOT_MASKED_BY_DEFAULT'))).toEqual({ kind: 'added', to: 'NOT_MASKED_BY_DEFAULT' });
    expect(maskingMark(undefined)).toEqual({ kind: 'none' });
  });
});

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
    expect(parsed.warnings).toEqual([{ code: 'UNMASKED_PERSONAL_DATA', input: 'nationalId', matchedName: 'national id', message: 'm' }]);
  });

  it('CKP_037_a_masking_entry_with_an_unknown_status_is_dropped_and_the_others_kept', () => {
    const parsed = versionDiff.parse({ ruleSet: 'customer-screening', version: 2, base: 1, masking: [
      { input: 'nationalId', from: 'MASKED', to: 'NOT_MASKED_BY_DEFAULT', changed: true, personalDataName: 'national id' },
      { input: 'email', from: 'MASKED', to: 'TOKENISED', changed: true, personalDataName: null },
      { input: 'segment', from: 'NOT_MASKED', to: 'NOT_MASKED' },
      { input: 'phoneNumber', from: 'MASKED', to: 'MASKED', changed: false, personalDataName: null },
    ] });
    expect(parsed.masking.map((item) => item.input)).toEqual(['nationalId', 'phoneNumber']);
  });

  it('CKP_037_a_diff_without_masking_gives_an_empty_column', () => {
    expect(versionDiff.parse({ ruleSet: 'card-fee', version: 2, base: 1, rows: [] }).masking).toEqual([]);
  });
});
