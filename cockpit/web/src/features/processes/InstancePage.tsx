import { lazy, Suspense, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { InstanceActivities, ProcessInstance } from '../../api/processes/schemas';
import { toHostError } from '../../api/hostError';
import { setCrumbTrail } from '../../app/crumbs';
import { useNames, useWantNames, wantedOf } from './api/displayNames';
import { formatRoute } from '../../app/route';
import { CopyButton } from '../../components/CopyButton';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { Tabs } from '../rules/components/Tabs';
import { formatDateTime } from '../rules/model/dates';
import { useProcessesSession } from './api/processesContext';
import { useDefinitionGraph, useDefinitionVersion, useInstance, useInstanceActivities } from './api/useProcesses';
import type { DiagramOverlay, StepBadge } from './diagram/DefinitionDiagram';
import { InstanceStatusPill } from './InstanceStatusPill';
import { flowOrder, type DiagramGraph, type DiagramNode } from './model/bpmnGraph';
import {
  activityOf,
  actorReplica,
  durationParts,
  entryKey,
  firstStep,
  incidentKey,
  isProblemEntry,
  nodeMarks,
  openTaskAt,
  TOKEN_ICON,
  TOKEN_KEY,
  type NodeMark,
} from './model/instanceView';
import { nodeTypeLabel } from './model/nodeLabels';

/*
 * One case on its diagram (CKP-017): where its tokens wait, which step holds an incident, and what happened at each
 * step, from the engine's getInstance and getInstanceActivities (PRE-027). The step the case is stuck on opens
 * first in the inspector beside the diagram; the Timeline view lists the same entries in time order. Status is text
 * and icon on every badge, never colour alone. No variable or input value is shown.
 */

const DefinitionDiagram = lazy(() => import('./diagram/DefinitionDiagram'));

type Lens = 'diagram' | 'timeline';
type T = (key: MessageKey, params?: Record<string, string>) => string;

export function InstancePage({ instanceId }: { instanceId: string }) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  if (!can('processes:read')) {
    return (
      <section className="workspace" aria-labelledby="instance-title">
        <PageHead id="instance-title" title={t('instance.crumb')} subtitle={t('instance.subtitlePlain')} />
        <article className="card"><div className="card__pad"><EmptyState icon="processes" text={t('instance.noRead')} /></div></article>
      </section>
    );
  }
  return <InstanceScreen instanceId={instanceId} />;
}

function InstanceScreen({ instanceId }: { instanceId: string }) {
  const { t } = useI18n();
  const instance = useInstance(instanceId, true);
  useEffect(() => () => { setCrumbTrail(undefined); }, []);
  if (instance.isPending) return <section className="workspace" aria-busy="true"><LoadingRows rows={6} /></section>;
  if (instance.isError) {
    const host = toHostError(instance.error);
    const missing = host.kind === 'problem' && host.code === 'INSTANCE_NOT_FOUND';
    return (
      <section className="workspace" aria-labelledby="instance-title">
        <PageHead id="instance-title" title={t('instance.crumb')} subtitle={t('instance.subtitlePlain')} />
        <article className="card">
          {missing ? (
            <div className="card__pad"><EmptyState icon="processes" text={t('instance.notFound')} action={{ label: t('instance.backToProcesses'), href: formatRoute({ name: 'processes' }) }} /></div>
          ) : <AreaError error={instance.error} onRetry={() => { void instance.refetch(); }} />}
        </article>
      </section>
    );
  }
  return <InstanceLoaded instance={instance.data} />;
}

