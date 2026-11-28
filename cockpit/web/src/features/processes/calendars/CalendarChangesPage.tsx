import { useId, useState } from 'react';
import { toHostError } from '../../../api/hostError';
import type { CalendarChange } from '../../../api/processes/schemas';
import { formatRoute } from '../../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import { useProcessesSession } from '../api/processesContext';
import { useApprovalHash } from './approvalHash';
import { calendarName, zoneLabel } from './calendarNames';
import { ChangeState } from './ChangeState';
import { useCalendarChange, useCalendarChanges, useDecideCalendarChange } from './useCalendarChanges';

/**
 * The business calendars' changes waiting for a second person: a list under Processes (#473) and, for one change, the
 * review where it is read and decided. The engine decides who may approve; the screen disables what it would refuse and
 * says why in one line.
 */
export function CalendarChangesPage({ changeId, pageLimit }: { changeId: string | undefined; pageLimit: number }) {
  return changeId === undefined ? <ChangeList pageLimit={pageLimit} /> : <ChangeReview changeId={changeId} />;
}

function ChangeList({ pageLimit }: { pageLimit: number }) {
  const { t } = useI18n();
  const list = useCalendarChanges(pageLimit);
  return (
    <section className="workspace" aria-labelledby="calendar-title">
      <PageHead id="calendar-title" title={t('calendar.title')} subtitle={t('calendar.subtitle')} />
      <article className="card">
        {list.isPending ? <LoadingRows rows={3} /> : list.isError ? <AreaError error={list.error} onRetry={() => { void list.refetch(); }} /> : list.data.data.length === 0 ? (
          <EmptyState icon="processes" text={t('calendar.empty')} action={{ label: t('menu.processes'), href: formatRoute({ name: 'processes' }) }} />
        ) : (
          <ul className="rows">
            {list.data.data.map((change) => (
              <li key={change.id} className="row row--link">
                <div className="row__text">
                  <a className="row__main" href={formatRoute({ name: 'calendar-change', changeId: change.id })}>{calendarName(change.calendarKey)}</a>
                  <ChangeState change={change} />
                </div>
                <span className="row__chevron" aria-hidden="true">›</span>
              </li>
            ))}
          </ul>
        )}
      </article>
    </section>
  );
}

function ChangeReview({ changeId }: { changeId: string }) {
  const { t } = useI18n();
  const change = useCalendarChange(changeId);
  if (change.isPending) return <section className="workspace" aria-busy="true"><LoadingRows rows={5} /></section>;
  if (change.isError) {
    const host = toHostError(change.error);
    const missing = host.kind === 'problem' && host.code === 'CALENDAR_NOT_FOUND';
    return (
      <section className="workspace" aria-labelledby="calendar-title">
        <PageHead id="calendar-title" title={t('calendar.title')} subtitle={t('calendar.subtitle')} />
        <article className="card">
          {missing ? <EmptyState icon="processes" text={t('calendar.missing')} action={{ label: t('calendar.title'), href: formatRoute({ name: 'calendar-changes' }) }} />
            : <AreaError error={change.error} onRetry={() => { void change.refetch(); }} />}
        </article>
      </section>
    );
  }
  return <Review change={change.data} />;
}

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'] as const;

function Review({ change }: { change: CalendarChange }) {
  const { t } = useI18n();
  const { userId, can } = useProcessesSession();
  const commentId = useId();
  const [comment, setComment] = useState('');
  // The hash of the change as first shown: Approve names it, so a change that moved on is refused, never approved blind.
  const hash = useApprovalHash(change);
  const decide = useDecideCalendarChange(change, hash);
  const pending = change.status === 'PENDING';
  const mine = change.proposedBy === userId;
  const mayDecide = can('processes:calendar:manage');
  const reason = !mayDecide ? t('approvals.denied.PERMISSION_REQUIRED') : mine ? t('approvals.denied.SELF_APPROVAL_FORBIDDEN') : undefined;
  const problem = decide.isError ? toHostError(decide.error) : undefined;
  const reasonId = useId();
  return (
    <section className="workspace" aria-labelledby="calendar-title">
      <PageHead id="calendar-title" title={t('calendar.reviewTitle', { key: calendarName(change.calendarKey) })} subtitle={t('calendar.subtitle')} />
      <article className="card" aria-label={t('calendar.reviewTitle', { key: calendarName(change.calendarKey) })}>
        <p className="calendar-review__state" role="status"><ChangeState change={change} /></p>
        <dl className="facts-list">
          <div><dt>{t('calendar.reason')}</dt><dd>{change.reason}</dd></div>
          <div><dt>{t('calendar.zone')}</dt><dd>{zoneLabel(change.proposed.zone, t)}</dd></div>
          <div><dt>{t('calendar.hours')}</dt><dd>
            <ul className="plain-list">
              {DAYS.filter((day) => (change.proposed.week?.[day] ?? []).length > 0).map((day) => <li key={day} className="tnum">{t(`calendar.day.${day}`)} {(change.proposed.week?.[day] ?? []).join(', ')}</li>)}
            </ul>
          </dd></div>
          <div><dt>{t('calendar.holidays')}</dt><dd>
            {Object.keys(change.proposed.holidays ?? {}).length === 0 ? t('calendar.noHolidays') : (
              <ul className="plain-list">
                {Object.entries(change.proposed.holidays ?? {}).map(([date, name]) => <li key={date} className="tnum">{date} · {name}</li>)}
              </ul>
            )}
          </dd></div>
        </dl>
        <p className="muted">{t('calendar.effect')}</p>
      </article>
      {pending ? (
        <article className="card">
          <label htmlFor={commentId} className="field__label">{t('calendar.comment')}</label>
          <textarea id={commentId} className="field__input" rows={2} maxLength={500} value={comment} onChange={(event) => { setComment(event.target.value); }} />
          {problem !== undefined ? <p className="field__error" role="alert">{problem.kind === 'problem' && problem.status === 409 ? t('calendar.conflict') : t('area.error.generic')}</p> : null}
          {reason !== undefined && !(mine && mayDecide) ? <p className="reason-line" id={reasonId}><span aria-hidden="true">⊘</span> {reason}</p> : null}
          {mine && mayDecide ? <p className="reason-line" id={reasonId}><span aria-hidden="true">⊘</span> {reason}</p> : null}
          <div className="dialog__actions">
            <button type="button" className="btn btn--danger" disabled={!mayDecide || comment.trim() === '' || decide.isPending}
              onClick={() => { decide.mutate({ approve: false, comment: comment.trim() }); }}>{t('calendar.reject')}</button>
            <button type="button" className="btn btn--primary" disabled={reason !== undefined || decide.isPending} aria-busy={decide.isPending}
              {...(reason !== undefined ? { 'aria-describedby': reasonId } : {})}
              onClick={() => { decide.mutate({ approve: true, comment: comment.trim() }); }}>{decide.isPending ? t('calendar.deciding') : t('calendar.approve')}</button>
          </div>
        </article>
      ) : null}
    </section>
  );
}
