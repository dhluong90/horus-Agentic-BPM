/*
 * The spreadsheet format of the Rule Engine as the fake engine reads and writes it (RLE-191, RLE-192; PD-107): a
 * UTF-8 file with a byte order mark, one header line of `Rule`, the inputs, the outputs and `Description`, one
 * line per rule, cells in the shorthand (`GOLD, PLATINUM`, `>= 50000000 VND`, `-`). The real engine is the contract;
 * this stand-in keeps the same codes and never quotes a cell in a problem.
 */

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
interface Column { name: string; type: string }
export interface CsvDefinition {
  inputs: Column[];
  outputs: Column[];
  rules: { id: string; when: Record<string, string>; then: Record<string, Json>; description?: string }[];
}
export interface CsvProblem { ruleId: string | null; column: string | null; code: string; message: string }

const MESSAGES: Record<string, string> = {
  CSV_COLUMN_UNKNOWN: 'This column is not in the version the file is based on. Inputs and outputs cannot change through a file.',
  CSV_COLUMN_MISSING: 'This column of the version the file is based on is missing from the file.',
  CSV_RULE_DUPLICATE: 'This rule id is already used by an earlier row.',
  CSV_ROW_SHAPE: 'The row has a different number of cells than the header.',
  CSV_CELL_NO_CURRENCY: 'The amount has no valid currency. Write it after the number, for example 50000000 VND.',
  CSV_CELL_SEPARATORS: 'Write the amount without separators, for example 50000000 VND.',
  CSV_CELL_SCIENTIFIC: 'Write the amount in full, not in scientific notation, for example 50000000 VND.',
  CSV_CELL_DATE: 'Write the date as year-month-day, for example 2026-11-20.',
  CSV_EMPTY: 'The file has no rules.',
};
const problem = (code: string, ruleId: string | null, column: string | null): CsvProblem => ({ code, ruleId, column, message: MESSAGES[code] ?? code });

const quote = (cell: string) => (/[",\r\n]/.test(cell) ? `"${cell.replaceAll('"', '""')}"` : cell);

function whenToCell(condition: string | undefined): string {
  if (condition === undefined || condition.trim() === '') return '-';
  const eq = /^==\s*'(.*)'$/.exec(condition.trim());
  return eq ? (eq[1] ?? '') : condition.trim();
}
function thenToCell(value: Json | undefined): string {
  return value === undefined || value === null ? 'null' : typeof value === 'string' ? value : JSON.stringify(value);
}

/** The file for a version: header, then one line per rule in table order; CRLF line ends and a byte order mark. */
export function toCsv(definition: CsvDefinition): string {
  const columns = [...definition.inputs, ...definition.outputs];
  const lines = [['Rule', ...columns.map((c) => c.name), 'Description']];
  for (const rule of definition.rules) {
    lines.push([rule.id, ...definition.inputs.map((c) => whenToCell(rule.when[c.name])), ...definition.outputs.map((c) => thenToCell(rule.then[c.name])), rule.description ?? '']);
  }
  return `\uFEFF${lines.map((line) => line.map(quote).join(',')).join('\r\n')}\r\n`;
}

/** Splits the file into rows of cells (RFC 4180 quoting); the delimiter is the header line's own, comma or semicolon. */
export function parseCsv(text: string): string[][] {
  const body = text.replace(/^\uFEFF/, '');
  const firstLine = body.split(/\r?\n/, 1)[0] ?? '';
  const delimiter = firstLine.includes(';') && !firstLine.includes(',') ? ';' : ',';
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < body.length; i += 1) {
    const ch = body[i] ?? '';
    if (quoted) {
      if (ch === '"' && body[i + 1] === '"') { cell += '"'; i += 1; } else if (ch === '"') quoted = false; else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === delimiter) { row.push(cell); cell = ''; } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && body[i + 1] === '\n') i += 1;
      row.push(cell); cell = '';
      if (row.some((c) => c !== '')) rows.push(row);
      row = [];
    } else cell += ch;
  }
  row.push(cell);
  if (row.some((c) => c !== '')) rows.push(row);
  return rows;
}