function InstanceLoaded({ instance }: { instance: ProcessInstance }) {
  const { t } = useI18n();
  const details = useDefinitionVersion(instance.definitionKey, instance.definitionVersion);
  const graph = useDefinitionGraph(instance.definitionKey, instance.definitionVersion);
  const activities = useInstanceActivities(instance.id, true);
  const [lens, setLens] = useState<Lens>('diagram');
  const processName = details.data?.name ?? undefined;
  const title = instance.businessKey ? t('instance.title', { key: instance.businessKey }) : t('instance.crumb');
  useEffect(() => {
    setCrumbTrail([
      ...(processName !== undefined ? [{ label: processName, href: formatRoute({ name: 'process', key: instance.definitionKey, version: instance.definitionVersion, lens: 'cases' }) }] : []),
      { label: title },
    ]);
  }, [processName, title, instance.definitionKey, instance.definitionVersion]);
  const subtitle = processName !== undefined ? t('instance.subtitle', { process: processName, version: String(instance.definitionVersion) }) : t('instance.subtitlePlain');
  return (
    <section className="workspace instance" aria-labelledby="instance-title">
      <PageHead id="instance-title" title={title} subtitle={subtitle} action={<InstanceStatusPill status={instance.status} />} />
      <article className="card card--diagram">
        {graph.isPending ? <LoadingRows rows={6} /> : graph.isError ? (
          <AreaError error={graph.error} onRetry={() => { void graph.refetch(); }} />
        ) : (
          <Tabs label={t('instance.lenses')} selected={lens} onSelect={setLens}
            items={[{ id: 'diagram', label: t('instance.lens.diagram') }, { id: 'timeline', label: t('instance.lens.timeline') }]}>
            {lens === 'diagram' ? (
              <InstanceDiagram instance={instance} graph={graph.data} activities={activities.data} label={t('instance.diagramLabel', { name: title })}
                load={{ pending: activities.isPending, error: activities.isError ? activities.error : undefined, retry: () => { void activities.refetch(); } }} />
            ) : (
              activities.isPending ? <LoadingRows rows={6} /> : activities.isError ? <ActivitiesError error={activities.error} onRetry={() => { void activities.refetch(); }} /> : <Timeline graph={graph.data} activities={activities.data} />
            )}
          </Tabs>
        )}
      </article>
    </section>
  );
}

/** A missing activities endpoint is an older engine: the diagram still shows where the case is. */
function ActivitiesError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const { t } = useI18n();
  const host = toHostError(error);
  const status = host.kind === 'problem' || host.kind === 'unexpected-response' ? host.status : undefined;
  if (status === 404 || status === 405) return <EmptyState text={t('instance.noActivities')} />;
  return <AreaError error={error} onRetry={onRetry} />;
}

function badgeOf(mark: NodeMark, t: T): StepBadge {
  switch (mark.kind) {
    case 'incident':
      return { tone: 'incident', icon: '✕', text: t('instance.badge.incident'), label: t('instance.badge.incidentLabel', { what: t(incidentKey(mark.code)) }) };
    case 'token': {
      const words = t(TOKEN_KEY[mark.status]);
      const text = mark.count > 1 ? t('instance.badge.tokens', { what: words, count: String(mark.count) }) : words;
      return { tone: 'token', icon: TOKEN_ICON[mark.status], text, label: text };
    }
    case 'done':
      return { tone: 'done', icon: '✓', text: '', label: t('instance.state.completed') };
  }
}

/** How the activities read is going, for the inspector. */
interface ActivitiesLoad {
  pending: boolean;
  error: unknown;
  retry: () => void;
}

function InstanceDiagram({ instance, graph, activities, load, label }: {
  instance: ProcessInstance;
  graph: DiagramGraph;
  activities: InstanceActivities | undefined;
  load: ActivitiesLoad;
  label: string;
}) {
  const { t } = useI18n();
  const marks = useMemo(() => nodeMarks(instance, activities), [instance, activities]);
  const order = useMemo(() => flowOrder(graph).map((node) => node.id), [graph]);
  const [picked, setPicked] = useState<string | undefined>(undefined);
  const selected = picked ?? firstStep(marks, order);
  const badges = useMemo(() => new Map([...marks].map(([id, mark]) => [id, badgeOf(mark, t)])), [marks, t]);
  const overlay: DiagramOverlay = { badges, selected, onSelect: setPicked };
  const node = graph.nodes.find((item) => item.id === selected);
  return (
    <div className="instance-grid">
      <div className="instance-grid__canvas">
        <Suspense fallback={<LoadingRows rows={6} />}>
          <DefinitionDiagram graph={graph} label={label} overlay={overlay} />
        </Suspense>
      </div>
      <aside className="inspector" aria-label={t('instance.inspector')} aria-live="polite">
        {node === undefined ? (
          <p className="inspector__hint">{t('instance.pick')}</p>
        ) : (
          <StepDetails instance={instance} node={node} mark={marks.get(node.id)} activities={activities} load={load} />
        )}
      </aside>
    </div>
  );
}

function durationText(ms: number, t: T): string {
  return durationParts(ms).map((part) => t(`instance.unit.${part.unit}`, { value: String(part.value) })).join(' ');
}

/** Who acted, in words: a person by name, the engine as "Process Engine"; an engine node's id stays under Details. */
function useActorText(): (actor: string | null) => string {
  const { t } = useI18n();
  const { user } = useNames();
  return (actor) => {
    const who = actorReplica(actor);
    return who.engine ? t('instance.engine') : user(who.login);
  };
}

/** The logins among these actors, for the display name lookup; an engine replica is not a person. */
const peopleOf = (actors: readonly (string | null | undefined)[]): string[] => [...new Set(actors.flatMap((actor) => {
  if (actor === null || actor === undefined) return [];
  const who = actorReplica(actor);
  return who.engine ? [] : [who.login];
}))];

