import type { HistoryReadingResult, HistoryReadings } from '../../../api/rules/schemas';

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

export type ReplayForm = { from: string; to: string; cap: string; ids?: string };

/** The request for the days picked: `to` is the day after the last day, as the engine's end is exclusive. Undefined when a field is not yet a date or a whole number. */
export function replayRequest(form: ReplayForm): { from: string; to: string; limit: number; decisionIds?: string[] } | undefined {
  const day = /^\d{4}-\d{2}-\d{2}$/;
  if (!day.test(form.from) || !day.test(form.to)) return undefined;
  const from = Date.parse(`${form.from}T00:00:00Z`);
  const last = Date.parse(`${form.to}T00:00:00Z`);
  if (Number.isNaN(from) || Number.isNaN(last)) return undefined;
  if (!/^\d{1,4}$/.test(form.cap.trim())) return undefined;
  const decisionIds = parseDecisionIds(form.ids ?? '');
  if (!validDecisionIds(decisionIds)) return undefined;
  return { from: new Date(from).toISOString(), to: new Date(last + DAY).toISOString(), limit: Number(form.cap.trim()), ...(decisionIds.length > 0 ? { decisionIds } : {}) };
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

/*
 * A replay read in steps (#96). The engine answers one request at a time, so the cockpit asks for a week of the period
 * (or fifty selected decisions) at a time, newest first, and keeps what has come in. When the reader cancels, what was
 * already replayed is still shown; the engine writes nothing, so no step leaves anything behind.
 */

const WEEK = 7 * DAY;
const BATCH = 50;

/** The decision ids typed or pasted into the form: split on spaces, commas and line breaks, each once, in order. */
export function parseDecisionIds(text: string): string[] {
  return [...new Set(text.split(/[\s,;]+/).map((id) => id.trim()).filter((id) => id !== ''))];
}

/** Whether every selected id is the shape the engine logs (letters and digits only, at most 64). */
export function validDecisionIds(ids: readonly string[]): boolean {
  return ids.every((id) => /^[A-Za-z0-9_-]{1,64}$/.test(id)) && ids.length <= 1000;
}

type Step = { from: string; to: string; limit?: number; decisionIds?: string[] };

/** The weekly windows of a period, newest first: each starts at most seven days before its end and none leaves the period. */
export function weeklyWindows(from: string, to: string): { from: string; to: string }[] {
  const start = Date.parse(from);
  const windows: { from: string; to: string }[] = [];
  for (let end = Date.parse(to); end > start; end -= WEEK) {
    windows.push({ from: new Date(Math.max(start, end - WEEK)).toISOString(), to: new Date(end).toISOString() });
  }
  return windows;
}

/** The steps of a period; a period the engine would refuse (over 31 days, or ending before it starts) goes in one step so the engine says why. */
function stepsOfPeriod(from: string, to: string): Step[] {
  const span = Date.parse(to) - Date.parse(from);
  return span > 0 && span <= 31 * DAY ? weeklyWindows(from, to) : [{ from, to }];
}

/** Joins the answers read so far into one, newest first. The counts are those of the decisions held. */
export function joinReadings(parts: readonly HistoryReadings[], request: { from: string; to: string }, truncated: boolean): HistoryReadings | undefined {
  const first = parts[0];
  if (first === undefined) return undefined;
  const seen = new Set<string>();
  const results = parts
    .flatMap((part) => part.results)
    .filter((result) => !seen.has(result.decisionId) && seen.add(result.decisionId))
    .sort((a, b) => Date.parse(b.evalClock) - Date.parse(a.evalClock));
  const counts = countGroups(results);
  return {
    ...first,
    from: request.from,
    to: request.to,
    decisionCount: results.length,
    replayedCount: counts.changed + counts.unchanged,
    changedCount: counts.changed,
    notReplayableCount: counts.notReplayable,
    truncated,
    results,
  };
}

export type ReplaySteps = {
  /** The answer so far; set after every step, so a cancelled replay can show it. */
  partial: HistoryReadings | undefined;
};

/**
 * Runs a replay step by step. `read` answers one step. Throws what `read` throws (an abort included); `onProgress` is
 * called with the joined answer after each step, so the caller keeps the partial result when it is stopped.
 */
export async function runReplay(
  request: { from: string; to: string; limit: number; decisionIds?: readonly string[] | undefined },
  read: (step: Step) => Promise<HistoryReadings>,
  onProgress: (joined: HistoryReadings, done: number, total: number) => void,
): Promise<HistoryReadings> {
  const ids = request.decisionIds ?? [];
  const steps: Step[] = ids.length > 0
    ? Array.from({ length: Math.ceil(ids.length / BATCH) }, (_, i) => ({ from: request.from, to: request.to, decisionIds: ids.slice(i * BATCH, (i + 1) * BATCH) }))
    : stepsOfPeriod(request.from, request.to);
  const parts: HistoryReadings[] = [];
  let held = 0;
  let truncated = false;
  for (const [index, step] of steps.entries()) {
    const remaining = ids.length > 0 ? undefined : request.limit - held;
    const answer = await read(remaining === undefined ? step : { ...step, limit: remaining });
    parts.push(answer);
    held += answer.results.length;
    truncated = truncated || answer.truncated;
    const reached = ids.length === 0 && held >= request.limit;
    // The cap was reached before the oldest week: older decisions were left unread.
    const more = reached && index < steps.length - 1;
    const joined = joinReadings(parts, request, truncated || more);
    if (joined !== undefined) onProgress(joined, index + 1, steps.length);
    if (reached) break;
  }
  const done = joinReadings(parts, request, truncated || (ids.length === 0 && held >= request.limit && parts.length < steps.length));
  if (done === undefined) throw new Error('A replay has at least one step');
  return ids.length > 0 && done.results.length > request.limit ? { ...done, results: done.results.slice(0, request.limit), truncated: true } : done;
}
