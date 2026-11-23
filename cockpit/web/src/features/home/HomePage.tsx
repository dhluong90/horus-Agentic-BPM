import { useState, type ReactNode } from 'react';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { TaskSummary } from '../../api/processes/schemas';
import { formatRoute, type Area } from '../../app/route';
import { EmptyState } from '../../components/PageParts';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { type ApprovalItem, type Engine } from '../approvals/queue';
import { useApprovalQueue, type QueueState } from '../approvals/useApprovalQueue';
import { useIncidentList } from '../incidents/api/useIncidents';
import { useRetryRules } from '../overview/useRetryRules';
import { mapLoadable, ownWork, useEveryRuleSet, useOpenVersions, type Loadable } from '../overview/useRuleOverview';
import { useProcessesSession } from '../processes/api/processesContext';
import { useRulesSession } from '../rules/api/rulesContext';
import { useQueue } from '../tasks/api/useTasks';
import { WORK } from '../tasks/model/queue';
import { useOverdueText } from '../tasks/parts';
import { ApprovalsCard, AuthorCard, DecisionsCard, RecentCard, Tile, TasksCard, type TileModel } from './HomeParts';
import { dueToday, longestOverdue } from './model';
import { useDecisionsToday } from './useDecisionsToday';
import '../../components/home.css';

/** How often the task and incident figures are read again when the host gives no period (seconds). */
const REFRESH = { tasks: 30, incidents: 60 } as const;

export interface HomeRefresh { inboxSeconds?: number | undefined; incidentsSeconds?: number | undefined }

/**
 * Home, the landing page for every role (PD-092). What it shows follows the host's modules and what /me allows: key
 * figures for tasks, incidents and approvals, the user's tasks, the requests they may approve, and today's decisions.
 * A figure whose API the host lacks is left out, never invented. Every number comes from the engines.
 */
export function HomePage({ pageLimit, me, areas, engines, refresh }: { pageLimit: number; me: BpmMeV1; areas: readonly Area[]; engines: readonly Engine[]; refresh?: HomeRefresh }) {
  const { t } = useI18n();
  const [now] = useState(() => new Date());
  const rules = areas.includes('rules');
  const approver = mayApprove(me.permissions, engines);
  const { can } = useProcessesSession();
  const tasksOn = areas.includes('tasks') && can(WORK);
  const incidentsOn = areas.includes('incidents');
  const name = me.user.displayName ?? me.user.id;
  const headline = <h1 id="home-title">{t(greeting(now.getHours()), { name })}</h1>;
  return (
    <section className="workspace" aria-labelledby="home-title">
      {rules || approver || tasksOn || incidentsOn ? (
        <Dashboard pageLimit={pageLimit} me={me} engines={engines} rules={rules} approver={approver} tasksOn={tasksOn} incidentsOn={incidentsOn}
          decisionsOn={areas.includes('decisions')} now={now} refresh={refresh} title={headline} />
      ) : (
        <>
          <div className="page-head">
            {headline}
            <p className="page-head__sub">{t(subtitle(me.permissions, rules, approver))}</p>
          </div>
          <article className="card"><EmptyState icon="home" text={t('home.nothingForRole')} /></article>
        </>
      )}
    </section>
  );
}

/** The approve permission of an engine whose queue this user reads. */
function mayApprove(permissions: readonly string[], engines: readonly Engine[]): boolean {
  return (engines.includes('rules') && permissions.includes('rules:approve')) || (engines.includes('processes') && permissions.includes('processes:approve'));
}

function greeting(hour: number): MessageKey {
  if (hour < 12) return 'home.greeting.morning';
  if (hour < 18) return 'home.greeting.afternoon';
  return 'home.greeting.evening';
}

function subtitle(permissions: readonly string[], rules: boolean, approver: boolean): MessageKey {
  const author = rules && permissions.includes('rules:author');
  if (author && approver) return 'home.subtitle.both';
  if (author) return 'home.subtitle.author';
  if (approver) return 'home.subtitle.approver';
  return rules ? 'home.subtitle.reader' : 'home.subtitle.none';
}

/** The queue's requests this user may approve now, as one async value. A queue they may not read holds none. */
function approvable(state: QueueState): Loadable<ApprovalItem[]> {
  switch (state.status) {
    case 'loading':
      return { status: 'loading' };
    case 'error':
      return { status: 'error', error: state.error };
    case 'forbidden':
      return { status: 'ready', data: [] };
    case 'ready':
      return { status: 'ready', data: state.items.filter((item) => item.canApprove) };
  }
}

interface TaskList { tasks: TaskSummary[]; more: boolean }

/** A task list query as one async value; a list that failed is a failure, never a zero. Undefined when it is off. */
function loaded(query: { isError: boolean; error: unknown; data: { data: TaskSummary[]; hasNext: boolean } | undefined }, enabled: boolean): Loadable<TaskList> | undefined {
  if (!enabled) return undefined;
  if (query.isError) return { status: 'error', error: query.error };
  if (query.data === undefined) return { status: 'loading' };
  return { status: 'ready', data: { tasks: query.data.data, more: query.data.hasNext } };
}

