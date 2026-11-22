import { lazy, Suspense, useId, useMemo, useState } from 'react';
import type { GraphDiff, GraphDiffEdge } from '../../../api/processes/schemas';
import { AreaError, LoadingRows } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { en } from '../../../i18n/en';
import { ReviewValue } from '../review/ReviewValue';
import { useDefinitionGraph } from '../api/useProcesses';
import type { DiagramGraph } from '../model/bpmnGraph';
import {
  changedSteps, diagramType, humanise, linkKind, marksFor, pathParts, sideOf, type Mark, type Side,
} from '../model/graphDiff';
import { nodeTypeLabel } from '../model/nodeLabels';
import { useDockReserve } from './dock';

const DefinitionDiagram = lazy(() => import('../diagram/DefinitionDiagram'));

const GLYPH: Record<Mark, string> = { added: '+', changed: '•', removed: '−' };


/** A mark in words with its glyph: the diff never rests on colour alone. */
function MarkTag({ mark }: { mark: Mark }) {
  const { t } = useI18n();
  return (
    <span className={`diff-tag diff-tag--${mark}`}>
      <span aria-hidden="true">{GLYPH[mark]}</span> {t(`diff.mark.${mark}`)}
    </span>
  );
}

/** A member of a step's canonical form in words: the catalogue's label, otherwise the name itself, spaced. */
function useFieldLabel() {
  const { t } = useI18n();
  return (name: string): string => {
    const key = `field.${name}`;
    return key in en ? t(key as MessageKey) : humanise(name);
  };
}

/** Where in a step a value changed, in words: `/branches/0/when` reads "Branch 1 › Condition". */
function usePathLabel() {
  const { t } = useI18n();
  const field = useFieldLabel();
  return (pointer: string): string => {
    const labels: string[] = [];
    for (const part of pathParts(pointer)) {
      if (part.kind === 'name') labels.push(field(part.name));
      else {
        const previous = labels.pop();
        labels.push(previous !== undefined ? t('diff.item', { label: previous, number: String(part.index + 1) }) : String(part.index + 1));
      }
    }
    return labels.join(' › ');
  };
}

function LinkText({ edge }: { edge: GraphDiffEdge }) {
  const { t } = useI18n();
  const kind = linkKind(edge.kind);
  const text = t('diff.link', { from: edge.fromName ?? edge.from, to: edge.toName ?? edge.to });
  return (
    <span className="pdiff__name">
      {text}
      {kind.kind === 'default' ? <span className="pdiff__kind"> · {t('diff.kind.default')}</span> : null}
      {kind.kind === 'condition' ? <span className="pdiff__kind"> · {t('diff.kind.condition', { number: String(kind.number) })}</span> : null}
    </span>
  );
}

interface Selection { id: string; mark: Mark }

