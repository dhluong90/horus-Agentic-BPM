/*
 * Simulation reports as the engine stores them (RLE-199 to RLE-201): counts that add up to the total, at most 50 changed
 * or failed rows, readings in every language and never an input value. A replay reads a synthetic log of decisions,
 * as the mock has no decision log of its own; a report is immutable once stored.
 */

import type { Readings } from './ruleReadings';

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export interface Outcome { kind: string; ruleIds: string[]; readings: Readings; then: Json }
export interface ReportRow { reference: string | null; decisionId: string | null; category: 'CHANGED' | 'FAILED'; before: Omit<Outcome, 'then'> | null; after: Omit<Outcome, 'then'> | null }
export interface ReportScope { source: 'SAMPLES' | 'REPLAY'; from: string | null; to: string | null; limit: number | null; truncated: boolean }
export interface StoredReport {
  id: string; ruleSet: string; version: number; canonicalHash: string; createdBy: string; createdAt: string;
  scope: ReportScope; counts: { total: number; changed: number; unchanged: number; failed: number; notReplayable: number }; rows: ReportRow[]; rowsTruncated: boolean;
}

/** The most changed or failed rows a report keeps. */
export const ROWS_MAX = 50;
/** The decisions the synthetic log holds for any period. */
export const LOG_SIZE = 60;

interface InputClause { name: string; type: string; allowed?: string[]; mask?: boolean }

const AMOUNTS = [5_000_000, 15_000_000, 30_000_000, 60_000_000, 120_000_000];
const COUNTS = [2, 8, 15, 25, 40];

/** The values one input takes across the synthetic log; every value of a column meets every value of the others. */
function valuesOf(input: InputClause): Json[] {
  if (input.allowed !== undefined && input.allowed.length > 0) return input.allowed;
  if (input.type === 'money') return AMOUNTS.map((amount) => ({ currency: 'VND', amount }));
  if (input.type === 'integer' || input.type === 'decimal') return COUNTS;
  if (input.type === 'boolean') return [false, true];
  return ['A', 'B', 'C'];
}

/** `count` input sets walking every combination of the inputs' values in turn (a mixed-radix count). */
export function syntheticInputs(inputs: readonly InputClause[], count: number): Record<string, Json>[] {
  const columns = inputs.map((input) => ({ name: input.name, values: valuesOf(input) }));
  return Array.from({ length: count }, (_, index) => {
    let rest = index;
    const row: Record<string, Json> = {};
    for (const column of columns) {
      row[column.name] = column.values[rest % column.values.length] ?? null;
      rest = Math.floor(rest / column.values.length);
    }
    return row;
  });
}

/** A ULID-shaped id for the n-th logged decision of the synthetic log: Crockford base32, the tail spread by a fixed multiplier. */
export function decisionIdOf(index: number): string {
  const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  let state = (index + 1) * 2654435761 % 4294967296;
  let tail = '';
  for (let i = 0; i < 16; i += 1) {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    tail += alphabet[state >>> 27] ?? '0';
  }
  return `01KB2R6Q3C${tail}`;
}

const strip = ({ kind, ruleIds, readings }: Outcome) => ({ kind, ruleIds, readings });

/** Runs the items beside the baseline and keeps the report's counts and rows; each item falls in exactly one count. */
export function runReport(items: readonly { reference: string | null; decisionId: string | null; inputs: Record<string, Json>; masked: boolean }[], current: (inputs: Record<string, Json>) => Outcome, baseline: ((inputs: Record<string, Json>) => Outcome) | null): Pick<StoredReport, 'counts' | 'rows' | 'rowsTruncated'> {
  const counts = { total: items.length, changed: 0, unchanged: 0, failed: 0, notReplayable: 0 };
  const rows: ReportRow[] = [];
  let changedOrFailed = 0;
  for (const item of items) {
    if (item.masked) { counts.notReplayable += 1; continue; }
    const after = current(item.inputs);
    const before = baseline === null ? null : baseline(item.inputs);
    const changed = before === null || after.kind !== before.kind || JSON.stringify(after.then) !== JSON.stringify(before.then);
    if (!changed) { counts.unchanged += 1; continue; }
    counts.changed += 1;
    changedOrFailed += 1;
    if (rows.length < ROWS_MAX) rows.push({ reference: item.reference, decisionId: item.decisionId, category: 'CHANGED', before: before === null ? null : strip(before), after: strip(after) });
  }
  return { counts, rows, rowsTruncated: changedOrFailed > rows.length };
}
