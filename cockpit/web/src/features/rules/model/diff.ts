import type { Rule } from '../../../api/rules/schemas';

/*
 * Row diff of two versions, matched by rule id, never by position (the same rules as rule-engine DESIGN
 * section 4.5). Interim: the engine's GET .../diff is not in this release's API, so the cockpit compares
 * the two canonical definitions it already read. Replace with the engine's diff when it ships.
 */

export type RowChange = 'ADDED' | 'REMOVED' | 'CHANGED' | 'MOVED' | 'UNCHANGED';

export interface RowDiff {
  ruleId: string;
  change: RowChange;
  moved: boolean;
  rule: Rule | undefined;
  base: Rule | undefined;
  /** Changed cells as "when.<input>" or "then.<output>". */
  changedCells: ReadonlySet<string>;
}

function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}

function changedCells(rule: Rule, base: Rule): Set<string> {
  const changed = new Set<string>();
  for (const part of ['when', 'then'] as const) {
    const names = new Set([...Object.keys(rule[part]), ...Object.keys(base[part])]);
    for (const name of names) if (!same(rule[part][name], base[part][name])) changed.add(`${part}.${name}`);
  }
  return changed;
}

/** Longest common subsequence of two id orders; ids outside it moved. */
function stableIds(a: readonly string[], b: readonly string[]): Set<string> {
  const table: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i -= 1) {
    for (let j = b.length - 1; j >= 0; j -= 1) {
      const row = table[i] ?? [];
      row[j] = a[i] === b[j] ? (table[i + 1]?.[j + 1] ?? 0) + 1 : Math.max(table[i + 1]?.[j] ?? 0, row[j + 1] ?? 0);
    }
  }
  const kept = new Set<string>();
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      kept.add(a[i] ?? '');
      i += 1;
      j += 1;
    } else if ((table[i + 1]?.[j] ?? 0) >= (table[i]?.[j + 1] ?? 0)) i += 1;
    else j += 1;
  }
  return kept;
}

export function diffRules(base: readonly Rule[], next: readonly Rule[]): RowDiff[] {
  const baseById = new Map(base.map((rule) => [rule.id, rule]));
  const nextIds = new Set(next.map((rule) => rule.id));
  const common = (rules: readonly Rule[], other: Set<string> | Map<string, Rule>) => rules.map((r) => r.id).filter((id) => other.has(id));
  const kept = stableIds(common(base, nextIds), common(next, baseById));
  const rows: RowDiff[] = next.map((rule) => {
    const old = baseById.get(rule.id);
    if (!old) return { ruleId: rule.id, change: 'ADDED', moved: false, rule, base: undefined, changedCells: new Set() };
    const cells = changedCells(rule, old);
    const moved = !kept.has(rule.id);
    const change: RowChange = cells.size > 0 ? 'CHANGED' : moved ? 'MOVED' : 'UNCHANGED';
    return { ruleId: rule.id, change, moved, rule, base: old, changedCells: cells };
  });
  for (const old of base) {
    if (!nextIds.has(old.id)) rows.push({ ruleId: old.id, change: 'REMOVED', moved: false, rule: undefined, base: old, changedCells: new Set() });
  }
  return rows;
}
