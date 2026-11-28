/** The periods the Timing view offers. The engine reads whole hours and at most 31 days; 30 days stays inside. */
export const TIMING_WINDOWS = [
  { id: '24h', hours: 24 },
  { id: '7d', hours: 7 * 24 },
  { id: '30d', hours: 30 * 24 },
] as const;
export type TimingWindowId = (typeof TIMING_WINDOWS)[number]['id'];

const HOUR_MS = 3_600_000;

/** The start of a window as a whole hour (ISO, UTC), so the query holds still while the page is open. */
export function windowStart(now: number, id: TimingWindowId): string {
  const hours = TIMING_WINDOWS.find((w) => w.id === id)?.hours ?? 24;
  return new Date(Math.floor(now / HOUR_MS) * HOUR_MS - hours * HOUR_MS).toISOString();
}

const UNITS = [
  { unit: 'day', ms: 86_400_000 },
  { unit: 'hour', ms: 3_600_000 },
  { unit: 'minute', ms: 60_000 },
  { unit: 'second', ms: 1_000 },
] as const;
export type DurationUnit = (typeof UNITS)[number]['unit'];

/** The words of a duration: each unit with its number ("2 min"), and the text for under a second. */
export interface DurationWords {
  unit: (unit: DurationUnit, count: number) => string;
  under: string;
}

/** A duration, the two largest units that are not zero ("2 min 10 s"): whole-number arithmetic only. */
export function formatDuration(ms: number, words: DurationWords): string {
  if (ms < 1_000) return words.under;
  let rest = Math.round(ms / 1_000) * 1_000;
  const parts: string[] = [];
  for (const { unit, ms: size } of UNITS) {
    const whole = Math.floor(rest / size);
    rest -= whole * size;
    if (whole > 0 && parts.length < 2) parts.push(words.unit(unit, whole));
  }
  return parts.join(' ');
}

/** How close a figure is to the slowest one of the list, in whole percent, 0 to 100. */
export function shareOfSlowest(value: number | null | undefined, slowest: number): number {
  if (value === null || value === undefined || slowest <= 0) return 0;
  return Math.min(100, Math.round((value * 100) / slowest));
}

/** The steps that run slowest take the warm colour: the top quarter of the list's slowest 95th percentile. */
export const HOT_SHARE = 75;

/** The heat of a step, 1 to 4, from its share of the slowest: under 25, under 50, under 75, and 75 or more. */
export function heatLevel(share: number): 1 | 2 | 3 | 4 {
  return share >= HOT_SHARE ? 4 : share >= 50 ? 3 : share >= 25 ? 2 : 1;
}
