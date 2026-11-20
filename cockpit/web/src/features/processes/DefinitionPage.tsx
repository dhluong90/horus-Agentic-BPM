import { lazy, Suspense, useEffect, useState } from 'react';
import { setCrumbLabel } from '../../app/crumbs';
import { AreaError, LoadingRows, PageHead } from '../../components/PageParts';
import { useI18n } from '../../i18n/I18nProvider';
import { Tabs } from '../rules/components/Tabs';
import { useDefinitionGraph, useDefinitionVersion } from './api/useProcesses';
import { flowOrder, type DiagramGraph } from './model/bpmnGraph';
import { nodeTypeLabel } from './model/nodeLabels';
import { DefinitionStatusPill } from './ProcessesPage';

/* The diagram and dagre load on demand, in their own chunk, only when a diagram opens (CKP-028). */
const DefinitionDiagram = lazy(() => import('./diagram/DefinitionDiagram'));

type Lens = 'diagram' | 'steps';

/** One definition version as a diagram, laid out automatically (CKP-069), with its steps as a list beside it. */
export function DefinitionPage({ definitionKey, version }: { definitionKey: string; version: number }) {
  const { t } = useI18n();
  const details = useDefinitionVersion(definitionKey, version);
  const graph = useDefinitionGraph(definitionKey, version);
  const [lens, setLens] = useState<Lens>('diagram');
  const title = details.data?.name ?? definitionKey;
  const name = details.data?.name ?? undefined;
  useEffect(() => {
    setCrumbLabel(name);
    return () => { setCrumbLabel(undefined); };
  }, [name]);
  return (
    <section className="workspace" aria-labelledby="definition-title">
      <PageHead id="definition-title" title={title} subtitle={t('definition.subtitle', { version: String(version) })}
        action={details.data ? <DefinitionStatusPill status={details.data.status} /> : undefined} />
      <article className="card card--diagram">
        {graph.isPending ? <LoadingRows rows={6} /> : graph.isError ? (
          <AreaError error={graph.error} onRetry={() => { void graph.refetch(); }} />
        ) : (
          <Tabs label={t('definition.lenses')} selected={lens} onSelect={setLens}
            items={[{ id: 'diagram', label: t('definition.lens.diagram') }, { id: 'steps', label: t('definition.lens.steps', { count: String(graph.data.nodes.length) }) }]}>
            {lens === 'diagram' ? (
              <Suspense fallback={<LoadingRows rows={6} />}>
                <DefinitionDiagram graph={graph.data} label={t('definition.diagramLabel', { name: title, count: String(graph.data.nodes.length) })} />
              </Suspense>
            ) : <StepList graph={graph.data} />}
          </Tabs>
        )}
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
