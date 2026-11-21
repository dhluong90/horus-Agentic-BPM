import { lazy, Suspense, useEffect, useState } from 'react';
import { setCrumbLabel } from '../../app/crumbs';
import { usePersonName } from '../../app/people';
import { formatRoute, useRoute } from '../../app/route';
import { AreaError, EmptyState, LoadingRows, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { Tabs } from '../rules/components/Tabs';
import { formatDateTime } from '../rules/model/dates';
import { useDefinitionGraph, useDefinitionVersion, useInstances } from './api/useProcesses';
import { InstanceStatusPill } from './InstanceStatusPill';
import { flowOrder, type DiagramGraph } from './model/bpmnGraph';
import { nodeTypeLabel } from './model/nodeLabels';
import { DefinitionStatusPill } from './ProcessesPage';

/* The diagram and dagre load on demand, in their own chunk, only when a diagram opens (CKP-028). */
const DefinitionDiagram = lazy(() => import('./diagram/DefinitionDiagram'));

type Lens = 'diagram' | 'steps' | 'cases';

/**
 * One definition version as a diagram, laid out automatically (CKP-069), with its steps as a list beside it, and the
 * cases of its key. The Cases view is kept in the URL, so coming back from a case returns to the list.
 */
export function DefinitionPage({ definitionKey, version, lens: routeLens, pageLimit }: { definitionKey: string; version: number; lens?: 'cases' | undefined; pageLimit: number }) {
  const { t } = useI18n();
  const [, navigate] = useRoute();
  const details = useDefinitionVersion(definitionKey, version);
  const graph = useDefinitionGraph(definitionKey, version);
  const [localLens, setLocalLens] = useState<'diagram' | 'steps'>('diagram');
  const lens: Lens = routeLens === 'cases' ? 'cases' : localLens;
  const select = (next: Lens) => {
    if (next === 'cases') { navigate({ name: 'process', key: definitionKey, version, lens: 'cases' }); return; }
    setLocalLens(next);
    if (routeLens === 'cases') navigate({ name: 'process', key: definitionKey, version });
  };
  const title = details.data?.name ?? definitionKey;
  const name = details.data?.name ?? undefined;
  useEffect(() => {
    setCrumbLabel(name);
    return () => { setCrumbLabel(undefined); };
  }, [name]);
  const steps = graph.data?.nodes.length;
  return (
    <section className="workspace" aria-labelledby="definition-title">
      <PageHead id="definition-title" title={title} subtitle={t('definition.subtitle', { version: String(version) })}
        action={details.data ? <DefinitionStatusPill status={details.data.status} /> : undefined} />
      <article className="card card--diagram">
        <Tabs label={t('definition.lenses')} selected={lens} onSelect={select}
          items={[
            { id: 'diagram', label: t('definition.lens.diagram') },
            { id: 'steps', label: steps !== undefined ? t('definition.lens.steps', { count: String(steps) }) : t('definition.lens.stepsPlain') },
            { id: 'cases', label: t('definition.lens.cases') },
          ]}>
          {lens === 'cases' ? <CaseList definitionKey={definitionKey} pageLimit={pageLimit} /> : graph.isPending ? <LoadingRows rows={6} /> : graph.isError ? (
            <AreaError error={graph.error} onRetry={() => { void graph.refetch(); }} />
          ) : lens === 'diagram' ? (
            <Suspense fallback={<LoadingRows rows={6} />}>
              <DefinitionDiagram graph={graph.data} label={t('definition.diagramLabel', { name: title, count: String(graph.data.nodes.length) })} />
            </Suspense>
          ) : <StepList graph={graph.data} />}
        </Tabs>
      </article>
    </section>
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