/** Every change in words, steps first, then links. A step's entry selects it on the diagram that draws it. */
function ChangeList({ diff, selected, onSelect }: { diff: GraphDiff; selected: string | undefined; onSelect: (selection: Selection) => void }) {
  const { t } = useI18n();
  const pathLabel = usePathLabel();
  const titleId = useId();
  const steps: { id: string; name: string; type: string; mark: Mark; values?: ReturnType<typeof changedSteps>[number]['values'] }[] = [
    ...diff.nodesAdded.map((node) => ({ id: node.id, name: node.name ?? node.id, type: node.type, mark: 'added' as const })),
    ...changedSteps(diff).map((step) => ({ id: step.id, name: step.name ?? step.id, type: step.type ?? '', mark: 'changed' as const, values: step.values })),
    ...diff.nodesRemoved.map((node) => ({ id: node.id, name: node.name ?? node.id, type: node.type, mark: 'removed' as const })),
  ];
  const links = [
    ...diff.edgesAdded.map((edge) => ({ edge, mark: 'added' as const })),
    ...diff.edgesRemoved.map((edge) => ({ edge, mark: 'removed' as const })),
  ];
  return (
    <section className="pdiff__list" aria-labelledby={titleId}>
      <h2 id={titleId} className="pdiff__title">{t('diff.listTitle')}</h2>
      {diff.truncated ? (
        // The reason Approve is unavailable is said once, beside it in the dock; the list says only what it holds.
        <p className="notice notice--info notice--line pdiff__truncated">
          <span className="notice__title"><span className="notice__icon" aria-hidden="true">ⓘ</span><span>{t('diff.truncated.reason')}</span></span>
        </p>
      ) : null}
      {steps.length === 0 && links.length === 0 ? <p className="pdiff__note">{t('diff.none')}</p> : null}
      {steps.length > 0 ? (
        <>
          <h3 className="pdiff__group">{t('diff.steps')}</h3>
          <ul className="pdiff__entries">
            {steps.map((step) => (
              <li key={`${step.mark}:${step.id}`} className={`pdiff__entry pdiff__entry--${step.mark}`}>
                <button type="button" className={selected === step.id ? 'pdiff__pick hit is-selected' : 'pdiff__pick hit'} aria-pressed={selected === step.id}
                  onClick={() => { onSelect({ id: step.id, mark: step.mark }); }}>
                  <MarkTag mark={step.mark} />{' '}
                  <span className="pdiff__name">{step.name}</span>{' '}
                  <span className="pdiff__type">{nodeTypeLabel(diagramType(step.type), t)}</span>
                </button>
                {step.values !== undefined ? (
                  <dl className="pdiff__values">
                    {step.values.map((value) => (
                      <div key={value.path}>
                        <dt>{pathLabel(value.path)}</dt>
                        <dd>
                          <span className="pdiff__from"><ReviewValue value={value.from} /></span>
                          <span aria-hidden="true"> → </span>
                          <span className="sr-only"> {t('diff.to')} </span>
                          <span className="pdiff__to"><ReviewValue value={value.to} /></span>
                        </dd>
                      </div>
                    ))}
                  </dl>
                ) : null}
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {links.length > 0 ? (
        <>
          <h3 className="pdiff__group">{t('diff.links')}</h3>
          <ul className="pdiff__entries">
            {links.map(({ edge, mark }) => (
              <li key={`${mark}:${edge.from}>${edge.to}:${edge.kind}`} className={`pdiff__entry pdiff__entry--${mark} pdiff__link`}>
                <MarkTag mark={mark} />{' '}
                <LinkText edge={edge} />
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </section>
  );
}

/** The two diagrams of the change, one at a time, with the marks of each, and the key to the marks. */
function SideDiagram({ definitionKey, diff, side, onSide, selected, title, targetGraph }: {
  definitionKey: string; diff: GraphDiff; side: Side; onSide: (side: Side) => void; selected: string | undefined; title: string; targetGraph: DiagramGraph;
}) {
  const { t } = useI18n();
  const reserve = useDockReserve();
  const base = useDefinitionGraph(definitionKey, diff.base.version);
  const marks = useMemo(() => marksFor(diff, side), [diff, side]);
  const graph = side === 'target' ? targetGraph : base.data;
  const shown = side === 'target' ? diff.target.version : diff.base.version;
  const markLabel = (mark: Mark) => t(`diff.mark.${mark}`);
  const toolbar = (
    <div className="pdiff__bar">
      <div className="seg seg--small" role="group" aria-label={t('diff.side')}>
        <button type="button" className="hit" aria-pressed={side === 'target'} onClick={() => { onSide('target'); }}>{t('diff.side.target', { version: String(diff.target.version) })}</button>
        <button type="button" className="hit" aria-pressed={side === 'base'} onClick={() => { onSide('base'); }}>{t('diff.side.base', { version: String(diff.base.version) })}</button>
      </div>
      <ul className="pdiff__legend" aria-label={t('diff.legend')}>
        {(side === 'target' ? (['added', 'changed'] as const) : (['removed', 'changed'] as const)).map((mark) => (
          <li key={mark} className={`pdiff__key pdiff__key--${mark}`}><span className="pdiff__swatch" aria-hidden="true">{GLYPH[mark]}</span>{t(`diff.mark.${mark}`)}</li>
        ))}
      </ul>
    </div>
  );
  if (graph === undefined) {
    return base.isError ? <AreaError error={base.error} onRetry={() => { void base.refetch(); }} /> : <LoadingRows rows={6} />;
  }
  return (
    <Suspense fallback={<LoadingRows rows={6} />}>
      <DefinitionDiagram graph={graph} marks={marks} markLabel={markLabel} selected={selected} toolbar={toolbar} reserve={reserve} fill
        label={t('diff.diagramLabel', { name: title, version: String(shown), count: String(graph.nodes.length) })} />
    </Suspense>
  );
}

/**
 * The Changes view of a version (CKP-024): the engine's change list from the version in effect, drawn on either
 * diagram, one at a time, and listed in words beside it. A first version has nothing in effect to compare.
 */
export default function ChangesView({ definitionKey, diff, title, targetGraph }: { definitionKey: string; diff: GraphDiff | null; title: string; targetGraph: DiagramGraph }) {
  const { t } = useI18n();
  const [side, setSide] = useState<Side>('target');
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const reserve = useDockReserve();
  if (diff === null) {
    return (
      <div className="pdiff pdiff--first">
        <p className="pdiff__note">{t('diff.firstVersion')}</p>
        <Suspense fallback={<LoadingRows rows={6} />}>
          <DefinitionDiagram graph={targetGraph} reserve={reserve} label={t('definition.diagramLabel', { name: title, count: String(targetGraph.nodes.length) })} />
        </Suspense>
      </div>
    );
  }
  return (
    <div className="pdiff">
      <div className="pdiff__main">
        <SideDiagram definitionKey={definitionKey} diff={diff} side={side} onSide={(next) => { setSide(next); setSelected(undefined); }} selected={selected} title={title} targetGraph={targetGraph} />
      </div>
      <ChangeList diff={diff} selected={selected} onSelect={(selection) => { setSide(sideOf(selection.mark)); setSelected(selection.id); }} />
    </div>
  );
}