function StepDetails({ instance, node, mark, activities, load }: {
  instance: ProcessInstance;
  node: DiagramNode;
  mark: NodeMark | undefined;
  activities: InstanceActivities | undefined;
  load: ActivitiesLoad;
}) {
  const { t, locale } = useI18n();
  const actorText = useActorText();
  const { assigneeName } = useNames();
  const activity = activityOf(activities, node.id);
  const task = openTaskAt(instance, node.id);
  useWantNames(wantedOf(task !== undefined ? [task.assignee] : [], peopleOf([activity?.actor, task?.claimedBy])));
  const name = node.name ?? node.id;
  const attempts = activity !== undefined ? t(activity.attempts === 1 ? 'instance.attempts.one' : 'instance.attempts.many', { count: String(activity.attempts) }) : undefined;
  const summary = (() => {
    if (activity === undefined) return undefined;
    if (activity.state === 'COMPLETED' && activity.durationMs !== null) {
      return t('instance.summary.completed', { attempts: attempts ?? '', duration: durationText(activity.durationMs, t), actor: actorText(activity.actor) });
    }
    return undefined;
  })();
  const who = activity !== undefined ? actorReplica(activity.actor) : undefined;
  const engineNode = who?.engine === true ? who.replica : undefined;
  return (
    <div className="inspector__body">
      <div className="inspector__col">
        <div className="inspector__head">
          <h2 className="card__title">{name}</h2>
          <p className={node.type === 'serviceTask:agentTask' ? 'inspector__type inspector__type--agent' : 'inspector__type'}>{nodeTypeLabel(node.type, t)}</p>
        </div>
        <StateLine mark={mark} activity={activity !== undefined} />
        {summary !== undefined ? <p className="inspector__summary">{summary}</p> : null}
      </div>
      <div className="inspector__col">
        {load.pending ? <LoadingRows rows={3} /> : load.error !== undefined ? (
          <ActivitiesError error={load.error} onRetry={load.retry} />
        ) : activity === undefined && mark === undefined ? (
          <p className="muted inspector__hint">{t('instance.notReached')}</p>
        ) : (
          <dl className="facts">
            {activity?.enteredAt ? <Fact term={t('instance.fact.entered')}>{formatDateTime(activity.enteredAt, locale)}</Fact> : null}
            {activity?.completedAt ? <Fact term={t('instance.fact.completed')}>{formatDateTime(activity.completedAt, locale)}</Fact> : null}
            {activity?.durationMs !== null && activity?.durationMs !== undefined ? <Fact term={t('instance.fact.duration')}>{durationText(activity.durationMs, t)}</Fact> : null}
            {attempts !== undefined ? <Fact term={t('instance.fact.attempts')}>{attempts}</Fact> : null}
            {activity !== undefined ? <Fact term={t('instance.fact.actor')}>{actorText(activity.actor)}</Fact> : null}
            {task !== undefined ? <Fact term={t('instance.fact.waitingFor')}>{assigneeName(task.assignee)}</Fact> : null}
            {task?.claimedBy ? <Fact term={t('instance.fact.takenBy')}>{actorText(task.claimedBy)}</Fact> : null}
          </dl>
        )}
      </div>
      {activity?.incident || task !== undefined || engineNode !== undefined ? (
        <div className="inspector__col inspector__col--end">
          {activity?.incident ? <IncidentBox code={activity.incident.code} openedAt={activity.incident.openedAt} replica={engineNode} /> : null}
          {task !== undefined ? <TaskAction taskId={task.id} noActivity={load.error !== undefined} /> : null}
          {!activity?.incident && engineNode !== undefined ? <EngineNode replica={engineNode} /> : null}
        </div>
      ) : null}
    </div>
  );
}

/** The engine node that last acted, one click away: an id for operators, not for the page body. */
function EngineNode({ replica }: { replica: string }) {
  const { t } = useI18n();
  return (
    <details className="alert__details inspector__details">
      <summary>{t('area.error.details')}</summary>
      <dl>
        <div><dt>{t('instance.fact.engineNode')}</dt><dd className="mono">{replica} <CopyButton value={replica} /></dd></div>
      </dl>
    </details>
  );
}

function Fact({ term, children }: { term: string; children: ReactNode }) {
  return <div><dt>{term}</dt><dd className="tnum">{children}</dd></div>;
}

