import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nameOf, resetNameWarnings, tenantName, titleFromKey, warnMissingName } from './names';

describe('human names (#510)', () => {
  beforeEach(() => { resetNameWarnings(); });

  it('CKP_510_a_supplied_display_name_wins_and_a_missing_one_falls_back_to_the_key_in_words', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(nameOf('rule set', 'card-fee', { displayName: 'Card fees' })).toBe('Card fees');
    expect(warn).not.toHaveBeenCalled();
    expect(nameOf('rule set', 'card-fee', {})).toBe('Card fee');
    expect(nameOf('rule set', 'card-fee', {})).toBe('Card fee');
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('CKP_510_the_tenant_name_comes_from_me_names', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(tenantName({ 'bank-a': 'Retail banking' }, 'bank-a')).toBe('Retail banking');
    expect(tenantName(undefined, 'bank-a')).toBe('Bank a');
    expect(warn).toHaveBeenCalledTimes(1);
    warn.mockRestore();
  });

  it('titleFromKey_reads_separators_as_spaces', () => {
    expect(titleFromKey('kyc_risk-score')).toBe('Kyc risk score');
    warnMissingName('x', 'y', () => undefined);
  });
});