function cellToWhen(cell: string): string | undefined {
  const text = cell.trim();
  if (text === '-' || text === '') return undefined;
  if (/^(==|!=|>=|<=|>|<)/.test(text)) return text;
  if (text.includes(',')) return `in [${text.split(',').map((part) => `'${part.trim()}'`).join(', ')}]`;
  return /^[A-Za-z][A-Za-z0-9_ ]*$/.test(text) ? `== '${text}'` : text;
}
function cellToThen(cell: string): Json {
  const text = cell.trim();
  if (text === 'true' || text === 'false') return text === 'true';
  if (text === 'null') return null;
  return /^-?\d+$/.test(text) ? Number(text) : text;
}

/** The rules of a file read against the columns of its base version, or every problem found. */
export function fromCsv(text: string, base: CsvDefinition): { rules: CsvDefinition['rules'] } | { problems: CsvProblem[] } {
  const rows = parseCsv(text);
  const [header, ...lines] = rows;
  if (header === undefined) return { problems: [problem('CSV_EMPTY', null, null)] };
  const expected = [...base.inputs, ...base.outputs].map((c) => c.name);
  const problems: CsvProblem[] = [];
  for (const name of header.slice(1)) if (name !== 'Description' && !expected.includes(name)) problems.push(problem('CSV_COLUMN_UNKNOWN', null, name));
  for (const name of expected) if (!header.includes(name)) problems.push(problem('CSV_COLUMN_MISSING', null, name));
  if (problems.length > 0) return { problems };
  if (lines.length === 0) return { problems: [problem('CSV_EMPTY', null, null)] };
  const used = new Set(lines.map((l) => (l[0] ?? '').trim()).filter((id) => id !== ''));
  const rules: CsvDefinition['rules'] = [];
  const seen = new Set<string>();
  let counter = 0;
  for (const line of lines) {
    let id = (line[0] ?? '').trim();
    if (id === '') { do { counter += 1; id = `R${String(counter)}`; } while (used.has(id)); }
    if (seen.has(id)) { problems.push(problem('CSV_RULE_DUPLICATE', id, null)); continue; }
    seen.add(id);
    if (line.length !== header.length) { problems.push(problem('CSV_ROW_SHAPE', id, null)); continue; }
    const cell = (name: string) => (line[header.indexOf(name)] ?? '').trim();
    const rule: CsvDefinition['rules'][number] = { id, when: {}, then: {} };
    for (const input of base.inputs) {
      const value = cell(input.name);
      if (input.type === 'money' && /^(>=|<=|>|<|==|!=)?\s*-?[\d.,]+(E\+?\d+)?\s*([A-Z]{3})?$/i.test(value) && !/[A-Z]{3}$/.test(value.replace(/E\+?\d+/i, '')) && value !== '-') {
        problems.push(problem(/E\+?\d/i.test(value) ? 'CSV_CELL_SCIENTIFIC' : /\d,\d{3}/.test(value) ? 'CSV_CELL_SEPARATORS' : 'CSV_CELL_NO_CURRENCY', id, input.name));
        continue;
      }
      if (/E\+\d+\s+[A-Z]{3}$/i.test(value)) { problems.push(problem('CSV_CELL_SCIENTIFIC', id, input.name)); continue; }
      if (/\d,\d{3}\s+[A-Z]{3}$/.test(value)) { problems.push(problem('CSV_CELL_SEPARATORS', id, input.name)); continue; }
      if (/^\d{1,2}\/\d{1,2}\/\d{4}$/.test(value)) { problems.push(problem('CSV_CELL_DATE', id, input.name)); continue; }
      const when = cellToWhen(value);
      if (when !== undefined) rule.when[input.name] = when;
    }
    for (const output of base.outputs) rule.then[output.name] = cellToThen(cell(output.name));
    const description = header.includes('Description') ? cell('Description') : '';
    if (description !== '') rule.description = description;
    rules.push(rule);
  }
  return problems.length > 0 ? { problems } : { rules };
}
