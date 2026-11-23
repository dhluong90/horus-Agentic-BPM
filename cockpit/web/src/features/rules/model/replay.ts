import type { HistoryReadingResult } from '../../../api/rules/schemas';

/*
 * The period and the cap of a replay (RLE-202), and how the decisions of its answer are grouped. Only the shape of
 * the request is checked here; the engine decides whether the period and the cap are allowed (QUERY_INVALID,
 * READINGS_TOO_LARGE) and the screen words its answer.
 */

/** The most decisions one replay request reads when no cap is given (the engine's `simulation` readings limit). */
export const DEFAULT_CAP = 100;

export type Preset = 'week' | 'thirty' | 'lastMonth';

const DAY = 86_400_000;

/** A calendar day as `2026-11-19` (UTC), the value of a date field. */
export function dayOf(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

/** The first and the last day (both included) a preset names, counted from `now`; days are UTC, as the engine's clock. */
export function presetDays(preset: Preset, now: number): { from: string; to: string } {
  const today = Date.parse(`${dayOf(now)}T00:00:00Z`);
  if (preset === 'week') return { from: dayOf(today - 6 * DAY), to: dayOf(today) };
  if (preset === 'thirty') return { from: dayOf(today - 29 * DAY), to: dayOf(today) };
  const first = new Date(today);
  const start = Date.UTC(first.getUTCFullYear(), first.getUTCMonth() - 1, 1);
  const end = Date.UTC(first.getUTCFullYear(), first.getUTCMonth(), 1);
  return { from: dayOf(start), to: dayOf(end - DAY) };
}

export type ReplayForm = { from: string; to: string; cap: string };

/** The request for the days picked: `to` is the day after the last day, as the engine's end is exclusive. Undefined when a field is not yet a date or a whole number. */
export function replayRequest(form: ReplayForm): { from: string; to: string; limit: number } | undefined {
  const day = /^\d{4}-\d{2}-\d{2}$/;
  if (!day.test(form.from) || !day.test(form.to)) return undefined;
  const from = Date.parse(`${form.from}T00:00:00Z`);
  const last = Date.parse(`${form.to}T00:00:00Z`);
  if (Number.isNaN(from) || Number.isNaN(last)) return undefined;
  if (!/^\d{1,4}$/.test(form.cap.trim())) return undefined;
  return { from: new Date(from).toISOString(), to: new Date(last + DAY).toISOString(), limit: Number(form.cap.trim()) };
}

export type ReplayGroup = 'changed' | 'unchanged' | 'notReplayable';

export function groupOf(result: Pick<HistoryReadingResult, 'changed' | 'code'>): ReplayGroup {
  if (result.code === 'REPLAY_NOT_POSSIBLE') return 'notReplayable';
  return result.changed ? 'changed' : 'unchanged';
}

export function countGroups(results: readonly Pick<HistoryReadingResult, 'changed' | 'code'>[]): Record<ReplayGroup, number> {
  const counts: Record<ReplayGroup, number> = { changed: 0, unchanged: 0, notReplayable: 0 };
  for (const result of results) counts[groupOf(result)] += 1;
  return counts;
}

/** The last eight characters of a decision id, as the decision list names it, for a row; the full id is its title. */
export function shortId(decisionId: string): string {
  return decisionId.slice(-8);
}
