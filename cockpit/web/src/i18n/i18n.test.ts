import { describe, expect, it } from 'vitest';
import { en } from './en';
import { format } from './I18nProvider';
import { vi } from './vi';

describe('dictionaries', () => {
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
