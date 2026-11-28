import type { CalendarValue } from '../../../api/processes/schemas';

/*
 * The editor's working copy of a calendar. It holds only what was typed, in the shape the engine reads: a time zone,
 * local ranges per weekday and dated holidays. Whether a zone or a range is allowed is the engine's call (it answers
 * 400 INPUT_INVALID); here only the shape of each field is checked so that a request is never sent empty.
 */

export const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;
export type Weekday = (typeof WEEKDAYS)[number];

export interface DraftRange { from: string; to: string }
export interface DraftHoliday { date: string; name: string }
export interface CalendarDraft {
  zone: string;
  week: Record<Weekday, DraftRange[]>;
  holidays: DraftHoliday[];
}

const RANGE = /^([0-2][0-9]:[0-5][0-9])-([0-2][0-9]:[0-5][0-9])$/;
const TIME = /^([01][0-9]|2[0-3]):[0-5][0-9]$|^24:00$/;
const DATE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;

export const emptyWeek = (): Record<Weekday, DraftRange[]> => ({ MONDAY: [], TUESDAY: [], WEDNESDAY: [], THURSDAY: [], FRIDAY: [], SATURDAY: [], SUNDAY: [] });

/** The calendar the engine holds, as a draft: holidays in date order. */
export function toDraft(calendar: CalendarValue): CalendarDraft {
  const week = emptyWeek();
  for (const day of WEEKDAYS) {
    week[day] = (calendar.week?.[day] ?? []).flatMap((text) => {
      const match = RANGE.exec(text);
      return match === null ? [] : [{ from: match[1] ?? '', to: match[2] ?? '' }];
    });
  }
  const holidays = Object.entries(calendar.holidays ?? {}).sort(([a], [b]) => a.localeCompare(b)).map(([date, name]) => ({ date, name }));
  return { zone: calendar.zone, week, holidays };
}

/** The proposal's calendar: a weekday without a range is left out, as the engine reads a day that is not worked. */
export function toCalendar(draft: CalendarDraft): CalendarValue {
  const week: Record<string, string[]> = {};
  for (const day of WEEKDAYS) if (draft.week[day].length > 0) week[day] = draft.week[day].map((range) => `${range.from}-${range.to}`);
  const holidays: Record<string, string> = {};
  for (const holiday of draft.holidays) holidays[holiday.date] = holiday.name.trim();
  return { zone: draft.zone.trim(), week, holidays };
}

/** What a new calendar starts from: Monday to Friday, 08:00 to 17:00, no holiday. The time zone is the author's to enter. */
export function newDraft(): CalendarDraft {
  const week = emptyWeek();
  for (const day of WEEKDAYS.slice(0, 5)) week[day] = [{ from: '08:00', to: '17:00' }];
  return { zone: '', week, holidays: [] };
}

/** Where the draft is not in the shape the engine reads, as a list of problems; empty when it can be sent. */
export type DraftProblem = { kind: 'zone' } | { kind: 'range'; day: Weekday; index: number } | { kind: 'holiday'; index: number } | { kind: 'duplicate'; date: string };

export function draftProblems(draft: CalendarDraft): DraftProblem[] {
  const problems: DraftProblem[] = [];
  if (draft.zone.trim() === '') problems.push({ kind: 'zone' });
  for (const day of WEEKDAYS) {
    draft.week[day].forEach((range, index) => { if (!TIME.test(range.from) || !TIME.test(range.to)) problems.push({ kind: 'range', day, index }); });
  }
  const seen = new Set<string>();
  draft.holidays.forEach((holiday, index) => {
    if (!DATE.test(holiday.date)) problems.push({ kind: 'holiday', index });
    else if (seen.has(holiday.date)) problems.push({ kind: 'duplicate', date: holiday.date });
    seen.add(holiday.date);
  });
  return problems;
}

/** Whether the draft differs from the calendar in force: the editor sends nothing that changes nothing. */
export function isChanged(draft: CalendarDraft, inForce: CalendarValue | undefined): boolean {
  return inForce === undefined || JSON.stringify(toCalendar(draft)) !== JSON.stringify(toCalendar(toDraft(inForce)));
}
