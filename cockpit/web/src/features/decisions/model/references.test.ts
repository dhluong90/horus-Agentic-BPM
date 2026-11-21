import { describe, expect, it } from 'vitest';
import { fileSize, maskedValue, monthLabel, monthOf, monthPeriod, shortHash, shortId } from './references';

describe('references', () => {
  it('CKP_072_a_masked_value_shows_its_key_and_a_short_reference_never_the_value', () => {
    expect(maskedValue('hmac:k1:9f4a6c1e0b7d2c4a6e8f0a1b3c5d7e9f1a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d')).toEqual({
      keyId: 'k1', reference: '9f4a6c', full: 'hmac:k1:9f4a6c1e0b7d2c4a6e8f0a1b3c5d7e9f1a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d',
    });
    // The older form without a key id still reads as masked.
    expect(maskedValue(`hmac:${'ab'.repeat(32)}`)?.reference).toBe('ababab');
    // Plain values, and text that only looks like a hash, are not masked values.
    expect(maskedValue('GOLD')).toBeUndefined();
    expect(maskedValue('hmac:k1:short')).toBeUndefined();
    expect(maskedValue({ currency: 'VND', amount: 1 })).toBeUndefined();
  });

  it('CKP_072_ids_and_hashes_are_shortened_for_the_eye_only', () => {
    expect(shortId('01KF2Q8Z0RQ6V2Y8M1C4N5P7T9')).toBe('01KF2Q…P7T9');
    expect(shortHash('9f4a6c1e0b7d2c4a6e8f0a1b3c5d7e9f1a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d')).toBe('9f4a6c1e…');
  });

  it('CKP_071_a_month_is_one_period_of_at_most_31_days_in_utc', () => {
    expect(monthPeriod('2026-11')).toEqual({ from: '2026-11-01T00:00:00Z', to: '2026-12-01T00:00:00Z' });
    expect(monthPeriod('2026-12')).toEqual({ from: '2026-12-01T00:00:00Z', to: '2027-01-01T00:00:00Z' });
    expect(monthOf(new Date('2026-11-30T23:59:59Z'))).toBe('2026-11');
    expect(monthLabel('2026-11', 'en')).toBe('November 2026');
    expect(monthLabel('2026-11', 'vi')).toMatch(/11.*2026/);
  });

  it('CKP_074_file_sizes_read_for_people', () => {
    expect(fileSize(812, 'en')).toEqual({ value: '812', unit: 'bytes' });
    expect(fileSize(4812, 'en')).toEqual({ value: '4.7', unit: 'KB' });
    expect(fileSize(4812, 'vi')).toEqual({ value: '4,7', unit: 'KB' });
    expect(fileSize(2_500_000, 'en')).toEqual({ value: '2.4', unit: 'MB' });
  });
});
