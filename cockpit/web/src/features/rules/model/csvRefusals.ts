import { toHostError } from '../../../api/hostError';
import type { MessageKey } from '../../../i18n/en';

/*
 * The engine refuses a spreadsheet file one place at a time and names each with a stable `CSV_*` code. The reason
 * is said in the user's language from the code, so a screen stays in one language; no reason quotes a cell, so a
 * value of a masked input never appears (RLE-193). A code not listed here falls back to the engine's sentence.
 */
const CODES = [
  'CSV_NO_TABLE_FORM', 'CSV_EMPTY', 'CSV_ENCODING', 'CSV_FILE_INVALID', 'CSV_COLUMN_UNKNOWN', 'CSV_COLUMN_MISSING',
  'CSV_COLUMN_DUPLICATE', 'CSV_COLUMN_ORDER', 'CSV_RULE_ID_INVALID', 'CSV_RULE_DUPLICATE', 'CSV_ROW_SHAPE', 'CSV_CELL_INVALID',
  'CSV_CELL_EMPTY', 'CSV_CELL_SCIENTIFIC', 'CSV_CELL_SEPARATORS', 'CSV_CELL_DATE', 'CSV_CELL_NOT_A_NUMBER', 'CSV_CELL_NO_CURRENCY',
] as const;

export function csvReason(code: string | undefined): MessageKey | undefined {
  const known = CODES.find((c) => c === code);
  return known === undefined ? undefined : `csv.${known}`;
}

/** A problem about a column of the file as a whole, not about a row: the place is the column. */
export function isColumnProblem(code: string | undefined): boolean {
  return code !== undefined && code.startsWith('CSV_COLUMN_');
}

/** True when the engine refused with this `CSV_*` code on any of its problems. */
export function refusedWith(error: unknown, code: string): boolean {
  const hostError = toHostError(error);
  return hostError.kind === 'problem' && (hostError.violations ?? []).some((v) => v.code === code);
}
