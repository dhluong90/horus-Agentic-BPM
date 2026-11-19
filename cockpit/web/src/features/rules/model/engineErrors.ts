/*
 * Where an engine validation error belongs. The engine of this release returns one 422 VALIDATION_FAILED
 * with a `detail` text that names the row and, for conditions, the input:
 *   "Rule R2, input balance: compares a money with a number"   -> the cell R2 / balance
 *   "rule R3: Unknown input 'x' at position 1"                  -> the row R3 (output cells are not named)
 *   "Rule R3: unknown output 'fee2'"                            -> the row R3
 * Anything else stays a message for the whole table. The text after the location is the engine's own.
 */

export type ErrorPlace =
  | { kind: 'cell'; ruleId: string; column: string; message: string }
  | { kind: 'row'; ruleId: string; message: string }
  | { kind: 'table'; message: string };

export function placeError(detail: string | undefined): ErrorPlace {
  const text = (detail ?? '').trim();
  const cell = /^Rule (\S+), input ([A-Za-z_][A-Za-z0-9_]*): (.+)$/s.exec(text);
  if (cell) return { kind: 'cell', ruleId: cell[1] ?? '', column: cell[2] ?? '', message: cell[3] ?? '' };
  const row = /^[Rr]ule ([^\s,:]+): (.+)$/s.exec(text);
  if (row) return { kind: 'row', ruleId: row[1] ?? '', message: row[2] ?? '' };
  return { kind: 'table', message: text };
}
