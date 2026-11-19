import type { InputClause, OutputClause, RuleSetDefinition } from '../../../api/rules/schemas';

/*
 * Typed cells for the draft editor. The draft keeps every cell as the engine's text (`>= 50000000 VND`,
 * `in ['GOLD', 'PLATINUM']`, `true`), so saving sends exactly what the engine validates. Each editor reads
 * the text into a value it can show (a tier picker, a comparison and an amount, Yes or No) and writes text
 * back. Text the editors cannot represent stays editable as plain text, unchanged.
 */

export const OPERATORS = ['=', '≥', '>', '≤', '<'] as const;
export type Operator = (typeof OPERATORS)[number];
const TO_ENGINE: Record<Operator, string> = { '=': '==', '≥': '>=', '>': '>', '≤': '<=', '<': '<' };
const FROM_ENGINE: Record<string, Operator> = { '==': '=', '>=': '≥', '>': '>', '<=': '≤', '<': '<' };

export type CellModel =
  | { kind: 'any' }
  | { kind: 'choice'; values: string[] }
  | { kind: 'compare'; op: Operator; amount: string; currency: string | undefined }
  | { kind: 'bool'; value: boolean }
  | { kind: 'money'; amount: string; currency: string }
  | { kind: 'text'; text: string }
  | { kind: 'raw'; text: string };

/** Which editor a column uses, from its declared type. */
export type ColumnKind = 'choice' | 'number' | 'money' | 'bool' | 'text' | 'raw';

export function inputKind(input: InputClause): ColumnKind {
  if (input.allowed && input.allowed.length > 0) return 'choice';
  if (input.type === 'money') return 'money';
  if (input.type === 'integer' || input.type === 'decimal') return 'number';
  if (input.type === 'boolean') return 'bool';
  return 'raw';
}

export function outputKind(output: OutputClause): ColumnKind {
  if (output.type === 'money') return 'money';
  if (output.type === 'boolean') return 'bool';
  if (output.type === 'string') return 'text';
  if (output.type === 'integer' || output.type === 'decimal') return 'number';
  return 'raw';
}

const QUOTED = /^'((?:[^'\\]|\\.)*)'$/;
const unquote = (text: string) => QUOTED.exec(text.trim())?.[1]?.replaceAll("\\'", "'");
const quote = (text: string) => `'${text.replaceAll("'", "\\'")}'`;

/** A condition cell as a typed value for its column. */
export function readConditionCell(text: string | undefined, kind: ColumnKind): CellModel {
  const value = (text ?? '').trim();
  if (value === '') return { kind: 'any' };
  if (kind === 'choice') {
    const one = /^==\s*(.+)$/.exec(value);
    const single = one ? unquote(one[1] ?? '') : undefined;
    if (single !== undefined) return { kind: 'choice', values: [single] };
    const list = /^in\s*\[(.*)\]$/.exec(value);
    if (list) {
      const items = (list[1] ?? '').split(',').map((item) => unquote(item));
      if (items.every((item): item is string => item !== undefined)) return { kind: 'choice', values: items };
    }
  }
  if (kind === 'number' || kind === 'money') {
    const compare = /^(==|>=|<=|>|<)\s*(-?\d+(?:\.\d+)?)?(?:\s+([A-Z]{3}))?$/.exec(value);
    const op = compare ? FROM_ENGINE[compare[1] ?? ''] : undefined;
    if (compare && op) return { kind: 'compare', op, amount: compare[2] ?? '', currency: compare[3] };
  }
  if (kind === 'bool' && /^==\s*(true|false)$/.test(value)) return { kind: 'bool', value: value.endsWith('true') };
  return { kind: 'raw', text: value };
}

/** The engine text of a typed condition; an empty string is a wildcard. */
export function writeConditionCell(model: CellModel): string {
  switch (model.kind) {
    case 'any':
      return '';
    case 'choice':
      if (model.values.length === 0) return '';
      return model.values.length === 1 ? `== ${quote(model.values[0] ?? '')}` : `in [${model.values.map(quote).join(', ')}]`;
    case 'compare':
      return `${TO_ENGINE[model.op]} ${model.amount}${model.currency !== undefined && model.amount !== '' ? ` ${model.currency}` : ''}`.trim();
    case 'bool':
      return `== ${String(model.value)}`;
    case 'money':
      return model.amount === '' ? '' : `${model.amount} ${model.currency}`;
    case 'text':
      return model.text === '' ? '' : quote(model.text);
    case 'raw':
      return model.text;
  }
}

/** An output cell (the draft keeps it as text) as a typed value for its column. */
export function readOutputCell(text: string, kind: ColumnKind, currency: string | undefined): CellModel {
  const value = text.trim();
  if (value === '') return kind === 'money' && currency !== undefined ? { kind: 'money', amount: '', currency } : { kind: 'any' };
  if (kind === 'money') {
    const money = /^(-?\d+(?:\.\d+)?)\s+([A-Z]{3})$/.exec(value);
    if (money) return { kind: 'money', amount: money[1] ?? '', currency: money[2] ?? '' };
  }
  if (kind === 'bool' && (value === 'true' || value === 'false')) return { kind: 'bool', value: value === 'true' };
  if (kind === 'text') {
    const inner = unquote(value);
    if (inner !== undefined) return { kind: 'text', text: inner };
  }
  return { kind: 'raw', text: value };
}

export function writeOutputCell(model: CellModel): string {
  return model.kind === 'bool' ? String(model.value) : writeConditionCell(model);
}

/** The currency a money column uses: the one named by its own cells, when they agree. */
export function columnCurrency(definition: RuleSetDefinition, part: 'when' | 'then', column: string): string | undefined {
  const found = new Set<string>();
  for (const rule of definition.rules) {
    const cell = part === 'when' ? rule.when[column] : rule.then[column];
    if (typeof cell !== 'string') continue;
    for (const match of cell.matchAll(/\d\s+([A-Z]{3})\b/g)) if (match[1]) found.add(match[1]);
  }
  return found.size === 1 ? [...found][0] : undefined;
}

/** An enum code as words: PLATINUM -> Platinum, FIRST_TIME -> First time. Other text stays as it is. */
export function valueLabel(value: string): string {
  if (!/^[A-Z][A-Z0-9_]*$/.test(value)) return value;
  const words = value.toLowerCase().replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
