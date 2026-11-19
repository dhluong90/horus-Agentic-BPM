import { describe, expect, it } from 'vitest';
import type { Rule, VersionDiff } from '../../../api/rules/schemas';
import { cellKey, countChanges, pairRows, structureNotes } from './versionDiff';

const rule = (id: string): Rule => ({ id, when: {}, then: {} });
const none = { added: [], removed: [], changed: [], reordered: false };

function diffOf(rows: VersionDiff['rows'], extra: Partial<VersionDiff> = {}): VersionDiff {
  return {
    ruleSet: 'card-fee', version: 2, base: 1, canonicalHash: 'a'.repeat(64), baseCanonicalHash: 'b'.repeat(64), sourceHashChanged: true,
    effectiveFrom: { from: '2026-11-01T03:00:00Z', to: null }, header: [], inputs: none, outputs: none, rows, unchangedCount: 0, masking: [], ...extra,
  };
}

describe('side-by-side pairing of the engine diff', () => {
  it('CKP_002_rows_pair_by_rule_id_and_a_removed_row_follows_its_former_neighbour', () => {
    const base = ['R1', 'R2', 'R3', 'R4', 'R5'].map(rule);
    const next = ['R1', 'R2', 'R6', 'R3', 'R5'].map(rule);
    const diff = diffOf([
      { ruleId: 'R6', change: 'ADDED', position: 3 },
      { ruleId: 'R3', change: 'CHANGED', position: 4, basePosition: 3, moved: false, fields: [{ path: '/then/fee', from: { currency: 'VND', amount: 30000 }, to: { currency: 'VND', amount: 35000 }, nonAscii: false }] },
      { ruleId: 'R4', change: 'REMOVED', basePosition: 4 },
    ], { unchangedCount: 3 });
    const pairs = pairRows(diff, base, next);
    expect(pairs.map((p) => `${p.ruleId}:${p.change}`)).toEqual(['R1:UNCHANGED', 'R2:UNCHANGED', 'R6:ADDED', 'R3:CHANGED', 'R4:REMOVED', 'R5:UNCHANGED']);
    const [, , added, changed, removed] = pairs;
    expect(added?.base).toBeUndefined();
    expect(added?.next?.id).toBe('R6');
    expect([...(changed?.changed ?? [])]).toEqual(['then.fee']);
    expect(removed?.next).toBeUndefined();
    expect(removed?.base?.id).toBe('R4');
  });

  it('CKP_002_a_removed_first_row_goes_on_top_and_moved_rows_are_marked', () => {
    const base = ['R1', 'R2', 'R3'].map(rule);
    const next = ['R3', 'R2'].map(rule);
    const diff = diffOf([
      { ruleId: 'R3', change: 'MOVED', position: 1, basePosition: 3 },
      { ruleId: 'R2', change: 'CHANGED', position: 2, basePosition: 2, moved: true, fields: [{ path: '/when/tier', from: "== 'GOLD'", to: "== 'GОLD'", nonAscii: true }] },
      { ruleId: 'R1', change: 'REMOVED', basePosition: 1 },
    ]);
    const pairs = pairRows(diff, base, next);
    expect(pairs.map((p) => p.ruleId)).toEqual(['R1', 'R3', 'R2']);
    expect(pairs.map((p) => p.moved)).toEqual([false, true, true]);
    expect([...(pairs[2]?.nonAscii ?? [])]).toEqual(['when.tier']);
    expect(countChanges(diff)).toEqual({ added: 0, changed: 1, removed: 1, moved: 2, unchanged: 0, nonAscii: 1 });
  });

  it('CKP_002_json_pointer_paths_name_their_cell', () => {
    expect(cellKey('/then/fee')).toBe('then.fee');
    expect(cellKey('/when/a~1b~0c')).toBe('when.a/b~c');
    expect(cellKey('/when/tier/0')).toBe('when.tier');
    expect(cellKey('/hitPolicy')).toBeUndefined();
  });

  it('CKP_002_header_and_column_changes_become_notes', () => {
    const diff = diffOf([], {
      header: [{ path: '/hitPolicy', from: 'first', to: 'unique', nonAscii: false }, { path: '/description', from: 'a', to: 'b', nonAscii: false }],
      inputs: { added: ['channel'], removed: [], changed: [{ name: 'tier', fields: [{ path: '/mask', from: null, to: true, nonAscii: false }] }], reordered: true },
      outputs: { added: [], removed: ['waived'], changed: [], reordered: false },
    });
    expect(structureNotes(diff)).toEqual([
      { kind: 'hitPolicy', from: 'first', to: 'unique' },
      { kind: 'member', member: 'description' },
      { kind: 'columnAdded', group: 'when', name: 'channel' },
      { kind: 'columnChanged', group: 'when', name: 'tier' },
      { kind: 'columnsReordered', group: 'when' },
      { kind: 'columnRemoved', group: 'then', name: 'waived' },
    ]);
  });
});
