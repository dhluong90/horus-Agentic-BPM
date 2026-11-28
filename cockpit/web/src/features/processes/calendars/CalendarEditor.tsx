import { useId, useMemo, useState } from 'react';
import { toHostError } from '../../../api/hostError';
import type { CalendarChange, CalendarInForce } from '../../../api/processes/schemas';
import { formatRoute } from '../../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import { Tabs } from '../../rules/components/Tabs';
import { useProcessesSession } from '../api/processesContext';
import { calendarName, zoneLabel } from './calendarNames';
import { ChangeState } from './ChangeState';
import { draftProblems, isChanged, newDraft, toCalendar, toDraft, WEEKDAYS, type CalendarDraft, type DraftProblem, type Weekday } from './calendarDraft';
import { useCalendar, useCalendarChanges, useProposeCalendarChange } from './useCalendarChanges';

type Part = 'hours' | 'holidays' | 'history';

/**
 * One calendar: working hours per weekday, holidays and the changes made so far, one at a time. A change is proposed
 * with a reason and waits for a second person; the screen says so before it is sent and after, and never shows a
 * change as applied before the engine says it is (PD-131, PRE-182).
 */
export function CalendarEditor({ calendarKey, pageLimit }: { calendarKey: string; pageLimit: number }) {
  const { t } = useI18n();
  const calendar = useCalendar(calendarKey);
  const head = <PageHead id="calendar-edit-title" title={calendarName(calendarKey)} subtitle={t('calendars.editSubtitle')} />;
  if (calendar.isPending) return <section className="workspace" aria-busy="true">{head}<LoadingRows rows={6} /></section>;
  if (calendar.isError) return <section className="workspace" aria-labelledby="calendar-edit-title">{head}<article className="card"><AreaError error={calendar.error} onRetry={() => { void calendar.refetch(); }} /></article></section>;
  return <Form key={`${calendarKey}:${String(calendar.data?.version ?? 0)}`} calendarKey={calendarKey} inForce={calendar.data ?? undefined} pageLimit={pageLimit} onReload={() => { void calendar.refetch(); }} />;
}

