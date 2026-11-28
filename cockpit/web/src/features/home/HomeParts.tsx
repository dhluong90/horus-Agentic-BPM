import type { ReactNode } from 'react';
import type { TaskSummary } from '../../api/processes/schemas';
import { usePersonName } from '../../app/people';
import { formatRoute } from '../../app/route';
import { AreaIcon, ClockIcon } from '../../components/icons';
import { AreaError, EmptyState, LoadingRows } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { PendingList } from '../approvals/PendingList';
import { reviewRoute, type ApprovalItem } from '../approvals/queue';
import type { Loadable, OwnWork, useEveryRuleSet } from '../overview/useRuleOverview';
import { RuleSetTable } from '../rules/RuleSetTable';
import { slaOf } from '../tasks/model/queue';
import { taskTitle, useOverdueText } from '../tasks/parts';
import { chartHours, hourLabel, initials, isToday, peakHour, shortWhen } from './model';
import type { HourlyDecisions } from './useDecisionsToday';

/** Rows of a Home list; the full list is one click away. */
export const ROWS = 4;

export type Tone = 'on-track' | 'act-now' | 'urgent' | 'waiting';
export type TileIcon = 'tasks' | 'clock' | 'incidents' | 'approvals';

export interface TileModel {
  id: string;
  tone: Tone;
  icon: TileIcon;
  /** Undefined while the engine has not answered. */
  value: string | undefined;
  label: string;
  status: string;
  note: string;
  href: string;
}

/** One key figure: a number, a plain label, one status word and a short note; the tile opens the list behind it. */
export function Tile({ tile }: { tile: TileModel }) {
  return (
    <a className={`tile tile--${tile.tone}`} href={tile.href} data-tile={tile.id}>
      <span className="tile__icon" aria-hidden="true">{tile.icon === 'clock' ? <ClockIcon /> : <AreaIcon area={tile.icon} />}</span>
      <span className="tile__status">{tile.status}</span>
      <span className="tile__value tnum">{tile.value ?? '–'}</span>
      <span className="tile__label">{tile.label}</span>
      <span className="tile__note">{tile.note}</span>
    </a>
  );
}

function Avatar({ text, tone = 'mint' }: { text: string; tone?: 'mint' | 'amber' }) {
  return <span className={`avatar avatar--${tone}`} aria-hidden="true">{initials(text)}</span>;
}

/** The user's own tasks with their due state; "Open" goes to the task in the queue. */
export function TasksCard({ tasks, now, onRetry }: { tasks: Loadable<TaskSummary[]>; now: Date; onRetry: () => void }) {
  const { t, locale } = useI18n();
  const overdueText = useOverdueText();
  return (
    <article className="card card--list home__tasks" aria-labelledby="home-tasks">
      <div className="card__head">
        <h2 id="home-tasks" className="card__title">{t('home.tasks.title')}</h2>
        <a className="btn btn--quiet btn--small hit" href={formatRoute({ name: 'tasks', view: 'mine' })}>{t('home.viewAll')} <span aria-hidden="true">→</span></a>
      </div>
      {tasks.status === 'loading' ? <LoadingRows rows={3} /> : tasks.status === 'error' ? <AreaError error={tasks.error} onRetry={onRetry} /> : tasks.data.length === 0 ? (
        <EmptyState icon="tasks" text={t('tasks.empty.mine')} />
      ) : (
        <ul className="rows">
          {tasks.data.slice(0, ROWS).map((task) => {
            const title = taskTitle(task, t);
            const late = slaOf(task, undefined) === 'BREACHED';
            const lateText = overdueText(task.overdueBy);
            return (
              <li key={task.id} className="hrow">
                <Avatar text={title} tone={late ? 'amber' : 'mint'} />
                <div className="hrow__text">
                  <span className="hrow__main">{title}</span>
                  {task.businessKey ? <span className="hrow__meta tnum">{t('tasks.case', { key: task.businessKey })}</span> : null}
                </div>
                {late ? (
                  <span className="status status--warn"><span aria-hidden="true">▲</span> {lateText !== undefined ? t('tasks.sla.overdueBy', { time: lateText }) : t('tasks.sla.overdue')}</span>
                ) : task.dueAt != null ? (
                  <span className={isToday(task.dueAt, now) ? 'status status--ok' : 'status status--muted'}>
                    <span aria-hidden="true">◷</span> {isToday(task.dueAt, now) ? t('home.due.today', { time: shortWhen(task.dueAt, now, locale) }) : shortWhen(task.dueAt, now, locale)}
                  </span>
                ) : null}
                <a className="btn btn--soft btn--small hit" href={formatRoute({ name: 'tasks', view: 'mine', taskId: task.id })} aria-label={t('home.openTask', { title })}>{t('home.open')}</a>
              </li>
            );
          })}
        </ul>
      )}
    </article>
  );
}

/**
 * Requests this user may approve. Approve and Reject both open the review: the decision is taken there, with the
 * reviewed hash, never from Home.
 */
