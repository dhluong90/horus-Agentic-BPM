import { describe, expect, it } from 'vitest';
import { checkAll, EXEMPT, findChips, PENDING } from './check-no-chips.mjs';

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

  it.each(['cue', 'label', 'note', 'legend', 'meta'])('flags a %s class that is a rounded box with a fill', (name) => {
    expect(findChips(`.${name} { border-radius: 8px; background: var(--surface-3); }`)).toEqual([`.${name}`]);
  });

  it('flags a status inside a tile or a node: the word in the name does not exempt it', () => {
    expect(findChips('.tile__status { border-radius: 999px; background: var(--ok-soft); }\n.node__count { border-radius: 8px; border: 1px solid var(--line); }')).toEqual(['.tile__status', '.node__count']);
  });

  it('accepts a status that is text and a card', () => {
    expect(findChips('.status { color: var(--ok); font-weight: 600; }\n.pillar { border: 1px solid var(--line); border-radius: 16px; }')).toEqual([]);
  });

  it('accepts a class named in the exemptions and nothing else beside it', () => {
    const css = '.status-dot { border-radius: 50%; background: var(--ok); }\n.status-text { border-radius: 50%; background: var(--ok); }';
    expect(findChips(css, ['.status-dot'])).toEqual(['.status-text']);
  });

  it('accepts the square annotation backing of a diagram note (#649): a fill without a corner', () => {
    expect(findChips('.dg-note__surface { fill: var(--surface); background: var(--surface); stroke: none; }')).toEqual([]);
  });

  it('names a reason for every exemption', () => {
    for (const entry of EXEMPT) expect(entry.why.length).toBeGreaterThan(20);
  });

  it('keeps no pending chip: the guard covers everything', () => {
    expect(PENDING).toEqual([]);
  });

  it('finds none in the cockpit and docs site stylesheets, and no unused exemption', () => {
    expect(checkAll()).toEqual([]);
  });
});
