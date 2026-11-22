import { lazy, Suspense, useEffect, useState } from 'react';
import { setCrumbLabel } from '../../app/crumbs';
import { suppliedName, usePersonName } from '../../app/people';
import { formatRoute, type ProcessLens } from '../../app/route';
import type { DefinitionVersion } from '../../api/processes/schemas';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { Tabs } from '../rules/components/Tabs';
import { formatDateTime } from '../rules/model/dates';
import { useProcessesSession } from './api/processesContext';
import { useApproveDefinition, useDefinitionDiff, useDefinitionGraph, useDefinitionVersion, useInstances, useRejectDefinition } from './api/useProcesses';
import { ApprovalDock, DecidedLine } from './approval/ApprovalDock';
import { DOCK_RESERVE } from './approval/dock';
import { InstanceStatusPill } from './InstanceStatusPill';
import { approvalBlock, reviewedHash, type DiffState } from './model/graphDiff';
import { flowOrder, type DiagramGraph } from './model/bpmnGraph';
import { nodeTypeLabel } from './model/nodeLabels';
import { DefinitionStatusPill } from './ProcessesPage';

/* The diagram and dagre load on demand, in their own chunk, only when a diagram opens (CKP-028). */
const DefinitionDiagram = lazy(() => import('./diagram/DefinitionDiagram'));
/* The Changes view loads with the diagram, only when a version's changes open (CKP-024). */
const ChangesView = lazy(() => import('./approval/ChangesView'));

/** A version whose change from the version in effect is worth reading: a draft, or one waiting for approval. */
const hasChanges = (version: DefinitionVersion | undefined) => version?.status === 'PENDING_APPROVAL' || version?.status === 'DRAFT';

type Approve = ReturnType<typeof useApproveDefinition>;
type Reject = ReturnType<typeof useRejectDefinition>;

/**
 * One definition version as a diagram, laid out automatically (CKP-069), with its steps as a list beside it, and the
 * cases of its key. A version waiting for approval opens on its Changes (CKP-024) with the four-eyes decision docked
 * below. The view is kept in the URL, so coming back from a case returns to the list.
 */
export function DefinitionPage({ definitionKey, version, lens, pageLimit }: { definitionKey: string; version: number; lens?: ProcessLens | undefined; pageLimit: number }) {
  const { t } = useI18n();
  const personName = usePersonName();
  const { can } = useProcessesSession();
  const details = useDefinitionVersion(definitionKey, version);
  const graph = useDefinitionGraph(definitionKey, version);
  const reviewable = hasChanges(details.data);
  const diff = useDefinitionDiff(definitionKey, version, reviewable);
  const approve = useApproveDefinition(definitionKey);
  const reject = useRejectDefinition(definitionKey);
  // The outcome is the engine's answer to this page's decision; nothing is shown as decided before it.
  const decided = approve.isSuccess ? 'approved' : reject.isSuccess ? 'rejected' : undefined;
  const pending = details.data?.status === 'PENDING_APPROVAL';
  const shown: ProcessLens = lens === 'changes' && !reviewable && details.data !== undefined ? 'diagram' : lens ?? (pending ? 'changes' : 'diagram');
  const title = details.data?.name ?? definitionKey;
  const name = details.data?.name ?? undefined;
  useEffect(() => {
    setCrumbLabel(name);
    return () => { setCrumbLabel(undefined); };
  }, [name]);
  const select = (next: ProcessLens) => { window.location.hash = formatRoute({ name: 'process', key: definitionKey, version, lens: next }); };
  const requester = details.data?.requestedBy;
  const subtitle = pending && requester !== null && requester !== undefined
    ? t('definition.subtitlePending', { version: String(version), who: personName(requester, suppliedName(details.data, 'requestedBy')) })
    : t('definition.subtitle', { version: String(version) });
  const docked = pending || decided !== undefined;
  const steps = graph.data?.nodes.length;
  const items = [
    ...(reviewable ? [{ id: 'changes' as const, label: t('definition.lens.changes') }] : []),
    { id: 'diagram' as const, label: t('definition.lens.diagram') },
    { id: 'steps' as const, label: steps !== undefined ? t('definition.lens.steps', { count: String(steps) }) : t('definition.lens.stepsPlain') },
    { id: 'cases' as const, label: t('definition.lens.cases') },
  ];
  return (
    <section className={docked ? 'workspace workspace--docked' : 'workspace'} aria-labelledby="definition-title">
      <PageHead id="definition-title" title={title} subtitle={subtitle}
        action={details.data ? (
          <>
            {details.data.status === 'ACTIVE' && version > 1 && can('processes:migration:manage') ? (
              <a className="btn btn--secondary" href={formatRoute({ name: 'migrate', key: definitionKey, version })}>{t('migration.start')}</a>
            ) : null}
            <DefinitionStatusPill status={details.data.status} />
          </>
        ) : undefined} />
      <article className="card card--diagram">
        <Tabs label={t('definition.lenses')} selected={shown} onSelect={select} items={items}>
          {shown === 'cases' ? <CaseList definitionKey={definitionKey} pageLimit={pageLimit} /> : graph.isPending ? <LoadingRows rows={6} /> : graph.isError ? (
            <AreaError error={graph.error} onRetry={() => { void graph.refetch(); }} />
          ) : shown === 'changes' ? (
            diff.isPending ? <LoadingRows rows={6} /> : diff.isError ? (
              <AreaError error={diff.error} onRetry={() => { void diff.refetch(); }} />
            ) : (
              <Suspense fallback={<LoadingRows rows={6} />}>
                <ChangesView definitionKey={definitionKey} diff={diff.data} title={title} targetGraph={graph.data} />
              </Suspense>
            )
          ) : shown === 'diagram' ? (
            <Suspense fallback={<LoadingRows rows={6} />}>
              <DefinitionDiagram graph={graph.data} label={t('definition.diagramLabel', { name: title, count: String(graph.data.nodes.length) })} {...(docked ? { reserve: DOCK_RESERVE } : {})} />
            </Suspense>
          ) : <StepList graph={graph.data} />}
        </Tabs>
      </article>
      {details.data !== undefined && docked ? (
        decided !== undefined ? <DecidedLine version={details.data} outcome={decided} /> : (
          <Decision version={details.data} diff={diff} approve={approve} reject={reject} onReload={() => { void details.refetch(); }} />
        )
      ) : null}
    </section>
  );
}

