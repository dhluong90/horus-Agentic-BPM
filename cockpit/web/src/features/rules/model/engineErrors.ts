import type { HostError } from '../../../api/hostError';

/*
 * Where an engine validation error belongs. The engine answers 422 VALIDATION_FAILED with `violations`,
 * one entry per cell problem in document order: { ruleId, column, message }, where column is an input
 * or an output name. A row problem has no column; a document problem has neither. An engine without
 * `violations` names one location in `detail` ("Rule R2, input balance: ..."); that is read as a fallback.
 */

export type ErrorPlace =
  | { kind: 'cell'; ruleId: string; column: string; message: string }
  | { kind: 'row'; ruleId: string; message: string }
  | { kind: 'table'; message: string };

/** One located problem from the older single-detail form. */
export function placeError(detail: string | undefined): ErrorPlace {
  const text = (detail ?? '').trim();
  const cell = /^Rule (\S+), (?:input|column|output) ([A-Za-z_][A-Za-z0-9_]*): (.+)$/s.exec(text);
  if (cell) return { kind: 'cell', ruleId: cell[1] ?? '', column: cell[2] ?? '', message: cell[3] ?? '' };
  const row = /^[Rr]ule ([^\s,:]+): (.+)$/s.exec(text);
  if (row) return { kind: 'row', ruleId: row[1] ?? '', message: row[2] ?? '' };
  return { kind: 'table', message: text };
}

/** Every located problem of a refused save; empty when the error is not a validation failure. */
export function placeErrors(error: HostError | undefined): ErrorPlace[] {
  if (error?.kind !== 'problem' || error.code !== 'VALIDATION_FAILED') return [];
  const violations = error.violations ?? [];
  if (violations.length === 0) return [placeError(error.detail)];
  return violations.map((v): ErrorPlace => {
    if (v.ruleId !== null && v.column !== null) return { kind: 'cell', ruleId: v.ruleId, column: v.column, message: v.message };
    if (v.ruleId !== null) return { kind: 'row', ruleId: v.ruleId, message: v.message };
    return { kind: 'table', message: v.message };
  });
}
