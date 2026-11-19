import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { RuleSetDefinition, RuleSetVersion, VersionDiff } from '../../../api/rules/schemas';
import { I18nProvider, type Locale } from '../../../i18n/I18nProvider';
import { formatDateTime } from '../model/dates';
import { ChangesView } from './ChangesView';

const definition: RuleSetDefinition = {
  schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first',
  inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }],
  rules: [
    { id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } },
    { id: 'R3', when: { tier: "== 'CLASSIC'" }, then: { fee: '30000 VND' } },
    { id: 'R4', when: {}, then: { fee: '15000 VND' } },
  ],
};
const proposed: RuleSetDefinition = {
  ...definition,
  rules: [
    { id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } },
    { id: 'R6', when: { tier: "== 'PLATINUM'" }, then: { fee: '10000 VND' } },
    { id: 'R3', when: { tier: "== 'CLASSIC'" }, then: { fee: '35000 VND' } },
  ],
};

function version(n: number, status: RuleSetVersion['status'], def: RuleSetDefinition, effectiveFrom: string | null): RuleSetVersion {
  return {
    ruleSet: 'card-fee', version: n, status, hitPolicy: 'FIRST', effectiveFrom, lockVersion: 1, canonicalHash: String(n).repeat(64), sourceFormat: 'YAML', sourceHash: 'e'.repeat(64),
    requestedBy: 'lan', requestedAt: '2026-11-18T08:30:00Z', approvedBy: null, approvedAt: null, createdBy: 'lan', createdAt: '2026-11-18T08:00:00Z', allowedActions: [], definition: def,
  };
}

const none = { added: [], removed: [], changed: [], reordered: false };
const diff: VersionDiff = {
  ruleSet: 'card-fee', version: 2, base: 1, canonicalHash: '4be1d7a0c35e'.padEnd(64, '0'), baseCanonicalHash: '9c07e5d3b1a9'.padEnd(64, '0'), sourceHashChanged: true,
  effectiveFrom: { from: '2026-11-01T03:00:00Z', to: null }, header: [], inputs: none, outputs: none,
  rows: [
    { ruleId: 'R6', change: 'ADDED', position: 2 },
    { ruleId: 'R3', change: 'CHANGED', position: 3, basePosition: 2, moved: false, fields: [{ path: '/then/fee', from: { currency: 'VND', amount: 30000 }, to: { currency: 'VND', amount: 35000 }, nonAscii: false }] },
    { ruleId: 'R4', change: 'REMOVED', basePosition: 3 },
  ],
  unchangedCount: 1,
};

function show(d: VersionDiff = diff, next: RuleSetDefinition = proposed, locale: Locale = 'en', nextEffective: string | null = null) {
  render(
    <I18nProvider initialLocale={locale}>
      <ChangesView diff={d} base={version(1, 'PUBLISHED', definition, d.effectiveFrom.from)} next={version(2, 'PENDING_APPROVAL', next, nextEffective)} />
    </I18nProvider>,
  );
}

const row = (id: string) => {
  const header = screen.getByRole('rowheader', { name: new RegExp(`^${id}\\b`) });
  return header.closest('tr') as HTMLElement;
};

