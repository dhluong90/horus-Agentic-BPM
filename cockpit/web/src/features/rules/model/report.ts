import type { SimulationReport } from '../../../api/rules/schemas';

/** The share of items in each count of a report, in whole percent of `total`; all zero for an empty report. */
export function shares(counts: SimulationReport['counts']): { changed: number; unchanged: number; other: number } {
  if (counts.total <= 0) return { changed: 0, unchanged: 0, other: 0 };
  const changed = Math.round((counts.changed / counts.total) * 100);
  const unchanged = Math.round((counts.unchanged / counts.total) * 100);
  return { changed, unchanged, other: Math.max(0, 100 - changed - unchanged) };
}

/** Items the report names in `rows` that exist beyond the 50 it keeps: changed plus failed, less the rows shown. */
export function hiddenRows(report: SimulationReport): number {
  return Math.max(0, report.counts.changed + report.counts.failed - report.rows.length);
}

/** A hash as the reviewer reads it: the first eight hex digits, the whole value on hover and for assistive tech. */
export const shortHash = (hash: string) => hash.slice(0, 8);
