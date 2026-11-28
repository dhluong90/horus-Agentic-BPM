import { describe, expect, it } from 'vitest';
import { draftProblems, isChanged, newDraft, toCalendar, toDraft } from './calendarDraft';

const CALENDAR = { zone: 'Asia/Ho_Chi_Minh', week: { MONDAY: ['08:00-12:00', '13:00-17:00'], SATURDAY: ['08:00-12:00'] }, holidays: { '2027-02-08': 'Tet', '2026-09-02': 'National Day' } };

describe('the calendar draft', () => {
  it('a calendar read and written back is the same calendar, holidays in date order', () => {
    expect(toCalendar(toDraft(CALENDAR))).toEqual(CALENDAR);
    expect(Object.keys(toCalendar(toDraft(CALENDAR)).holidays ?? {})).toEqual(['2026-09-02', '2027-02-08']);
  });

  it('a day without a range is left out of the proposal', () => {
    const draft = toDraft(CALENDAR);
    draft.week.MONDAY = [];
    expect(toCalendar(draft).week).toEqual({ SATURDAY: ['08:00-12:00'] });
  });

  it('only the shape of a field is checked: an empty zone, a half-typed time, a bad or repeated date', () => {
    const draft = toDraft(CALENDAR);
    expect(draftProblems(draft)).toEqual([]);
    draft.zone = ' ';
    draft.week.MONDAY = [{ from: '', to: '12:00' }];
    draft.holidays = [{ date: '2027-02-30x', name: 'x' }, { date: '2027-02-08', name: 'a' }, { date: '2027-02-08', name: 'b' }];
    expect(draftProblems(draft)).toEqual([{ kind: 'zone' }, { kind: 'range', day: 'MONDAY', index: 0 }, { kind: 'holiday', index: 0 }, { kind: 'duplicate', date: '2027-02-08' }]);
  });

  it('nothing changed means nothing to send; a new calendar always has something to send', () => {
    expect(isChanged(toDraft(CALENDAR), CALENDAR)).toBe(false);
    const draft = toDraft(CALENDAR);
    draft.holidays.push({ date: '2027-04-30', name: 'Reunification Day' });
    expect(isChanged(draft, CALENDAR)).toBe(true);
    expect(isChanged(newDraft(), undefined)).toBe(true);
  });

  it('a new calendar starts on Monday to Friday, 08:00 to 17:00, without a zone', () => {
    const calendar = toCalendar(newDraft());
    expect(Object.keys(calendar.week ?? {})).toEqual(['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']);
    expect(calendar.zone).toBe('');
  });
});
