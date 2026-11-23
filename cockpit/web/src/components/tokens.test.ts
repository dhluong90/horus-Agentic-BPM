import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(join(process.cwd(), 'src', 'components', 'tokens.css'), 'utf8');

function declarations(block: string): string[] {
  return block.split(';').map((part) => part.trim()).filter(Boolean).sort();
}

const light = css.slice(0, css.indexOf(':root[data-theme="dark"]'));

function token(name: string): string {
  const hex = new RegExp(`${name}: (#[0-9A-Fa-f]{6})`).exec(light)?.[1];
  if (!hex) throw new Error(`light token ${name} is not a hex colour`);
  return hex;
}

function luminance(hex: string): number {
  const [r = 0, g = 0, b = 0] = [1, 3, 5].map((i) => {
    const c = parseInt(hex.slice(i, i + 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(a: string, b: string): number {
  const la = luminance(token(a));
  const lb = luminance(token(b));
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

describe('design tokens', () => {
  it('TR_UI_03_light_theme_is_green_with_a_grey_page_and_white_cards', () => {
    expect(token('--bg')).toBe('#F7F7F7');
    expect(token('--surface')).toBe('#FFFFFF');
    expect(token('--line')).toBe('#ECEEED');
    expect(token('--primary-fill')).toBe('#158370');
    expect(token('--on-primary')).toBe('#FFFFFF');
    expect(token('--spark-max')).toBe('#0E4D41');
  });

  it('TR_UI_03_light_theme_has_no_dark_surface', () => {
    for (const name of ['--surface-contrast', '--rail', '--surface', '--bg']) {
      expect(luminance(token(name)), name).toBeGreaterThan(0.8);
    }
  });

  it('TR_UI_03_light_text_pairs_meet_4_5_to_1', () => {
    const pairs: Array<[string, string]> = [
      ['--ink', '--surface'], ['--muted', '--surface'], ['--muted', '--surface-3'],
      ['--on-primary', '--primary-fill'], ['--on-primary', '--primary-hover'],
      ['--accent', '--surface'], ['--accent', '--accent-soft'],
      ['--on-contrast', '--surface-contrast'], ['--on-contrast-muted', '--surface-contrast'],
      ['--on-contrast-accent', '--surface-contrast'], ['--on-contrast-action', '--contrast-action'],
      ['--ok', '--ok-soft'], ['--warn', '--warn-soft'], ['--bad', '--bad-soft'],
    ];
    for (const [fg, bg] of pairs) expect(ratio(fg, bg), `${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
  });

  it('TR_UI_03_light_chart_marks_are_at_least_3_to_1_on_white', () => {
    for (const name of ['--spark-max', '--rel-1', '--rel-2', '--rel-3', '--rel-later', '--ref', '--gain', '--loss']) {
      expect(ratio(name, '--surface'), name).toBeGreaterThanOrEqual(3);
    }
  });

  it('TR_UI_03_done_warning_and_error_keep_their_own_hue', () => {
    expect(new Set([token('--ok'), token('--warn'), token('--bad'), token('--accent')]).size).toBe(4);
  });

  it('TR_UI_03_dark_tokens_for_the_system_preference_equal_the_dark_theme_tokens', () => {
    const explicit = /:root\[data-theme="dark"\] \{([^}]*)\}/.exec(css)?.[1] ?? '';
    const fallback = /:root:not\(\[data-theme="light"\]\) \{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(declarations(explicit).length).toBeGreaterThan(20);
    expect(declarations(fallback)).toEqual(declarations(explicit));
  });

  it('TR_UI_03_no_font_is_named_that_the_cockpit_does_not_ship', () => {
    expect(css).not.toMatch(/IBM Plex|@font-face|url\(/);
  });
});
