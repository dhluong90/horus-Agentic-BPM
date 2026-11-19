import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(join(process.cwd(), 'src', 'components', 'tokens.css'), 'utf8');

function declarations(block: string): string[] {
  return block.split(';').map((part) => part.trim()).filter(Boolean).sort();
}

describe('design tokens', () => {
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
