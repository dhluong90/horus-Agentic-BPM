import type { TaskSummary } from '../../api/processes/schemas';
import type { LoggedDecision } from '../../api/rules/auditSchemas';
import type { Locale } from '../../i18n/I18nProvider';
import { overdueParts, slaOf } from '../tasks/model/queue';

/*
 * What Home shows that is not the engines' answer: the calendar day a due time falls on, the longest of the overdue
 * times the engine already computed, and the hour a logged decision falls in. Nothing here decides who is late.
 */

const sameDay = (a: Date, b: Date) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

/** Tasks that fall due on the calendar day of `now` and are not already overdue (the engine says which are). */
export function dueToday(tasks: readonly TaskSummary[], now: Date): TaskSummary[] {
  return tasks.filter((task) => task.dueAt != null && slaOf(task, undefined) !== 'BREACHED' && sameDay(new Date(task.dueAt), now));
}

/** The largest `overdueBy` of the tasks, as the engine wrote it; undefined when none carries one. */
export function longestOverdue(tasks: readonly TaskSummary[]): string | undefined {
  let best: { text: string; minutes: number } | undefined;
  for (const task of tasks) {
    const parts = overdueParts(task.overdueBy);
    if (parts === undefined || task.overdueBy == null) continue;
    const minutes = (parts.days * 24 + parts.hours) * 60 + parts.minutes;
    if (best === undefined || minutes > best.minutes) best = { text: task.overdueBy, minutes };
  }
  return best?.text;
}

/** Up to two capital letters of a name, for the round marker beside a row. */
export function initials(text: string): string {
  const words = text.split(/[\s·\-_.]+/).filter((word) => /\p{L}/u.test(word));
  const letters = words.length > 1 ? [words[0], words[words.length - 1]] : [words[0]];
  return letters.map((word) => (word ?? '').charAt(0)).join('').toUpperCase();
}

/** The local day as `[from, to)` in UTC instants for the decision log. */
export function dayPeriod(now: Date): { from: string; to: string } {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
  return { from: start.toISOString(), to: end.toISOString() };
}

/** Counts of the decisions by local hour, 24 slots. */
export function byHour(decisions: readonly Pick<LoggedDecision, 'evaluatedAt'>[]): number[] {
  const hours = Array.from({ length: 24 }, () => 0);
  for (const decision of decisions) {
    const hour = new Date(decision.evaluatedAt).getHours();
    if (!Number.isNaN(hour)) hours[hour] = (hours[hour] ?? 0) + 1;
  }
  return hours;
}

/** The hours the chart shows: the working day, widened to any hour outside it that has decisions. */
export function chartHours(counts: readonly number[]): number[] {
  const busy = counts.flatMap((count, hour) => (count > 0 ? [hour] : []));
  const first = Math.min(8, ...busy);
  const last = Math.max(17, ...busy);
  return Array.from({ length: last - first + 1 }, (_, n) => first + n);
}

/** The hour with the most decisions (the earliest on a tie); undefined when there are none. */
export function peakHour(counts: readonly number[]): number | undefined {
  let peak: number | undefined;
  counts.forEach((count, hour) => {
    if (count > 0 && (peak === undefined || count > (counts[peak] ?? 0))) peak = hour;
  });
  return peak;
}

/** "8 AM" or "08 giờ": the hour in the user's language. */
export function hourLabel(hour: number, locale: Locale): string {
  return new Intl.DateTimeFormat(locale === 'vi' ? 'vi-VN' : 'en-US', { hour: 'numeric' }).format(new Date(2026, 0, 1, hour));
}

/** A time today ("09:30"), or a short date for another day ("21 Nov"). */
export function shortWhen(iso: string, now: Date, locale: Locale): string {
  const date = new Date(iso);
  const tag = locale === 'vi' ? 'vi-VN' : 'en-GB';
  if (sameDay(date, now)) return new Intl.DateTimeFormat(tag, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(date);
  return new Intl.DateTimeFormat(tag, { day: 'numeric', month: 'short' }).format(date);
}

export const isToday = (iso: string, now: Date) => sameDay(new Date(iso), now);
