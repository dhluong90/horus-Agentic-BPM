import type { ColumnChanges, FieldChange, Rule, RowChangeKind, VersionDiff } from '../../../api/rules/schemas';

/*
 * The side-by-side view of the engine's version diff (RLE-028, rule-engine DESIGN section 4.5). The engine
 * decides what changed; this module only lines the two versions up so each row of the base sits next to the
 * same rule id in the proposed version. Nothing here compares cell values.
 */

export type PairChange = RowChangeKind | 'UNCHANGED';

export interface RowPair {
  ruleId: string;
  change: PairChange;
  /** True for MOVED, and for CHANGED with moved: true. */
  moved: boolean;
  /** The rule in the base version; undefined for an added row. */
  base: Rule | undefined;
  /** The rule in the proposed version; undefined for a removed row. */
  next: Rule | undefined;
  /** Changed cells as "when.<input>" or "then.<output>", from the engine's JSON Pointer paths. */
  changed: ReadonlySet<string>;
  /** Changed cells the engine marked nonAscii (RLE-048). */
  nonAscii: ReadonlySet<string>;
}

function unescapePointer(token: string): string {
  return token.replaceAll('~1', '/').replaceAll('~0', '~');
}

/** "/then/fee" -> "then.fee"; a deeper path ("/when/tier/0", inside a group) names its cell. */
export function cellKey(path: string): string | undefined {
  const [, part, name] = path.split('/');
  if ((part !== 'when' && part !== 'then') || name === undefined || name === '') return undefined;
  return `${part}.${unescapePointer(name)}`;
}

function cells(fields: readonly FieldChange[] | undefined, only: (field: FieldChange) => boolean = () => true): Set<string> {
  const keys = new Set<string>();
  for (const field of fields ?? []) {
    const key = cellKey(field.path);
    if (key !== undefined && only(field)) keys.add(key);
  }
  return keys;
}

/**
 * Rows of both versions in reading order: the proposed version's order, with each removed row placed after
 * the nearest row above it in the base that is still there (at the top when none is).
 */
export function pairRows(diff: VersionDiff, baseRules: readonly Rule[], nextRules: readonly Rule[]): RowPair[] {
  const changes = new Map(diff.rows.map((row) => [row.ruleId, row]));
  const baseById = new Map(baseRules.map((rule) => [rule.id, rule]));
  const nextIds = new Set(nextRules.map((rule) => rule.id));

  const removedAfter = new Map<string | null, RowPair[]>();
  const removed = diff.rows.filter((row) => row.change === 'REMOVED').sort((a, b) => (a.basePosition ?? 0) - (b.basePosition ?? 0));
  for (const row of removed) {
    const at = baseRules.findIndex((rule) => rule.id === row.ruleId);
    let anchor: string | null = null;
    for (let i = at - 1; i >= 0; i -= 1) {
      const id = baseRules[i]?.id;
      if (id !== undefined && nextIds.has(id)) {
        anchor = id;
        break;
      }
    }
    const pair: RowPair = { ruleId: row.ruleId, change: 'REMOVED', moved: false, base: baseById.get(row.ruleId), next: undefined, changed: new Set(), nonAscii: new Set() };
    removedAfter.set(anchor, [...(removedAfter.get(anchor) ?? []), pair]);
  }

  const pairs: RowPair[] = [...(removedAfter.get(null) ?? [])];
  for (const rule of nextRules) {
    const row = changes.get(rule.id);
    const change: PairChange = row === undefined || row.change === 'REMOVED' ? 'UNCHANGED' : row.change;
    pairs.push({
      ruleId: rule.id,
      change,
      moved: change === 'MOVED' || (change === 'CHANGED' && row?.moved === true),
      base: change === 'ADDED' ? undefined : baseById.get(rule.id),
      next: rule,
      changed: cells(row?.fields),
      nonAscii: cells(row?.fields, (field) => field.nonAscii),
    });
    pairs.push(...(removedAfter.get(rule.id) ?? []));
  }
  return pairs;
}

export interface DiffCounts {
  added: number;
  changed: number;
  removed: number;
  moved: number;
  unchanged: number;
  nonAscii: number;
}

/** The counts of the summary line, from the engine's rows; a row both moved and changed counts in both. */
export function countChanges(diff: VersionDiff): DiffCounts {
  const of = (kind: RowChangeKind) => diff.rows.filter((row) => row.change === kind).length;
  const fields = [...diff.header, ...diff.rows.flatMap((row) => row.fields ?? []), ...[diff.inputs, diff.outputs].flatMap((c) => c.changed.flatMap((column) => column.fields))];
  return {
    added: of('ADDED'),
    changed: of('CHANGED'),
    removed: of('REMOVED'),
    moved: of('MOVED') + diff.rows.filter((row) => row.change === 'CHANGED' && row.moved === true).length,
    unchanged: diff.unchangedCount,
    nonAscii: fields.filter((field) => field.nonAscii).length,
  };
}

/** A change outside the rows, as one plain sentence each in the view. */
export type StructureNote =
  | { kind: 'hitPolicy'; from: string; to: string }
  | { kind: 'member'; member: string }
  | { kind: 'columnAdded'; group: 'when' | 'then'; name: string }
  | { kind: 'columnRemoved'; group: 'when' | 'then'; name: string }
  | { kind: 'columnChanged'; group: 'when' | 'then'; name: string }
  | { kind: 'columnsReordered'; group: 'when' | 'then' };

function columnNotes(group: 'when' | 'then', columns: ColumnChanges): StructureNote[] {
  return [
    ...columns.added.map((name): StructureNote => ({ kind: 'columnAdded', group, name })),
    ...columns.removed.map((name): StructureNote => ({ kind: 'columnRemoved', group, name })),
    ...columns.changed.map(({ name }): StructureNote => ({ kind: 'columnChanged', group, name })),
    ...(columns.reordered ? [{ kind: 'columnsReordered', group } as const] : []),
  ];
}

export function structureNotes(diff: VersionDiff): StructureNote[] {
  const header = diff.header.map((field): StructureNote => {
    const member = unescapePointer(field.path.replace(/^\//, ''));
    if (member === 'hitPolicy' && typeof field.from === 'string' && typeof field.to === 'string') return { kind: 'hitPolicy', from: field.from, to: field.to };
    return { kind: 'member', member };
  });
  return [...header, ...columnNotes('when', diff.inputs), ...columnNotes('then', diff.outputs)];
}
