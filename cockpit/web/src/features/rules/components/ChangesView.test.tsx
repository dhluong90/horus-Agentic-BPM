import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { RuleSetDefinition } from '../../../api/rules/schemas';
import { I18nProvider } from '../../../i18n/I18nProvider';
import { ChangesView } from './ChangesView';

const base: RuleSetDefinition = {
  schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first',
  inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }],
  rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }, { id: 'R2', when: {}, then: { fee: '50000 VND' } }],
};
// R1's GOLD now carries a Cyrillic capital O (U+041E): it reads like GOLD and never matches it.
const next: RuleSetDefinition = { ...base, rules: [{ id: 'R1', when: { tier: "== 'GОLD'" }, then: { fee: '0 VND' } }, base.rules[1] ?? { id: 'R2', when: {}, then: {} }] };

describe('approver changes', () => {
  it('ADR_0026_changed_literals_with_non_ascii_characters_are_marked_under_one_summary_line', () => {
    render(
      <I18nProvider initialLocale="en">
        <ChangesView definition={next} base={base} baseVersion={1} baseInEffect />
      </I18nProvider>,
    );
    expect(screen.getByText('Non-ASCII: О U+041E')).toBeInTheDocument();
    expect(screen.getAllByText(/Non-ASCII/)).toHaveLength(1);
    expect(screen.getByText('0 added, 1 changed, 0 removed, 1 unchanged')).toBeInTheDocument();
    expect(screen.getByText('Compared with Version 1, in effect now')).toBeInTheDocument();
    expect(screen.queryByText(/Aid:/)).not.toBeInTheDocument();
    expect(screen.getAllByRole('table')).toHaveLength(1);
  });
});
