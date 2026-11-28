import { useId, useState, type FormEvent } from 'react';
import { usePersonName } from '../../../app/people';
import { formatRoute } from '../../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import { formatDateTime } from '../../rules/model/dates';
import { useProcessesSession } from '../api/processesContext';
import { CalendarEditor } from './CalendarEditor';
import { useCalendars } from './useCalendarChanges';

/** A calendar key as the engine writes it; a key that is not one is never requested. */
const KEY = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

/**
 * The tenant's business calendars: a list under Processes, and for one key the editor. A holder of
 * `processes:calendar:manage` proposes a change; a second holder approves it before it applies (PD-131).
 */
export function CalendarsPage({ calendarKey, pageLimit }: { calendarKey: string | undefined; pageLimit: number }) {
  return calendarKey === undefined ? <CalendarList pageLimit={pageLimit} /> : <CalendarEditor calendarKey={calendarKey} pageLimit={pageLimit} />;
}

function CalendarList({ pageLimit }: { pageLimit: number }) {
  const { t, locale } = useI18n();
  const { can } = useProcessesSession();
  const person = usePersonName();
  const list = useCalendars(pageLimit);
  return (
    <section className="workspace" aria-labelledby="calendars-title">
      <PageHead id="calendars-title" title={t('calendars.title')} subtitle={t('calendars.subtitle')}
        action={<a className="btn" href={formatRoute({ name: 'calendar-changes' })}>{t('calendars.waiting')}</a>} />
      <article className="card">
        {list.isPending ? <LoadingRows rows={3} /> : list.isError ? <AreaError error={list.error} onRetry={() => { void list.refetch(); }} /> : list.data.data.length === 0 ? (
          <div className="card__pad"><EmptyState icon="processes" text={t('calendars.empty')} /></div>
        ) : (
          <table className="qtable qtable--links calendars__table" aria-label={t('calendars.label')}>
            <thead>
              <tr>
                <th scope="col">{t('calendars.col.key')}</th>
                <th scope="col">{t('calendar.zone')}</th>
                <th scope="col" className="num">{t('calendars.col.version')}</th>
                <th scope="col">{t('calendars.col.changed')}</th>
                <th scope="col"><span className="sr-only">{t('processes.col.open')}</span></th>
              </tr>
            </thead>
            <tbody>
              {list.data.data.map((item) => (
                <tr key={item.key}>
                  <th scope="row"><a className="qtable__link" href={formatRoute({ name: 'calendar', calendarKey: item.key })}>{item.key}</a></th>
                  <td>{item.zone}</td>
                  <td className="num tnum">{item.version}</td>
                  <td className="tnum muted">{t('calendars.changedBy', { date: formatDateTime(item.updatedAt, locale) ?? '', who: person(item.updatedBy), approver: person(item.approvedBy) })}</td>
                  <td className="qtable__chevron" aria-hidden="true">›</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </article>
      {can('processes:calendar:manage') ? <NewCalendar /> : null}
    </section>
  );
}

/** A new calendar is a first change against version 0: the key is entered here, the hours in the editor. */
function NewCalendar() {
  const { t } = useI18n();
  const id = useId();
  const [key, setKey] = useState('');
  const valid = KEY.test(key) && key.length <= 100;
  const go = (event: FormEvent) => {
    event.preventDefault();
    if (valid) window.location.hash = formatRoute({ name: 'calendar', calendarKey: key });
  };
  return (
    <form className="card card--pad calendar-new" onSubmit={go}>
      <label htmlFor={id} className="field__label">{t('calendars.newKey')}</label>
      <div className="calendar-new__row">
        <input id={id} className="field__input" value={key} maxLength={100} autoComplete="off" spellCheck={false} aria-describedby={`${id}-hint`}
          onChange={(event) => { setKey(event.target.value.trim()); }} />
        <button type="submit" className="btn btn--secondary" disabled={!valid}>{t('calendars.new')}</button>
      </div>
      <p id={`${id}-hint`} className="muted">{t('calendars.newHint')}</p>
    </form>
  );
}