describe('side-by-side version diff', () => {
  it('CKP_002_version_diff_highlights_row_changes', () => {
    show();
    expect(screen.getAllByRole('table')).toHaveLength(1);
    expect(screen.getByText('1 added, 1 changed, 1 removed, 1 unchanged')).toBeInTheDocument();
    // One line per rule id, in the proposed order, the removed row after its former neighbour.
    expect(screen.getAllByRole('rowheader').map((th) => th.querySelector('.row-id')?.textContent)).toEqual(['R1', 'R6', 'R3', 'R4']);
    expect(row('R6').querySelector('.change-mark--added')).toHaveTextContent('Added');
    expect(within(row('R6')).getByText('Not in version 1')).toBeInTheDocument();
    expect(row('R4').querySelector('.change-mark--removed')).toHaveTextContent('Removed');
    expect(within(row('R4')).getByText('Removed in version 2')).toBeInTheDocument();
    // R3: the old fee on the left, the new fee on the right, both formatted with thousand separators.
    const changed = row('R3');
    expect(changed.querySelector('.change-mark--changed')).toHaveTextContent('Changed');
    expect(within(changed).getByText('30,000 VND').closest('td')).toHaveClass('diff-td--old');
    expect(within(changed).getByText('35,000 VND').closest('td')).toHaveClass('diff-td--mod');
    expect(row('R1')).toHaveClass('diff-row--unchanged');
    // A removed row is not struck: its values are not marked as replaced (strike-through means replaced).
    expect(row('R4').querySelectorAll('.diff-td--old')).toHaveLength(0);
    expect(screen.getByRole('region', { name: 'Version 1 and version 2 of card-fee, side by side' })).toBeInTheDocument();
    expect(screen.queryByText(/>=|==/)).not.toBeInTheDocument();
  });

  it('CKP_002_each_side_shows_its_version_and_effective_date', () => {
    show();
    const base = screen.getByRole('columnheader', { name: /^Version 1/ });
    expect(base).toHaveTextContent(`In effect since ${formatDateTime('2026-11-01T03:00:00Z', 'en') ?? ''}`);
    expect(base).toHaveTextContent('Content hash 9c07e5d3b1a9');
    const next = screen.getByRole('columnheader', { name: /^Version 2/ });
    expect(next).toHaveTextContent('Proposed · takes effect when approved');
    expect(next).toHaveTextContent('Content hash 4be1d7a0c35e');
  });

  it('CKP_002_a_proposed_effective_date_is_shown_on_the_proposed_side', () => {
    show({ ...diff, effectiveFrom: { from: diff.effectiveFrom.from, to: '2026-12-01T00:00:00Z' } }, proposed, 'en', '2026-12-01T00:00:00Z');
    expect(screen.getByRole('columnheader', { name: /^Version 2/ })).toHaveTextContent(`Proposed · takes effect ${formatDateTime('2026-12-01T00:00:00Z', 'en') ?? ''}`);
  });

  it('CKP_002_vietnamese_reads_the_same_diff', () => {
    show(diff, proposed, 'vi');
    expect(screen.getByText('1 thêm, 1 sửa, 1 xóa, 1 không đổi')).toBeInTheDocument();
    expect(within(row('R3')).getByText('35.000 VND')).toBeInTheDocument();
    expect(screen.getByText('Đã xóa ở phiên bản 2')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: /^Phiên bản 2/ })).toHaveTextContent('Đề xuất · có hiệu lực khi được phê duyệt');
  });

  it('CKP_002_header_and_column_changes_read_as_sentences', () => {
    show({
      ...diff,
      header: [{ path: '/hitPolicy', from: 'first', to: 'unique', nonAscii: false }],
      inputs: { added: [], removed: [], changed: [], reordered: true },
      outputs: { added: ['waived'], removed: [], changed: [], reordered: false },
    }, { ...proposed, outputs: [...proposed.outputs, { name: 'waived', type: 'boolean' }] });
    const notes = screen.getByRole('list', { name: 'Changes outside the rows' });
    expect(within(notes).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Hit policy: first matching row wins → exactly one row matches',
      'Condition columns are in a new order',
      'Result column added: Waived',
    ]);
    // The added column exists on the proposed side only.
    expect(screen.getAllByRole('columnheader', { name: 'Waived' })).toHaveLength(1);
  });

  it('ADR_0026_values_the_engine_flags_non_ascii_are_marked_under_one_summary_line', () => {
    // R1's GOLD now carries a Cyrillic capital O (U+041E): it reads like GOLD and never matches it.
    const lookalike: RuleSetDefinition = { ...proposed, rules: proposed.rules.map((r) => (r.id === 'R1' ? { ...r, when: { tier: "== 'GОLD'" } } : r)) };
    show({ ...diff, rows: [{ ruleId: 'R1', change: 'CHANGED', position: 1, basePosition: 1, moved: false, fields: [{ path: '/when/tier', from: "== 'GOLD'", to: "== 'GОLD'", nonAscii: true }] }, ...diff.rows] }, lookalike);
    expect(within(row('R1')).getByText('Look-alike character: О U+041E')).toBeInTheDocument();
    expect(screen.getAllByText(/Look-alike character:/)).toHaveLength(1);
    expect(screen.getByText('1 changed cell contains a look-alike character')).toBeInTheDocument();
  });

  it('ADR_0026_vietnamese_names_look_alike_and_hidden_characters_in_plain_words', () => {
    const lookalike: RuleSetDefinition = { ...proposed, rules: proposed.rules.map((r) => (r.id === 'R1' ? { ...r, when: { tier: "== 'GОLD'" } } : r)) };
    show({ ...diff, rows: [{ ruleId: 'R1', change: 'CHANGED', position: 1, basePosition: 1, moved: false, fields: [{ path: '/when/tier', from: "== 'GOLD'", to: "== 'GОLD'", nonAscii: true }] }, ...diff.rows] }, lookalike, 'vi');
    expect(within(row('R1')).getByText('Ký tự dễ nhầm: О U+041E')).toBeInTheDocument();
    expect(screen.getByText('1 ô đã sửa có ký tự dễ nhầm')).toBeInTheDocument();
    expect(screen.queryByText(/ASCII/)).not.toBeInTheDocument();
  });

  it('ADR_0026_a_flagged_value_without_a_visible_character_is_still_marked', () => {
    show({ ...diff, rows: [{ ruleId: 'R1', change: 'CHANGED', position: 1, basePosition: 1, moved: false, fields: [{ path: '/when/tier', from: "== 'GOLD'", to: "== 'GOLD\\u200B'", nonAscii: true }] }, ...diff.rows] });
    expect(within(row('R1')).getByText('Hidden character')).toBeInTheDocument();
  });
});
