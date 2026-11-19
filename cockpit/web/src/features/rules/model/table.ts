import type { Rule, RuleSetDefinition, ThenValue } from '../../../api/rules/schemas';

/*
 * The decision table as the minimal editor sees it: every editable cell is plain text, exactly what the
 * engine stores. Rows carry a stable key that survives edits of the row id and reordering.
 */

export interface EditableRow {
  key: string;
  id: string;
  when: Record<string, string>;
  then: Record<string, string>;
  /** Nested any/all groups: kept as they are and shown read only. */
  groups: Record<string, readonly unknown[]>;
  /** The row as loaded, to keep untouched output values in their original JSON type. */
  original: Rule | undefined;
}

export function thenText(value: ThenValue | undefined): string {
  if (value === undefined || value === null) return '';
  return typeof value === 'string' ? value : String(value);
}

export function toRows(definition: RuleSetDefinition): EditableRow[] {
  return definition.rules.map((rule, index) => {
    const when: Record<string, string> = {};
    const groups: Record<string, readonly unknown[]> = {};
    for (const [name, cell] of Object.entries(rule.when)) {
      if (typeof cell === 'string') when[name] = cell;
      else groups[name] = cell;
    }
    const then: Record<string, string> = {};
    for (const [name, value] of Object.entries(rule.then)) then[name] = thenText(value);
    return { key: `row-${index}-${rule.id}`, id: rule.id, when, then, groups, original: rule };
  });
}

/** The next free row id of the form R<n>. */
export function nextRowId(rows: readonly { id: string }[]): string {
  let highest = 0;
  for (const row of rows) {
    const match = /^R(\d+)$/.exec(row.id);
    if (match) highest = Math.max(highest, Number(match[1]));
  }
  return `R${highest + 1}`;
}

function outputValue(row: EditableRow, name: string, text: string, type: string | undefined): ThenValue | undefined {
  const original = row.original?.then[name];
  if (original !== undefined && thenText(original) === text) return original;
  if (text.trim() === '') return undefined;
  if (type === 'boolean' && (text.trim() === 'true' || text.trim() === 'false')) return text.trim() === 'true';
  return text;
}

function without(record: Readonly<Record<string, unknown>>, names: readonly string[]): Record<string, unknown> {
  return Object.fromEntries(Object.entries(record).filter(([name]) => !names.includes(name)));
}

/**
 * The document the engine imports as a new draft: the loaded definition with the edited rows. Inputs,
 * outputs and the hit policy are never changed here. effectiveFrom is dropped, so the new draft takes effect
 * at approval unless the author sets a date through a file import.
 */
export function toDocument(definition: RuleSetDefinition, rows: readonly EditableRow[]): Record<string, unknown> {
  const outputTypes = new Map(definition.outputs.map((output) => [output.name, output.type]));
  const rest = without(definition, ['effectiveFrom']);
  return {
    ...rest,
    rules: rows.map((row) => {
      const when: Record<string, unknown> = { ...row.groups };
      for (const [name, text] of Object.entries(row.when)) if (text.trim() !== '') when[name] = text.trim();
      const then: Record<string, ThenValue> = {};
      for (const [name, text] of Object.entries(row.then)) {
        const value = outputValue(row, name, text, outputTypes.get(name));
        if (value !== undefined) then[name] = value;
      }
      const extra = row.original ? without(row.original, ['id', 'when', 'then']) : {};
      return { ...extra, id: row.id.trim(), when, then };
    }),
  };
}

function cells(record: Record<string, string>): string[][] {
  return Object.entries(record)
    .filter(([, text]) => text.trim() !== '')
    .map(([name, text]) => [name, text.trim()])
    .sort(([x = ''], [y = '']) => x.localeCompare(y));
}

/** True when two row lists hold the same rows in the same order, ignoring empty cells and key order. */
export function sameRows(a: readonly EditableRow[], b: readonly EditableRow[]): boolean {
  const shape = (rows: readonly EditableRow[]) => JSON.stringify(rows.map((row) => [row.id.trim(), cells(row.when), cells(row.then)]));
  return shape(a) === shape(b);
}
