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

/** The part of a `:validate` problem placement needs (RLE-090). */
export interface CheckProblem {
  ruleId: string | null;
  column: string | null;
  message: Readonly<Record<string, string>>;
}

/**
 * Where each problem of the engine's check goes, with its sentence in the reader's language. A problem on a row
 * that is no longer in the table, or on a column the table does not show, moves up to its row or to the table,
 * so no problem is ever lost. The engine's message for `locale` is used; when the engine has none in that
 * language, `fallback` (one plain sentence of the cockpit) keeps the screen in one language.
 */
export function placeProblems(
  problems: readonly CheckProblem[],
  rowIds: ReadonlySet<string>,
  columns: ReadonlySet<string>,
  locale: string,
  fallback: string,
): ErrorPlace[] {
  return problems.map((p): ErrorPlace => {
    const message = p.message[locale] ?? fallback;
    if (p.ruleId === null || !rowIds.has(p.ruleId)) return { kind: 'table', message };
    if (p.column !== null && columns.has(p.column)) return { kind: 'cell', ruleId: p.ruleId, column: p.column, message };
    return { kind: 'row', ruleId: p.ruleId, message };
  });
}
