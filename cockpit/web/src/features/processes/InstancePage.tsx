import { lazy, Suspense, useEffect, useId, useMemo, useState, type ReactNode } from 'react';
import type { ActivityEntry, CompensationScope, InstanceActivities, ProcessInstance } from '../../api/processes/schemas';
import { toHostError } from '../../api/hostError';
import { setCrumbTrail } from '../../app/crumbs';
import { nameOf } from '../../app/names';
import { readInstancePanelOpen, storeInstancePanelOpen } from '../../preferences/storage';
import { useNames, useWantNames, wantedOf } from './api/displayNames';
import { formatRoute } from '../../app/route';
import { CopyButton } from '../../components/CopyButton';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import type { MessageKey } from '../../i18n/en';
import { useI18n } from '../../i18n/I18nProvider';
import { Tabs } from '../rules/components/Tabs';
import { formatDateTime } from '../rules/model/dates';
import { useProcessesSession } from './api/processesContext';
import { useDefinitionGraph, useDefinitionVersion, useInstance, useInstanceActivities, useStartAdHocActivity } from './api/useProcesses';
import { adHocScope } from './model/adHoc';
import type { DiagramOverlay, StepBadge } from './diagram/DefinitionDiagram';
import { InstanceStatusPill } from './InstanceStatusPill';
import { ancestorsOf, flowOrder, type DiagramGraph, type DiagramNode } from './model/bpmnGraph';
import {
  activityOf,
  actorReplica,
  durationParts,
  dataNamed,
  entryKey,
  firstStep,
  incidentKey,
  isProblemEntry,
  loopProgress,
  nodeMarks,
  routingWords,
  scopeState,
  undoEntryType,
  wordsOf,
  type UndoEntryType,
  openTaskAt,
  TOKEN_ICON,
  TOKEN_KEY,
  type LoopProgress,
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
  const [picked, setPicked] = useState<string | undefined>(undefined);
  const processName = details.data?.name ?? undefined;
  // The heading names the process; the case number sits in the subtitle and the breadcrumb (#510).
  const title = processName ?? (details.isPending ? t('instance.crumb') : nameOf('process', instance.definitionKey));
  const caseLabel = instance.businessKey ? t('instance.title', { key: instance.businessKey }) : t('instance.crumb');
  useEffect(() => {
    setCrumbTrail([
      ...(processName !== undefined ? [{ label: processName, href: formatRoute({ name: 'process', key: instance.definitionKey, version: instance.definitionVersion, lens: 'cases' }) }] : []),
      { label: caseLabel },
    ]);
  }, [processName, caseLabel, instance.definitionKey, instance.definitionVersion]);
  const subtitle = instance.businessKey
    ? t('instance.caseSubtitle', { key: instance.businessKey, version: String(instance.definitionVersion) })
    : t('instance.subtitlePlain');
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
              <InstanceDiagram instance={instance} graph={graph.data} activities={activities.data} picked={picked} onPick={setPicked} label={t('instance.diagramLabel', { name: caseLabel })}
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

/** The counters an entry of a repeating step carries, in words: "3 of 5 done", or "2 done" for a standard loop. */
function progressWords(counters: { nrOfInstances?: number | undefined; nrOfCompletedInstances?: number | undefined }, t: T): string {
  const done = String(counters.nrOfCompletedInstances ?? 0);
  return counters.nrOfInstances !== undefined ? t('instance.badge.loopProgress', { done, total: String(counters.nrOfInstances) }) : t('instance.badge.loopCount', { done });
}

function badgeOf(mark: NodeMark, t: T, progress: LoopProgress | undefined): StepBadge {
  switch (mark.kind) {
    case 'incident':
      return { tone: 'incident', icon: '✕', text: t('instance.badge.incident'), label: t('instance.badge.incidentLabel', { what: t(incidentKey(mark.code)) }) };
    case 'token': {
      const words = t(TOKEN_KEY[mark.status]);
      if (progress !== undefined) {
        // A step that repeats says how many repetitions are done, as the engine counted them (#496).
        const text = progress.total !== undefined
          ? t('instance.badge.loopProgress', { done: String(progress.done), total: String(progress.total) })
          : t('instance.badge.loopCount', { done: String(progress.done) });
        return { tone: 'token', icon: TOKEN_ICON[mark.status], text, label: `${text}, ${words}` };
      }
      const text = mark.count > 1 ? t('instance.badge.tokens', { what: words, count: String(mark.count) }) : words;
      return { tone: 'token', icon: TOKEN_ICON[mark.status], text, label: text };
    }
    case 'undone':
      return { tone: 'undone', icon: '↺', text: t('instance.badge.undone'), label: t('instance.badge.undone'), hint: t('instance.hint.undone') };
    case 'undoRan':
      return { tone: 'helper', icon: '↺', text: t('instance.badge.undoRan'), label: t('instance.badge.undoRan'), hint: t('instance.hint.undoRan') };
    case 'cancelled':
      return { tone: 'cancelled', icon: '⊘', text: t('instance.badge.cancelled'), label: t('instance.state.cancelled') };
    case 'terminated':
      return { tone: 'terminated', icon: '■', text: t('instance.badge.terminated'), label: t('instance.state.terminated') };
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

function InstanceDiagram({ instance, graph, activities, load, label, picked, onPick }: {
  instance: ProcessInstance;
  graph: DiagramGraph;
  activities: InstanceActivities | undefined;
  load: ActivitiesLoad;
  label: string;
  picked: string | undefined;
  onPick: (nodeId: string) => void;
}) {
  const { t } = useI18n();
  const marks = useMemo(() => nodeMarks(instance, activities, graph), [instance, activities, graph]);
  const order = useMemo(() => flowOrder(graph).map((node) => node.id), [graph]);
  const selected = picked ?? firstStep(marks, order);
  const progress = useMemo(() => loopProgress(activities), [activities]);
  const badges = useMemo(() => new Map([...marks].map(([id, mark]) => [id, badgeOf(mark, t, progress.get(id))])), [marks, t, progress]);
  const node = graph.nodes.find((item) => item.id === selected);
  // Collapsed until the viewer opens it or picks a step, unless they chose before; their choice is remembered.
  const [open, setOpen] = useState(() => readInstancePanelOpen() ?? false);
  const panelId = useId();
  const toggle = () => {
    storeInstancePanelOpen(!open);
    setOpen(!open);
  };
  const pick = (nodeId: string) => {
    onPick(nodeId);
    setOpen(true);
  };
  const overlay: DiagramOverlay = { badges, selected, onSelect: pick };
  const current = graph.nodes.find((item) => item.id === firstStep(marks, order));
  const toolbar = (
    <button type="button" className="btn btn--small btn--quiet hit instance-panel-toggle" aria-expanded={open} aria-controls={panelId} onClick={toggle}>
      <span aria-hidden="true">{open ? '›' : '‹'}</span> {open ? t('instance.panel.hide') : t('instance.panel.show')}
    </button>
  );
  return (
    <div className={open ? 'instance-grid' : 'instance-grid instance-grid--wide'}>
      <div className="instance-grid__canvas">
        <Suspense fallback={<LoadingRows rows={6} />}>
          <DefinitionDiagram graph={graph} label={label} overlay={overlay} toolbar={toolbar} reserve={DIAGRAM_RESERVE} />
        </Suspense>
        <CaseTiles instance={instance} current={current} showAction={!open} />
      </div>
      <aside className="inspector" id={panelId} aria-label={t('instance.inspector')} aria-live="polite" hidden={!open}>
        {node === undefined ? (
          <p className="inspector__hint">{t('instance.pick')}</p>
        ) : (
          <StepDetails instance={instance} graph={graph} node={node} mark={marks.get(node.id)} activities={activities} load={load} />
        )}
      </aside>
    </div>
  );
}

/** Room kept under the canvas: the card's padding, the page margin and the tiles row. */
const DIAGRAM_RESERVE = 132;

/** Where the case stands, under the diagram: the step it waits at and who holds it; the task opens from here when the panel is closed. */
function CaseTiles({ instance, current, showAction }: { instance: ProcessInstance; current: DiagramNode | undefined; showAction: boolean }) {
  const { t } = useI18n();
  const { assigneeName } = useNames();
  const task = current !== undefined ? openTaskAt(instance, current.id) : undefined;
  useWantNames(wantedOf(task !== undefined ? [task.assignee] : [], []));
  const none = <span className="muted" aria-label={t('rulesArea.none')}>—</span>;
  return (
    <section className="case-tiles" aria-label={t('instance.tiles')}>
      <div className="case-tile"><p className="case-tile__term">{t('instance.tile.step')}</p><p className="case-tile__value">{current !== undefined ? (current.name ?? current.id) : none}</p></div>
      <div className="case-tile"><p className="case-tile__term">{t('instance.tile.assigned')}</p><p className="case-tile__value">{task !== undefined ? assigneeName(task.assignee) : none}</p></div>
      {showAction && task !== undefined ? <TaskAction taskId={task.id} noActivity={false} /> : null}
    </section>
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

function StepDetails({ instance, graph, node, mark, activities, load }: {
  instance: ProcessInstance;
  graph: DiagramGraph;
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
      {node.type === 'adHocSubProcess' ? <AdHocActions instance={instance} graph={graph} node={node} /> : null}
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

/**
 * An open ad-hoc sub-process (#500): the activities the operator may start now, each one button. The action needs
 * processes:task:manage; without it the buttons are disabled and say why. The engine checks again on every start.
 */
function AdHocActions({ instance, graph, node }: { instance: ProcessInstance; graph: DiagramGraph; node: DiagramNode }) {
  const { t } = useI18n();
  const { can } = useProcessesSession();
  const reasonId = useId();
  const start = useStartAdHocActivity(instance);
  const scope = adHocScope(graph, instance, node.id);
  if (scope === undefined) return null;
  const allowed = can('processes:task:manage');
  return (
    <div className="inspector__col inspector__col--end" role="group" aria-label={t('instance.adHoc.title')}>
      <p className="inspector__type">{t('instance.adHoc.title')}</p>
      {scope.enabled.length === 0 ? <p className="muted inspector__hint">{t('instance.adHoc.none')}</p> : scope.enabled.map((activity) => (
        <button key={activity.id} type="button" className="btn btn--primary inspector__action" disabled={start.isPending}
          aria-disabled={!allowed} aria-describedby={allowed ? undefined : reasonId}
          onClick={() => { if (allowed) start.mutate({ scopeTokenId: scope.scopeTokenId, activityId: activity.id }); }}>
          {t('instance.adHoc.start', { name: activity.name ?? activity.id })}
        </button>
      ))}
      {!allowed ? <p id={reasonId} className="inspector__reason"><span aria-hidden="true">ⓘ</span> {t('instance.adHoc.blocked')}</p> : null}
      {start.isError ? <p role="alert" className="inspector__reason">{t('instance.adHoc.failed')}</p> : null}
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
  if (mark?.kind === 'undone') return <p className="inspector__state"><span className="badge badge--warn" title={t('instance.hint.undone')}><span aria-hidden="true">↺</span> {t('instance.state.undone')}</span></p>;
  if (mark?.kind === 'undoRan') return <p className="inspector__state"><span className="badge badge--neutral" title={t('instance.hint.undoRan')}><span aria-hidden="true">↺</span> {t('instance.state.undoRan')}</span></p>;
  if (mark?.kind === 'cancelled') return <p className="inspector__state"><span className="badge badge--neutral"><span aria-hidden="true">⊘</span> {t('instance.state.cancelled')}</span></p>;
  if (mark?.kind === 'terminated') return <p className="inspector__state"><span className="badge badge--neutral"><span aria-hidden="true">■</span> {t('instance.state.terminated')}</span></p>;
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
  const reasonId = useId();
  const reason: MessageKey | undefined = !can('processes:task:work') ? 'instance.openTaskBlocked' : noActivity ? 'instance.openTaskNoActivity' : undefined;
  if (reason === undefined) {
    return <a className="btn btn--primary inspector__action" href={formatRoute({ name: 'task', taskId })}>{t('instance.openTask')}</a>;
  }
  return (
    <div className="inspector__blocked">
      <button type="button" className="btn btn--primary inspector__action" aria-disabled="true" aria-describedby={reasonId}>{t('instance.openTask')}</button>
      <p id={reasonId} className="inspector__reason"><span aria-hidden="true">ⓘ</span> {t(reason)}</p>
    </div>
  );
}

/** A message step in the timeline: the message name, the key as the host masked it, and the delivery result (#491). */
function MessageDetail({ message }: { message: NonNullable<ActivityEntry['message']> }) {
  const { t } = useI18n();
  const result = `instance.timeline.messageResult.${message.result}` as MessageKey;
  const known = ['WAITING', 'DELIVERED', 'HANDED_OVER'].includes(message.result);
  return (
    <span className="muted">
      {' · '}{t('instance.timeline.message', { name: message.name })}
      {message.correlationKey !== undefined ? <>{' · '}{t('instance.timeline.messageKey', { key: message.correlationKey })}</> : null}
      {known ? <>{' · '}{t(result)}</> : null}
    </span>
  );
}

const TRIGGERS = ['timer', 'message', 'signal', 'conditional', 'error', 'escalation', 'compensate'] as const;

/** What started an event sub-process and whether it interrupted its scope, read from the definition's own start event (#487). */
function TriggerDetail({ node }: { node: DiagramNode | undefined }) {
  const { t } = useI18n();
  if (node?.trigger === undefined) return null;
  const known = (TRIGGERS as readonly string[]).includes(node.trigger);
  return (
    <span className="muted">
      {known ? <>{' · '}{t(`instance.timeline.trigger.${node.trigger}` as MessageKey)}</> : null}
      {' · '}{t(node.interrupting === false ? 'instance.timeline.beside' : 'instance.timeline.interrupted')}
    </span>
  );
}

/** Which branches a gateway took and why a join waits, beside its timeline row (#494). */
function RoutingNote({ entry, graph }: { entry: InstanceActivities['entries'][number]; graph: DiagramGraph }) {
  const { t } = useI18n();
  const routing = routingWords(entry, graph);
  if (routing === undefined) return null;
  const steps = routing.steps.join(', ');
  const key = routing.kind === 'waiting' ? 'instance.timeline.waitingFor' : routing.kind === 'tookDefault' ? 'instance.timeline.tookDefault' : 'instance.timeline.took';
  return <span className="muted"> · {t(key, { steps })}</span>;
}

/** Which data objects and stores a step read when it started, and which one it wrote (#499), by their names in the diagram. */
function DataNote({ entry, entries, graph }: { entry: ActivityEntry; entries: readonly ActivityEntry[]; graph: DiagramGraph }) {
  const { t } = useI18n();
  const named = dataNamed(entry, entries, graph);
  if (named === undefined) return null;
  return <span className="muted"> · {t(named.direction === 'read' ? 'instance.timeline.dataRead' : 'instance.timeline.dataWritten', { names: named.names.join(', ') })}</span>;
}

const FIRED_TRIGGERS = ['timer', 'message', 'signal', 'condition', 'error', 'escalation'] as const;

/**
 * A boundary event that fired names its trigger (the message, the signal or the condition) and whether it stopped the
 * step; an event-based gateway names the catch that came first and the ones it stopped (#490, #495). Names come from the
 * diagram, never the ids the engine sent.
 */
function EventDetail({ event, stepName }: { event: NonNullable<ActivityEntry['event']>; stepName: (id: string) => string }) {
  const { t } = useI18n();
  if (event.winner !== undefined) {
    const others = (event.cancelled ?? []).map(stepName).join(', ');
    return (
      <span className="muted">
        {' · '}{t('instance.timeline.gateway.won', { winner: stepName(event.winner) })}
        {others !== '' ? <>{' · '}{t('instance.timeline.gateway.stopped', { others })}</> : null}
      </span>
    );
  }
  const trigger = event.trigger?.toLowerCase();
  const known = FIRED_TRIGGERS.find((item) => item === trigger);
  return (
    <span className="muted">
      {known !== undefined ? <>{' · '}{t(`instance.timeline.fired.${known}` as MessageKey, { name: event.name ?? '' })}</> : null}
      {event.interrupting !== undefined ? <>{' · '}{t(event.interrupting ? 'instance.timeline.fired.interrupting' : 'instance.timeline.fired.beside')}</> : null}
    </span>
  );
}

/** Every history entry in time order, with the step name and who acted, as the engine returned them. */
function Timeline({ graph, activities }: { graph: DiagramGraph; activities: InstanceActivities }) {
  const { t, locale } = useI18n();
  const actorText = useActorText();
  useWantNames({ users: peopleOf(activities.entries.map((entry) => entry.actor)) });
  const names = new Map(graph.nodes.map((node) => [node.id, node.name ?? node.id]));
  const stepName = (id: string) => names.get(id) ?? wordsOf(id);
  // A step inside a sub-process names the sub-processes around it, outermost first, so the timeline nests its steps (#486).
  const trail = (id: string | null): string[] => (id === null ? [] : ancestorsOf(graph, id).map((node) => node.name ?? node.id));
  const scopes = activities.compensations ?? [];
  if (activities.entries.length === 0) return <div className="card__pad"><EmptyState icon="processes" text={t('instance.timeline.empty')} /></div>;
  return (
    <>
      {scopes.length > 0 ? (
        <section className="undo-scopes" aria-label={t('instance.timeline.undoLabel')}>
          {scopes.map((scope) => <UndoScope key={scope.scopeTokenId} scope={scope} stepName={stepName} />)}
        </section>
      ) : null}
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
            {activities.entries.map((entry) => {
              const undo = undoEntryType(entry);
              return undo !== undefined ? (
                <UndoRow key={entry.id} entry={entry} type={undo} stepName={stepName} actorText={actorText} />
              ) : (
                <tr key={entry.id}>
                  <td className="tnum muted">{formatDateTime(entry.occurredAt, locale)}</td>
                  <td className={isProblemEntry(entry) ? 'timeline__what timeline__what--bad' : 'timeline__what'}>
                    {isProblemEntry(entry) ? <span aria-hidden="true">✕ </span> : null}{t(entryKey(entry.type), { counter: String(entry.instances?.loopCounter ?? '') })}
                    {entry.instances !== undefined ? <span className="muted timeline__note"> · {progressWords(entry.instances, t)}</span> : null}
                  </td>
                  <td className={trail(entry.nodeId).length > 0 ? 'timeline__step timeline__step--nested' : 'timeline__step'} data-depth={trail(entry.nodeId).length}>
                    {trail(entry.nodeId).length > 0 ? <span className="muted timeline__trail">{trail(entry.nodeId).join(' › ')} › </span> : null}
                    {entry.nodeId !== null ? names.get(entry.nodeId) ?? t('instance.timeline.otherStep') : <span className="muted" aria-label={t('rulesArea.none')}>—</span>}
                    {entry.type === 'NODE_COMPLETED' && entry.resultVariable !== undefined
                      ? <span className="muted"> · {t('instance.timeline.sets', { variable: entry.resultVariable })}</span> : null}
                    {entry.type === 'SIGNAL_THROWN' && entry.waiters !== undefined
                      ? <span className="muted"> · {t(entry.waiters === 1 ? 'instance.timeline.woke.one' : 'instance.timeline.woke.many', { count: String(entry.waiters) })}</span> : null}
                    {entry.type === 'EVENT_SUBPROCESS_STARTED' ? <TriggerDetail node={graph.nodes.find((node) => node.id === entry.nodeId)} /> : null}
                    {entry.message !== undefined ? <MessageDetail message={entry.message} /> : null}
                    <RoutingNote entry={entry} graph={graph} />
                    <DataNote entry={entry} entries={activities.entries} graph={graph} />
                    {entry.event !== undefined ? <EventDetail event={entry.event} stepName={stepName} /> : null}
                  </td>
                  <td>{actorText(entry.actor)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {activities.hasMore ? <p className="muted timeline__more">{t('instance.timeline.more', { count: String(activities.entries.length) })}</p> : null}
      </div>
    </>
  );
}

/**
 * Where the undo of one saga scope stands, in words, as the engine states it. An undo that did not finish is a calm
 * warning with the one way forward, a link to its incident in the incident queue; nothing here says the case was put right unless the engine says the scope is undone.
 */
function UndoScope({ scope, stepName }: { scope: CompensationScope; stepName: (id: string) => string }) {
  const { t } = useI18n();
  const state = scopeState(scope);
  const title = t(`instance.undo.scope.${state}`, { scope: scope.scope !== null ? stepName(scope.scope) : t('instance.timeline.otherStep') });
  const steps = t(scope.stepsUndone === 1 ? 'instance.undo.steps.one' : 'instance.undo.steps.many', { count: String(scope.stepsUndone) });
  const detail = state === 'NOT_FULLY_UNDONE' && scope.blockedStep !== null
    ? t('instance.undo.blocked', { step: stepName(scope.blockedStep) })
    : state === 'UNDONE'
      ? scope.failingStep !== null ? t('instance.undo.after', { step: stepName(scope.failingStep), steps }) : steps
      : t('instance.undo.running');
  const blocked = scope.blockedStep;
  return (
    <div className={`undo-scope undo-scope--${state === 'UNDONE' ? 'done' : state === 'NOT_FULLY_UNDONE' ? 'blocked' : 'running'}`}>
      <span className="undo-scope__icon" aria-hidden="true">{state === 'UNDONE' ? '✓' : state === 'NOT_FULLY_UNDONE' ? '!' : '↺'}</span>
      <p className="undo-scope__text"><strong>{title}</strong> <span>{detail}</span></p>
      {state === 'NOT_FULLY_UNDONE' && blocked !== null && scope.incidentId !== null ? (
        <a className="btn btn--small" href={formatRoute({ name: 'incidents', view: 'open', incidentId: scope.incidentId })}>{t('instance.undo.openIncident')}</a>
      ) : null}
    </div>
  );
}

/** One entry of an undo: marked ↺ and by the word "Undo", with a left edge in the warning hue; the failure is plain words, not red. */
function UndoRow({ entry, type, stepName, actorText }: { entry: ActivityEntry; type: UndoEntryType; stepName: (id: string) => string; actorText: (actor: string | null) => string }) {
  const { t, locale } = useI18n();
  const handler = entry.handler !== undefined ? wordsOf(entry.handler) : '';
  const step = entry.compensates !== undefined ? stepName(entry.compensates) : '';
  const scope = entry.scope !== undefined ? stepName(entry.scope) : undefined;
  const failed = type === 'COMPENSATION_STEP_FAILED' || type === 'COMPENSATION_STEP_MANUAL';
  const label = (() => {
    switch (type) {
      case 'COMPENSATION_STARTED': return t('instance.undo.started');
      case 'COMPENSATED': return t('instance.undo.finished');
      case 'COMPENSATION_STEP_FAILED': return t('instance.undo.stepFailed', { handler });
      case 'COMPENSATION_STEP_MANUAL': return t('instance.undo.stepManual', { handler });
      default: return t('instance.undo.step', { step, handler });
    }
  })();
  const note = (() => {
    const count = String(entry.attempt ?? 1);
    switch (type) {
      case 'COMPENSATION_STEP_SCHEDULED': return entry.attempt !== undefined && entry.attempt > 1 ? t('instance.undo.attempt', { count }) : t('instance.undo.state.sent');
      case 'COMPENSATION_STEP_COMPLETED': return t('instance.undo.state.done');
      case 'COMPENSATION_STEP_FAILED': return t(entry.attempt === 1 ? 'instance.undo.failedAfter.one' : 'instance.undo.failedAfter.many', { count });
      case 'COMPENSATION_STEP_MANUAL': return t('instance.undo.state.person');
      default: return undefined;
    }
  })();
  return (
    <tr className={failed ? 'timeline__row--undo timeline__row--undo-stuck' : 'timeline__row--undo'}>
      <td className="tnum muted">{formatDateTime(entry.occurredAt, locale)}</td>
      <td className="timeline__what">
        <span className="timeline__undo"><span className="timeline__glyph" aria-hidden="true">{failed ? '!' : '↺'}</span><span>{label}</span>{note !== undefined ? <span className="timeline__note">{note}</span> : null}</span>
      </td>
      <td>{scope ?? <span className="muted" aria-label={t('rulesArea.none')}>—</span>}</td>
      <td>{actorText(entry.actor)}</td>
    </tr>
  );
}