function Dashboard({ pageLimit, me, engines, rules, approver, tasksOn, incidentsOn, decisionsOn, now, refresh, title }: {
  pageLimit: number; me: BpmMeV1; engines: readonly Engine[]; rules: boolean; approver: boolean; tasksOn: boolean; incidentsOn: boolean; decisionsOn: boolean;
  now: Date; refresh: HomeRefresh | undefined; title: ReactNode;
}) {
  const { t } = useI18n();
  const { userId } = useRulesSession();
  const overdueText = useOverdueText();
  const taskSeconds = refresh?.inboxSeconds ?? REFRESH.tasks;
  const mineQuery = useQueue('mine', tasksOn, taskSeconds);
  const lateQuery = useQueue('mine', tasksOn, taskSeconds, true);
  const incidentQuery = useIncidentList('OPEN', refresh?.incidentsSeconds ?? REFRESH.incidents, incidentsOn);
  const queue = useApprovalQueue(engines, pageLimit);
  const retryRules = useRetryRules();
  const ruleSets = useEveryRuleSet(pageLimit, rules);
  const author = rules && me.permissions.includes('rules:author');
  const versions = useOpenVersions(ruleSets);
  const myWork = mapLoadable(versions, (data) => ownWork(data, userId));
  const hourly = useDecisionsToday(pageLimit, decisionsOn && me.permissions.includes('rules:log:read'), now);

  const mine = loaded(mineQuery, tasksOn);
  const late = loaded(lateQuery, tasksOn);
  const incidents = incidentsOn && !incidentQuery.isError ? incidentQuery.data : undefined;
  const toReview = approvable(queue.state);
  const count = (more: boolean, n: number) => (more ? t('tasks.more', { count: String(n) }) : String(n));

  const dueNow = mine?.status === 'ready' ? dueToday(mine.data.tasks, now).length : undefined;
  const lateNow = late?.status === 'ready' ? late.data.tasks.length : undefined;
  const tiles: TileModel[] = [];
  if (tasksOn && mine?.status !== 'error') {
    tiles.push({
      id: 'due', tone: 'on-track', icon: 'tasks', value: dueNow === undefined ? undefined : String(dueNow), label: t('home.tile.due'),
      status: t('home.status.onTrack'), note: t('home.tile.dueNote'), href: formatRoute({ name: 'tasks', view: 'mine' }),
    });
  }
  if (tasksOn && late?.status !== 'error') {
    const longest = late?.status === 'ready' ? overdueText(longestOverdue(late.data.tasks)) : undefined;
    tiles.push({
      id: 'overdue', tone: lateNow === undefined || lateNow === 0 ? 'on-track' : 'act-now', icon: 'clock',
      value: late?.status === 'ready' ? count(late.data.more, late.data.tasks.length) : undefined, label: t('home.tile.overdue'),
      status: lateNow === 0 ? t('home.status.none') : t('home.status.actNow'),
      note: lateNow === 0 ? t('home.tile.overdueNone') : longest !== undefined ? t('home.tile.overdueLongest', { time: longest }) : t('home.tile.overdueNote'),
      href: formatRoute({ name: 'tasks', view: 'mine', overdue: true }),
    });
  }
  if (incidentsOn && !incidentQuery.isError) {
    const open = incidents?.data.length;
    tiles.push({
      id: 'incidents', tone: open === 0 ? 'on-track' : 'urgent', icon: 'incidents', value: incidents === undefined ? undefined : count(incidents.hasNext, incidents.data.length), label: t('home.tile.incidents'),
      status: open === 0 ? t('home.status.clear') : t('home.status.urgent'), note: open === 0 ? t('home.tile.incidentsNone') : t('home.tile.incidentsNote'),
      href: formatRoute({ name: 'incidents', view: 'open' }),
    });
  }
  if (approver && toReview.status !== 'error') {
    const waiting = toReview.status === 'ready' ? toReview.data.length : undefined;
    tiles.push({
      id: 'approvals', tone: 'waiting', icon: 'approvals', value: waiting === undefined ? undefined : String(waiting), label: t('home.tile.approvals'),
      status: waiting === 0 ? t('home.status.clear') : t('home.status.waiting'), note: t('home.tile.approvalsNote'), href: formatRoute({ name: 'approvals' }),
    });
  }

  const attention = (dueNow ?? 0) + (lateNow ?? 0) + (toReview.status === 'ready' ? toReview.data.length : 0);
  const sub = attention > 0 ? t('home.subtitle.attention', { count: String(attention) }) : t(subtitle(me.permissions, rules, approver));
  const tasksCard = tasksOn && mine !== undefined;
  const chart = hourly.status !== 'unavailable';
  return (
    <div className="home">
      <div className="page-head home__head">
        {title}
        <p className="page-head__sub">{sub}</p>
      </div>
      {tiles.length > 0 ? <div className="tiles">{tiles.map((tile) => <Tile key={tile.id} tile={tile} />)}</div> : null}
      {tasksCard || approver || chart ? (
        <div className={approver ? 'home__grid' : 'home__grid home__grid--wide'}>
          {tasksCard ? <TasksCard tasks={mapLoadable(mine, (data) => data.tasks)} now={now} onRetry={() => { void mineQuery.refetch(); }} /> : null}
          {chart ? <DecisionsCard hourly={hourly} /> : null}
          {approver ? <ApprovalsCard items={toReview} now={now} onRetry={queue.retry} /> : null}
        </div>
      ) : null}
      {rules ? (
        <div className={author ? 'home__more' : 'home__more home__more--single'}>
          {author ? <AuthorCard mine={myWork} onRetry={retryRules} /> : null}
          <RecentCard ruleSets={ruleSets} />
        </div>
      ) : null}
    </div>
  );
}