/**
 * The decision for a pending version. The hash it approves is the one of the content the user first saw on this
 * page; when the content on screen moves on, Approve waits until the user confirms they checked it again.
 */
function Decision({ version, diff, approve, reject, onReload }: {
  version: DefinitionVersion; diff: ReturnType<typeof useDefinitionDiff>; approve: Approve; reject: Reject; onReload: () => void;
}) {
  const { userId, can } = useProcessesSession();
  const state: DiffState = diff.isPending ? { status: 'loading' } : diff.isError ? { status: 'error' } : diff.data === null ? { status: 'first-version' } : { status: 'ready', diff: diff.data };
  const current = reviewedHash(state, version);
  const [seen, setSeen] = useState<string | undefined>(undefined);
  // The first content the user sees is the content they review; taken once, while rendering (no extra paint).
  if (seen === undefined && current !== undefined) setSeen(current);
  const block = approvalBlock({ version, state, userId, canApprove: can('processes:approve'), seenHash: seen });
  return (
    <ApprovalDock version={version} block={block} hash={seen}
      approve={{
        start: ({ comment }) => { if (seen !== undefined) approve.mutate({ version, args: { expectedDefinitionHash: seen, comment } }); },
        isPending: approve.isPending,
        error: approve.error ?? undefined,
      }}
      reject={{
        start: ({ reason }) => { reject.mutate({ version, args: reason }); },
        isPending: reject.isPending,
        error: reject.error ?? undefined,
      }}
      onReviewAgain={() => { approve.reset(); reject.reset(); setSeen(current); }}
      onReload={() => {
        approve.reset();
        reject.reset();
        onReload();
        void diff.refetch();
      }}
    />
  );
}

/** Every step in flow order with its type in words: the keyboard and reader path through the diagram. */
function StepList({ graph }: { graph: DiagramGraph }) {
  const { t } = useI18n();
  return (
    <ol className="step-list" aria-label={t('definition.lens.stepsLabel')}>
      {flowOrder(graph).map((node) => (
        <li key={node.id} className="step-list__item">
          <span className="step-list__name">{node.name ?? node.id}</span>
          <span className={node.type === 'serviceTask:agentTask' ? 'step-list__type step-list__type--agent' : 'step-list__type'}>{nodeTypeLabel(node.type, t)}</span>
        </li>
      ))}
    </ol>
  );
}

/** The cases of this process key, newest first, every status, a page at a time; each opens on its diagram. */
function CaseList({ definitionKey, pageLimit }: { definitionKey: string; pageLimit: number }) {
  const { t, locale } = useI18n();
  const cases = useInstances(definitionKey, pageLimit);
  const person = usePersonName();
  if (cases.isPending) return <LoadingRows rows={4} />;
  if (cases.isError) return <AreaError error={cases.error} onRetry={() => { void cases.refetch(); }} />;
  const items = cases.data.pages.flatMap((page) => page.data);
  if (items.length === 0) return <div className="card__pad"><EmptyState icon="processes" text={t('cases.empty')} /></div>;
  return (
    <div className="cases">
      <table className="qtable qtable--links" aria-label={t('cases.label')}>
        <thead>
          <tr>
            <th scope="col">{t('cases.col.case')}</th>
            <th scope="col">{t('cases.col.status')}</th>
            <th scope="col">{t('cases.col.version')}</th>
            <th scope="col">{t('cases.col.started')}</th>
            <th scope="col">{t('cases.col.startedBy')}</th>
            <th scope="col"><span className="sr-only">{t('processes.col.open')}</span></th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id}>
              <th scope="row">
                <a className="qtable__link" href={formatRoute({ name: 'instance', instanceId: item.id })}>
                  {item.businessKey ?? t('cases.noKey')}
                </a>
              </th>
              <td><InstanceStatusPill status={item.status} /></td>
              <td className="tnum">{t('rules.version', { version: String(item.definitionVersion) })}</td>
              <td className="tnum muted">{formatDateTime(item.startedAt, locale)}</td>
              <td>{item.startedBy ? person(item.startedBy) : <span className="muted" aria-label={t('rulesArea.none')}>—</span>}</td>
              <td className="qtable__chevron" aria-hidden="true">›</td>
            </tr>
          ))}
        </tbody>
      </table>
      {cases.hasNextPage ? (
        <div className="cases__more">
          <button type="button" className="btn btn--secondary" disabled={cases.isFetchingNextPage} aria-busy={cases.isFetchingNextPage}
            onClick={() => { void cases.fetchNextPage(); }}>{t('cases.more')}</button>
        </div>
      ) : null}
    </div>
  );
}