function Form({ calendarKey, inForce, pageLimit, onReload }: { calendarKey: string; inForce: CalendarInForce | undefined; pageLimit: number; onReload: () => void }) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  const mayEdit = can('processes:calendar:manage');
  // Read first; one Edit control opens every field and the dock. A calendar that does not exist yet opens editing.
  const [editing, setEditing] = useState(inForce === undefined && mayEdit);
  const [draft, setDraft] = useState<CalendarDraft>(() => (inForce === undefined ? newDraft() : toDraft(inForce.calendar)));
  const [reason, setReason] = useState('');
  const [part, setPart] = useState<Part>('hours');
  const propose = useProposeCalendarChange(calendarKey, inForce?.version ?? 0);
  const waiting = useCalendarChanges(pageLimit);
  const problems = useMemo(() => draftProblems(draft), [draft]);
  const reasonId = useId();
  const hintId = useId();
  const changed = isChanged(draft, inForce?.calendar);
  const mine = (waiting.data?.data ?? []).filter((change) => change.calendarKey === calendarKey);
  const sent = propose.data;
  const failure = propose.isError ? toHostError(propose.error) : undefined;
  const conflict = failure?.kind === 'problem' && failure.status === 409;
  const ready = mayEdit && changed && problems.length === 0 && reason.trim() !== '' && !propose.isPending && sent === undefined;
  const set = (next: CalendarDraft) => { setDraft(next); propose.reset(); };
  return (
    <section className="workspace calendar-edit" aria-labelledby="calendar-edit-title">
      <PageHead id="calendar-edit-title" title={calendarName(calendarKey)} subtitle={inForce === undefined ? t('calendars.newSubtitle') : t('calendars.editSubtitle')}
        action={mayEdit && !editing && sent === undefined ? <button type="button" className="btn btn--secondary" onClick={() => { setEditing(true); }}>{t('calendars.edit')}</button> : undefined} />
      {mine[0] !== undefined && sent === undefined ? (
        <p className="calendar-edit__note" role="status">
          <ChangeState change={mine[0]} /> <a className="link-action" href={formatRoute({ name: 'calendar-change', changeId: mine[0].id })}>{t('calendars.openChange')}</a>
        </p>
      ) : null}
      <article className="card card--diagram">
        <Tabs label={t('calendars.parts')} selected={part} onSelect={setPart} items={[
          { id: 'hours', label: t('calendars.part.hours') },
          { id: 'holidays', label: t('calendars.part.holidays', { count: String(draft.holidays.length) }) },
          { id: 'history', label: t('calendars.part.history') },
        ]}>
          {part === 'history' ? <History calendarKey={calendarKey} pageLimit={pageLimit} /> : (
            <fieldset className="calendar-edit__fields" disabled={!editing || sent !== undefined}>
              <legend className="sr-only">{t(part === 'hours' ? 'calendars.part.hours' : 'calendars.part.holidays', { count: String(draft.holidays.length) })}</legend>
              {part === 'hours' ? <Hours draft={draft} problems={problems} editing={editing} onChange={set} /> : <Holidays draft={draft} problems={problems} editing={editing} onChange={set} />}
            </fieldset>
          )}
        </Tabs>
      </article>
      {!mayEdit ? <p className="muted" role="note">{t('calendars.readOnly')}</p> : !editing ? null : (
        <article className="card card--pad calendar-edit__dock" aria-label={t('calendars.dock')}>
          {sent !== undefined ? <Sent change={sent} /> : (
            <>
              <div className="calendar-edit__reason">
                <label htmlFor={reasonId} className="field__label">{t('calendars.reason')}</label>
                <input id={reasonId} className="field__input" value={reason} maxLength={500} aria-describedby={hintId} onChange={(event) => { setReason(event.target.value); propose.reset(); }} />
                <p id={hintId} className="muted">{t('calendars.fourEyes')}</p>
              </div>
              <div className="dialog__actions">
                {inForce !== undefined ? <button type="button" className="btn btn--quiet" onClick={() => { setDraft(toDraft(inForce.calendar)); setReason(''); propose.reset(); setEditing(false); }}>{t('calendars.stopEdit')}</button> : null}
                <button type="button" className="btn btn--primary" disabled={!ready} aria-busy={propose.isPending}
                  onClick={() => { propose.mutate({ calendar: toCalendar(draft), reason: reason.trim() }); }}>{propose.isPending ? t('calendar.deciding') : t('calendars.send')}</button>
              </div>
              {failure !== undefined ? (
                <p className="field__error" role="alert">
                  {conflict ? t('calendars.conflict') : t('calendars.rejected')}
                  {conflict ? <> <button type="button" className="btn btn--quiet btn--small hit" onClick={onReload}>{t('calendars.reload')}</button></> : null}
                </p>
              ) : null}
            </>
          )}
        </article>
      )}
    </section>
  );
}

function Sent({ change }: { change: CalendarChange }) {
  const { t } = useI18n();
  const applied = change.status === 'APPROVED';
  return (
    <div className="calendar-sent" role="status">
      <span>{applied ? t('calendars.applied') : t('calendars.sent')}</span>
      <ChangeState change={change} />
      <a className="link-action" href={formatRoute({ name: 'calendar-change', changeId: change.id })}>{t('calendars.openChange')}</a>
    </div>
  );
}

function problemFor(problems: DraftProblem[], kind: DraftProblem['kind']) { return problems.some((p) => p.kind === kind); }

/** The zones this browser knows, each read as city and offset, sorted by that reading. */
function useZoneChoices(current: string) {
  const { t } = useI18n();
  return useMemo(() => {
    const ids = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];
    const all = ids.includes(current) || current === '' ? ids : [current, ...ids];
    const now = new Date();
    return all.map((id) => ({ id, label: zoneLabel(id, t, now) })).sort((a, b) => a.label.localeCompare(b.label));
  }, [current, t]);
}