export function ApprovalsCard({ items, now, onRetry }: { items: Loadable<ApprovalItem[]>; now: Date; onRetry: () => void }) {
  const { t, locale } = useI18n();
  const personName = usePersonName();
  return (
    <article className="card card--list home__approvals" aria-labelledby="home-approvals">
      <div className="card__head">
        <h2 id="home-approvals" className="card__title">{t('home.approvals.title')}</h2>
        {items.status === 'ready' && items.data.length > 0 ? <span className="count tnum">{t('home.approvals.count', { count: String(items.data.length) })}</span> : null}
      </div>
      {items.status === 'loading' ? <LoadingRows rows={3} /> : items.status === 'error' ? <AreaError error={items.error} onRetry={onRetry} /> : items.data.length === 0 ? (
        <EmptyState icon="approvals" text={t('home.noReviews')} action={{ label: t('menu.approvals'), href: formatRoute({ name: 'approvals' }) }} />
      ) : (
        <>
          <ul className="rows">
            {items.data.slice(0, ROWS).map((item) => {
              const by = personName(item.requestedBy, item.requesterName);
              const title = t('rules.nameVersion', { name: item.title, version: String(item.version) });
              const href = formatRoute(reviewRoute(item));
              return (
                <li key={`${item.engine}/${item.key}/${String(item.version)}`} className="hrow hrow--stack">
                  <Avatar text={by} />
                  <div className="hrow__text">
                    <span className="hrow__main">{title}</span>
                    <span className="hrow__meta tnum">{by} · {shortWhen(item.requestedAt, now, locale)}</span>
                    <span className="hrow__actions">
                      <a className="btn btn--primary btn--small hit" href={href} aria-label={t('home.approve', { title })}><span aria-hidden="true">✓</span> {t('approvals.approve')}</a>
                      <a className="btn btn--quiet btn--small hit" href={href} aria-label={t('home.reject', { title })}><span aria-hidden="true">✕</span> {t('approval.reject')}</a>
                    </span>
                  </div>
                </li>
              );
            })}
          </ul>
          {items.data.length > ROWS ? <a className="link-action home__all" href={formatRoute({ name: 'approvals' })}>{t('home.allApprovals')} <span aria-hidden="true">→</span></a> : null}
        </>
      )}
    </article>
  );
}

const BAR = { width: 46, height: 84, radius: 6 } as const;

/** One bar as an SVG path with a rounded top, `share` of the full height (a style attribute is not allowed by the CSP). */
function BarShape({ share }: { share: number }) {
  const high = Math.max(BAR.radius + 1, Math.round(share * BAR.height));
  const top = BAR.height - high;
  const { width, radius } = BAR;
  return (
    <svg className="bars__track" viewBox={`0 0 ${String(width)} ${String(BAR.height)}`} aria-hidden="true" focusable="false">
      <path className="bars__bar" d={`M0 ${String(BAR.height)}V${String(top + radius)}Q0 ${String(top)} ${String(radius)} ${String(top)}H${String(width - radius)}Q${String(width)} ${String(top)} ${String(width)} ${String(top + radius)}V${String(BAR.height)}Z`} />
    </svg>
  );
}

/** Today's decisions by hour: every bar carries its value, the peak is the dark one. */
export function DecisionsCard({ hourly }: { hourly: HourlyDecisions }) {
  const { t, locale } = useI18n();
  if (hourly.status === 'unavailable') return null;
  const counts = hourly.status === 'ready' ? hourly.counts : [];
  const peak = peakHour(counts);
  const hours = chartHours(counts);
  const top = Math.max(1, ...counts);
  return (
    <article className="card home__chart" aria-labelledby="home-chart">
      <div className="card__head home__chart-head">
        <div>
          <h2 id="home-chart" className="card__title">{t('home.chart.title')}</h2>
          <p className="card__sub">{t('home.chart.sub')}</p>
        </div>
        {peak !== undefined ? <span className="count tnum">{t('home.chart.peak', { hour: hourLabel(peak, locale), count: String(counts[peak] ?? 0) })}</span> : null}
      </div>
      {hourly.status === 'loading' ? <LoadingRows rows={2} /> : peak === undefined ? <EmptyState icon="decisions" text={t('home.chart.empty')} /> : (
        <ul className="bars">
          {hours.map((hour) => {
            const count = counts[hour] ?? 0;
            return (
              <li key={hour} className={hour === peak ? 'bars__item bars__item--peak' : 'bars__item'} aria-label={t('home.chart.bar', { hour: hourLabel(hour, locale), count: String(count) })}>
                <span className="bars__value tnum" aria-hidden="true">{count}</span>
                <BarShape share={count / top} />
                <span className="bars__hour" aria-hidden="true">{hourLabel(hour, locale)}</span>
              </li>
            );
          })}
        </ul>
      )}
    </article>
  );
}

/** The author's own drafts and requests, below the key figures. */
export function AuthorCard({ mine, onRetry }: { mine: Loadable<OwnWork[]>; onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <article className="card card--list" aria-labelledby="home-pending">
      <div className="card__head"><h2 id="home-pending" className="card__title">{t('home.yourDraftsAndRequests')}</h2></div>
      {mine.status === 'loading' ? <LoadingRows rows={2} /> : mine.status === 'error' ? <AreaError error={mine.error} onRetry={onRetry} /> : mine.data.length === 0 ? (
        <EmptyState icon="approvals" text={t('home.noOwnWork')} action={{ label: t('home.openRules'), href: formatRoute({ name: 'rule-sets' }) }} />
      ) : <PendingList items={mine.data.slice(0, ROWS)} />}
    </article>
  );
}

export function RecentCard({ ruleSets }: { ruleSets: ReturnType<typeof useEveryRuleSet> }): ReactNode {
  const { t } = useI18n();
  return (
    <article className="card card--table" aria-labelledby="home-recent">
      <div className="card__head card__head--padded">
        <h2 id="home-recent" className="card__title">{t('home.recentRuleSets')}</h2>
        <a className="link-action" href={formatRoute({ name: 'rule-sets' })}>{t('home.allRuleSets')} <span aria-hidden="true">→</span></a>
      </div>
      <RuleSetTable ruleSets={ruleSets} limit={5} recentFirst compact emptyText={t('rulesArea.empty')} />
    </article>
  );
}
