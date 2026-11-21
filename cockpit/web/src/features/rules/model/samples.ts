import type { InputClause, RuleSetDefinition } from '../../../api/rules/schemas';
import type { Locale } from '../../../i18n/I18nProvider';
import { valueLabel } from './cells';
import { groupWhileTyping, toMinorUnits } from './money';

/*
 * The samples of a simulation as the author types or pastes them (CKP-004). Each row keeps the text of each field,
 * exactly as the sample form keeps it; the inputs sent to the engine are built from it when the author runs. The
 * engine decides every outcome; this module only reads the shape of pasted rows (columns, a choice, an amount).
 */

export interface SampleRow {
  /** A stable key for React, never shown. */
  key: number;
  /** The author's own name for the sample, sent as `label` and written back on its result. */
  label: string;
  values: Readonly<Record<string, string>>;
}

export function emptyRow(key: number): SampleRow {
  return { key, label: '', values: {} };
}

/** True when the row has neither a name nor any value. */
export function isBlank(row: SampleRow): boolean {
  return row.label.trim() === '' && Object.values(row.values).every((value) => value.trim() === '');
}

/** The reference the engine gives the sample in position `index` (from 0): S1 for the first. */
export function referenceOf(index: number): string {
  return `S${String(index + 1)}`;
}

export type PasteProblem =
  | { kind: 'empty' }
  | { kind: 'columns'; line: number; expected: number }
  | { kind: 'choice'; line: number; input: string; choices: readonly string[] }
  | { kind: 'yes-no'; line: number; input: string }
  | { kind: 'amount'; line: number; input: string }
  | { kind: 'currency'; line: number; input: string; currency: string };

export type Pasted = { ok: true; rows: { label: string; values: Record<string, string> }[] } | { ok: false; problem: PasteProblem };

const YES = new Set(['yes', 'y', 'true', '1', 'có', 'co']);
const NO = new Set(['no', 'n', 'false', '0', 'không', 'khong']);

function delimiterOf(lines: readonly string[]): string {
  if (lines.some((line) => line.includes('\t'))) return '\t';
  if (lines.some((line) => line.includes(';'))) return ';';
  return ',';
}

/** One line of delimited text into cells, with RFC 4180 double quotes so a pasted "50,000,000" stays one cell. */
function cellsOf(line: string, delimiter: string): string[] {
  const cells: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < line.length; i += 1) {
    const c = line.charAt(i);
    if (quoted) {
      if (c === '"' && line.charAt(i + 1) === '"') { cell += '"'; i += 1; }
      else if (c === '"') quoted = false;
      else cell += c;
    } else if (c === '"' && cell.trim() === '') { quoted = true; cell = ''; }
    else if (c === delimiter) { cells.push(cell.trim()); cell = ''; }
    else cell += c;
  }
  cells.push(cell.trim());
  return cells;
}

const same = (a: string, b: string) => a.trim().toLocaleLowerCase() === b.trim().toLocaleLowerCase();

/**
 * One pasted cell as the text its field would hold, or the problem with it. A blank cell means any value.
 * Choices match their value or label; yes or no in either language; an amount as typed on this screen or as the
 * engine writes it (`50000000 VND`), in the input's currency.
 */
function fieldText(input: InputClause, cell: string, currency: string | undefined, locale: Locale, line: number, label: string): { ok: true; text: string } | { ok: false; problem: PasteProblem } {
  if (cell === '' || cell === '-' || cell === '—') return { ok: true, text: '' };
  if (input.mask === true) return { ok: true, text: cell };
  if (input.allowed && input.allowed.length > 0) {
    const choices = input.allowed.map(String);
    const found = choices.find((choice) => same(choice, cell) || same(valueLabel(choice), cell));
    return found !== undefined ? { ok: true, text: found } : { ok: false, problem: { kind: 'choice', line, input: label, choices: choices.map(valueLabel) } };
  }
  if (input.type === 'boolean') {
    const word = cell.toLocaleLowerCase();
    if (YES.has(word)) return { ok: true, text: 'true' };
    if (NO.has(word)) return { ok: true, text: 'false' };
    return { ok: false, problem: { kind: 'yes-no', line, input: label } };
  }
  if (input.type === 'money') {
    const match = /^(.*?)\s*([A-Za-z]{3})?$/.exec(cell);
    const amount = (match?.[1] ?? cell).replace(/\s/g, '');
    const code = match?.[2]?.toUpperCase();
    if (code !== undefined && currency !== undefined && currency !== '' && code !== currency) return { ok: false, problem: { kind: 'currency', line, input: label, currency } };
    const plain = /^-?\d+$/.test(amount) ? groupWhileTyping(amount, locale) : amount;
    if (!toMinorUnits(plain, code ?? (currency !== undefined && currency !== '' ? currency : 'VND'), locale).ok) return { ok: false, problem: { kind: 'amount', line, input: label } };
    return { ok: true, text: plain };
  }
  return { ok: true, text: cell };
}

/**
 * Rows copied from a spreadsheet (tab separated) or written as CSV (comma or semicolon), one sample per line.
 * Columns: an optional name, then one per input in the table's order. A first line naming the columns is read as
 * the header and may put them in any order. `labelOf` is the input's label on screen; `nameHeader` the name column's.
 */
export function readPastedRows(text: string, definition: RuleSetDefinition, currencies: Readonly<Record<string, string>>, locale: Locale,
  labelOf: (input: InputClause) => string, nameHeader: string): Pasted {
  const lines = text.split(/\r?\n/).map((line, index) => ({ line: index + 1, text: line })).filter((entry) => entry.text.trim() !== '');
  if (lines.length === 0) return { ok: false, problem: { kind: 'empty' } };
  const delimiter = delimiterOf(lines.map((entry) => entry.text));
  const inputs = definition.inputs;
  const first = cellsOf(lines[0]?.text ?? '', delimiter);
  const headerOf = (cell: string): number | 'label' | undefined => {
    if (same(cell, nameHeader) || same(cell, 'label') || same(cell, 'name')) return 'label';
    const index = inputs.findIndex((input) => same(input.name, cell) || same(labelOf(input), cell));
    return index >= 0 ? index : undefined;
  };
  const header = first.map(headerOf).filter((column) => column !== undefined);
  const isHeader = first.length > 0 && header.length === first.length && header.some((column) => column !== 'label');
  let columns: (number | 'label')[];
  if (isHeader) columns = header;
  else if (first.length === inputs.length + 1) columns = ['label', ...inputs.map((_, index) => index)];
  else columns = inputs.map((_, index) => index);
  const rows: { label: string; values: Record<string, string> }[] = [];
  for (const entry of isHeader ? lines.slice(1) : lines) {
    const cells = cellsOf(entry.text, delimiter);
    if (cells.length !== columns.length) return { ok: false, problem: { kind: 'columns', line: entry.line, expected: inputs.length } };
    const row = { label: '', values: {} as Record<string, string> };
    for (const [position, column] of columns.entries()) {
      const cell = cells[position] ?? '';
      if (column === 'label') { row.label = cell.slice(0, 100); continue; }
      const input = inputs[column];
      if (input === undefined) continue;
      const read = fieldText(input, cell, currencies[input.name], locale, entry.line, labelOf(input));
      if (!read.ok) return read;
      row.values[input.name] = read.text;
    }
    rows.push(row);
  }
  return rows.length === 0 ? { ok: false, problem: { kind: 'empty' } } : { ok: true, rows };
}