function Hours({ draft, problems, editing, onChange }: { draft: CalendarDraft; problems: DraftProblem[]; editing: boolean; onChange: (next: CalendarDraft) => void }) {
  const { t } = useI18n();
  const zoneId = useId();
  const zones = useZoneChoices(draft.zone);
  const setDay = (day: Weekday, ranges: CalendarDraft['week'][Weekday]) => { onChange({ ...draft, week: { ...draft.week, [day]: ranges } }); };
  return (
    <div className="calendar-hours">
      <div className="calendar-hours__zone">
        {editing ? (
          <>
            <label htmlFor={zoneId} className="field__label">{t('calendar.zone')}</label>
            <select id={zoneId} className="field__input" value={draft.zone} aria-invalid={problemFor(problems, 'zone')} onChange={(event) => { onChange({ ...draft, zone: event.target.value }); }}>
              {draft.zone === '' ? <option value="">{t('calendars.zoneChoose')}</option> : null}
              {zones.map((zone) => <option key={zone.id} value={zone.id}>{zone.label}</option>)}
            </select>
          </>
        ) : (
          <>
            <span className="field__label">{t('calendar.zone')}</span>
            <span className="calendar-hours__zone-name">{draft.zone === '' ? t('rulesArea.none') : zoneLabel(draft.zone, t)}</span>
          </>
        )}
      </div>
      <ul className="calendar-hours__days">
        {WEEKDAYS.map((day) => {
          const ranges = draft.week[day];
          const dayName = t(`calendar.day.${day}`);
          return (
            <li key={day} className="calendar-hours__day">
              <span className="calendar-hours__name">{dayName}</span>
              <div className="calendar-hours__ranges">
                {!editing ? (
                  ranges.length === 0 ? <span className="muted">{t('calendars.notWorked')}</span> : <span className="tnum">{ranges.map((range) => `${range.from} – ${range.to}`).join(', ')}</span>
                ) : (
                  <>
                    {ranges.length === 0 ? <span className="muted">{t('calendars.notWorked')}</span> : ranges.map((range, index) => {
                      const bad = problems.some((p) => p.kind === 'range' && p.day === day && p.index === index);
                      const edit = (next: Partial<typeof range>) => { setDay(day, ranges.map((r, i) => (i === index ? { ...r, ...next } : r))); };
                      return (
                        <span key={index} className="calendar-hours__range">
                          <input type="text" inputMode="numeric" placeholder="08:00" maxLength={5} className="field__input field__input--compact tnum" aria-label={t('calendars.from', { day: dayName, n: String(index + 1) })} value={range.from} aria-invalid={bad} onChange={(event) => { edit({ from: event.target.value }); }} />
                          <span aria-hidden="true">–</span>
                          <input type="text" inputMode="numeric" placeholder="08:00" maxLength={5} className="field__input field__input--compact tnum" aria-label={t('calendars.to', { day: dayName, n: String(index + 1) })} value={range.to} aria-invalid={bad} onChange={(event) => { edit({ to: event.target.value }); }} />
                          <button type="button" className="btn btn--quiet btn--small hit calendar-icon-action" aria-label={t('calendars.removeRange', { day: dayName, n: String(index + 1) })} title={t('calendars.remove')} onClick={() => { setDay(day, ranges.filter((_, i) => i !== index)); }}><span aria-hidden="true">×</span></button>
                        </span>
                      );
                    })}
                    <button type="button" className="btn btn--quiet btn--small hit calendar-icon-action" disabled={ranges.length >= 8} aria-label={t('calendars.addRange', { day: dayName })} title={t('calendars.add')}
                      onClick={() => { setDay(day, [...ranges, ranges.length === 0 ? { from: '08:00', to: '17:00' } : { from: '13:00', to: '17:00' }]); }}><span aria-hidden="true">+</span></button>
                  </>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function Holidays({ draft, problems, editing, onChange }: { draft: CalendarDraft; problems: DraftProblem[]; editing: boolean; onChange: (next: CalendarDraft) => void }) {
  const { t, locale } = useI18n();
  const edit = (index: number, next: Partial<CalendarDraft['holidays'][number]>) => { onChange({ ...draft, holidays: draft.holidays.map((h, i) => (i === index ? { ...h, ...next } : h)) }); };
  const day = (date: string) => (date === '' ? '' : new Intl.DateTimeFormat(locale === 'vi' ? 'vi-VN' : 'en-GB', { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(`${date}T00:00:00Z`)));
  return (
    <div className="calendar-holidays">
      {draft.holidays.length === 0 ? <p className="muted">{t('calendar.noHolidays')}</p> : !editing ? (
        <ul className="calendar-holidays__list">
          {draft.holidays.map((holiday, index) => <li key={index} className="calendar-holidays__item calendar-holidays__item--read"><span className="tnum">{day(holiday.date)}</span><span>{holiday.name}</span></li>)}
        </ul>
      ) : (
        <ul className="calendar-holidays__list">
          {draft.holidays.map((holiday, index) => {
            const bad = problems.some((p) => (p.kind === 'holiday' && p.index === index) || (p.kind === 'duplicate' && p.date === holiday.date));
            return (
              <li key={index} className="calendar-holidays__item">
                <input type="date" className="field__input field__input--compact tnum" aria-label={t('calendars.holidayDate', { n: String(index + 1) })} value={holiday.date} aria-invalid={bad} onChange={(event) => { edit(index, { date: event.target.value }); }} />
                <input className="field__input field__input--compact" aria-label={t('calendars.holidayName', { n: String(index + 1) })} value={holiday.name} maxLength={200} onChange={(event) => { edit(index, { name: event.target.value }); }} />
                <button type="button" className="btn btn--quiet btn--small hit calendar-icon-action" aria-label={t('calendars.removeHoliday', { n: String(index + 1) })} title={t('calendars.remove')} onClick={() => { onChange({ ...draft, holidays: draft.holidays.filter((_, i) => i !== index) }); }}><span aria-hidden="true">×</span></button>
              </li>
            );
          })}
        </ul>
      )}
      {editing ? <button type="button" className="btn btn--quiet btn--small hit" disabled={draft.holidays.length >= 2000} onClick={() => { onChange({ ...draft, holidays: [...draft.holidays, { date: '', name: '' }] }); }}>{t('calendars.addHoliday')}</button> : null}
    </div>
  );
}

/** Every change made to this calendar, decided ones: who proposed it, who decided it and when; each opens its review. */
function History({ calendarKey, pageLimit }: { calendarKey: string; pageLimit: number }) {
  const { t } = useI18n();
  const approved = useCalendarChanges(pageLimit, 'APPROVED');
  const rejected = useCalendarChanges(pageLimit, 'REJECTED');
  if (approved.isPending || rejected.isPending) return <LoadingRows rows={3} />;
  const failed = approved.isError ? approved : rejected.isError ? rejected : undefined;
  if (failed?.isError) return <AreaError error={failed.error} onRetry={() => { void failed.refetch(); }} />;
  const items = [...(approved.data?.data ?? []), ...(rejected.data?.data ?? [])].filter((change) => change.calendarKey === calendarKey).sort((a, b) => b.proposedAt.localeCompare(a.proposedAt));
  if (items.length === 0) return <div className="card__pad"><EmptyState icon="processes" text={t('calendars.noHistory')} /></div>;
  return (
    <ul className="rows" aria-label={t('calendars.part.history')}>
      {items.map((change) => (
        <li key={change.id} className="row row--link">
          <div className="row__text">
            <a className="row__main" href={formatRoute({ name: 'calendar-change', changeId: change.id })}>{change.reason}</a>
            <ChangeState change={change} />
          </div>
          <span className="row__chevron" aria-hidden="true">›</span>
        </li>
      ))}
    </ul>
  );
}
