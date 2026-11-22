import { describe, expect, it } from 'vitest';
import { en } from './en';
import { format } from './I18nProvider';
import { vi } from './vi';

/** Keys of the reference dictionary that a translation lacks or leaves empty: what the coverage check names. */
function missingKeys(reference: Record<string, string>, translation: Record<string, string>): string[] {
  return Object.keys(reference).filter((key) => (translation[key] ?? '').trim() === '');
}

describe('dictionaries', () => {
  it('CKP_010_en_and_vi_i18n_coverage_complete_no_key_missing_in_vietnamese', () => {
    expect(missingKeys(en, vi)).toEqual([]);
    expect(Object.keys(vi).filter((key) => !(key in en))).toEqual([]);
  });

  it('CKP_010_a_key_missing_in_vietnamese_fails_the_check_and_is_named', () => {
    const incomplete: Record<string, string> = Object.fromEntries(Object.entries(vi).filter(([key]) => key !== 'menu.rules'));
    expect(missingKeys(en, incomplete)).toEqual(['menu.rules']);
    expect(missingKeys(en, { ...vi, 'menu.home': ' ' })).toEqual(['menu.home']);
  });

  it('TR_UI_04_english_and_vietnamese_have_the_same_keys_and_no_empty_text', () => {
    expect(Object.keys(vi).sort()).toEqual(Object.keys(en).sort());
    for (const [key, text] of [...Object.entries(en), ...Object.entries(vi)]) {
      expect(text.trim(), key).not.toBe('');
    }
  });

  it('TR_UI_04_vietnamese_keeps_every_placeholder_of_english', () => {
    const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
    const vietnamese = new Map(Object.entries(vi));
    for (const [key, text] of Object.entries(en)) {
      expect(placeholders(vietnamese.get(key) ?? ''), key).toEqual(placeholders(text));
    }
  });

  it('TR_UI_04_format_fills_named_placeholders_and_keeps_unknown_ones', () => {
    expect(format('Version {version}', { version: '0.1.0' })).toBe('Version 0.1.0');
    expect(format('In tenant {tenant}', {})).toBe('In tenant {tenant}');
  });
});