function StateLine({ mark, activity }: { mark: NodeMark | undefined; activity: boolean }) {
  const { t } = useI18n();
  if (mark?.kind === 'incident') return <p className="inspector__state"><span className="badge badge--bad"><span aria-hidden="true">✕</span> {t('instance.state.incident')}</span></p>;
  if (mark?.kind === 'token') return <p className="inspector__state"><span className="badge badge--accent"><span aria-hidden="true">{TOKEN_ICON[mark.status]}</span> {t(TOKEN_KEY[mark.status])}</span></p>;
  if (mark?.kind === 'done') return <p className="inspector__state"><span className="badge badge--ok"><span aria-hidden="true">✓</span> {t('instance.state.completed')}</span></p>;
  if (activity) return <p className="inspector__state"><span className="badge badge--neutral"><span aria-hidden="true">•</span> {t('instance.state.passed')}</span></p>;
  return <p className="inspector__state"><span className="badge badge--muted"><span aria-hidden="true">○</span> {t('instance.state.notReached')}</span></p>;
}

/** An open incident in words, with its code and when it opened one click away (styleguide: alerts). */
function IncidentBox({ code, openedAt, replica }: { code: string; openedAt: string; replica: string | undefined }) {
  const { t, locale } = useI18n();
  return (
    <div className="alert alert--error inspector__incident" role="note">
      <span className="alert__icon" aria-hidden="true">✕</span>
      <div className="alert__body">
        <p>{t(incidentKey(code))}</p>
        <details className="alert__details">
          <summary>{t('area.error.details')}</summary>
          <dl>
            <div><dt>{t('error.code')}</dt><dd className="mono">{code} <CopyButton value={code} /></dd></div>
            <div><dt>{t('instance.fact.opened')}</dt><dd className="tnum">{formatDateTime(openedAt, locale)}</dd></div>
            {replica !== undefined ? <div><dt>{t('instance.fact.engineNode')}</dt><dd className="mono">{replica} <CopyButton value={replica} /></dd></div> : null}
          </dl>
        </details>
      </div>
    </div>
  );
}

/**
 * Open the task waiting here. Unavailable, with the reason beside it, without the task permission, or when the
 * service gives no step activity, so the strongest action never sits under a line saying there is nothing to see.
 */
function TaskAction({ taskId, noActivity }: { taskId: string; noActivity: boolean }) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  const reason: MessageKey | undefined = !can('processes:task:work') ? 'instance.openTaskBlocked' : noActivity ? 'instance.openTaskNoActivity' : undefined;
  if (reason === undefined) {
    return <a className="btn btn--primary inspector__action" href={formatRoute({ name: 'task', taskId })}>{t('instance.openTask')}</a>;
  }
  return (
    <div className="inspector__blocked">
      <button type="button" className="btn btn--primary inspector__action" aria-disabled="true" aria-describedby="instance-task-reason">{t('instance.openTask')}</button>
      <p id="instance-task-reason" className="inspector__reason"><span aria-hidden="true">ⓘ</span> {t(reason)}</p>
    </div>
  );
}

/** Every history entry in time order, with the step name and who acted, as the engine returned them. */
function Timeline({ graph, activities }: { graph: DiagramGraph; activities: InstanceActivities }) {
  const { t, locale } = useI18n();
  const actorText = useActorText();
  useWantNames({ users: peopleOf(activities.entries.map((entry) => entry.actor)) });
  const names = new Map(graph.nodes.map((node) => [node.id, node.name ?? node.id]));
  if (activities.entries.length === 0) return <div className="card__pad"><EmptyState icon="processes" text={t('instance.timeline.empty')} /></div>;
  return (
    <div className="timeline" tabIndex={0} role="region" aria-label={t('instance.timeline.label')}>
      <table className="qtable" aria-label={t('instance.timeline.label')}>
        <thead>
          <tr>
            <th scope="col">{t('instance.timeline.when')}</th>
            <th scope="col">{t('instance.timeline.what')}</th>
            <th scope="col">{t('instance.timeline.step')}</th>
            <th scope="col">{t('instance.timeline.who')}</th>
          </tr>
        </thead>
        <tbody>
          {activities.entries.map((entry) => (
            <tr key={entry.id}>
              <td className="tnum muted">{formatDateTime(entry.occurredAt, locale)}</td>
              <td className={isProblemEntry(entry) ? 'timeline__what timeline__what--bad' : 'timeline__what'}>
                {isProblemEntry(entry) ? <span aria-hidden="true">✕ </span> : null}{t(entryKey(entry.type))}
              </td>
              <td>{entry.nodeId !== null ? names.get(entry.nodeId) ?? t('instance.timeline.otherStep') : <span className="muted" aria-label={t('rulesArea.none')}>—</span>}</td>
              <td>{actorText(entry.actor)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {activities.hasMore ? <p className="muted timeline__more">{t('instance.timeline.more', { count: String(activities.entries.length) })}</p> : null}
    </div>
  );
}
