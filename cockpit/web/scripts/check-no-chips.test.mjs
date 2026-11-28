import { describe, expect, it } from 'vitest';
import { checkAll, findChips } from './check-no-chips.mjs';

describe('no chips', () => {
  it('flags a status class that is a rounded box with a fill', () => {
    expect(findChips('.badge { padding: 0 8px; border-radius: 999px; background: var(--ok-soft); }')).toEqual(['.badge']);
  });

  it('flags a rounded base class and its filled modifier together', () => {
    expect(findChips('.tag { border-radius: var(--radius-pill); }\n.tag--demo { background: var(--warn-soft); }')).toEqual(['.tag']);
  });

  it('flags a rounded box with a border and no fill', () => {
    expect(findChips('.service-pill { border: 1px solid var(--line); border-radius: 12px; }')).toEqual(['.service-pill']);
  });

  it('accepts a status that is text, a dot swatch and a card', () => {
    expect(findChips('.status { color: var(--ok); font-weight: 600; }\n.pdiff__swatch { border-radius: 50%; background: var(--ok); }\n.pillar { border: 1px solid var(--line); border-radius: 16px; }')).toEqual([]);
  });

  it('finds none in the cockpit and docs site stylesheets', () => {
    expect(checkAll()).toEqual([]);
  });
});
