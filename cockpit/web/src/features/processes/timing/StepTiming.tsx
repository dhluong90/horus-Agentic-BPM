import { lazy, Suspense, useState, type ReactNode } from 'react';
import type { StepWindowFigures } from '../../../api/processes/schemas';
import { AreaError, EmptyState, LoadingRows } from '../../../components/PageParts';
import { useI18n } from '../../../i18n/I18nProvider';
import { formatDateTime } from '../../rules/model/dates';
import { useStepTiming } from '../api/useProcesses';
import { flowOrder, type DiagramGraph } from '../model/bpmnGraph';
import { nodeTypeLabel } from '../model/nodeLabels';
import type { StepHeat } from '../diagram/DefinitionDiagram';
import { formatDuration, heatLevel, HOT_SHARE, type DurationWords, shareOfSlowest, TIMING_WINDOWS, windowStart, type TimingWindowId } from './timing';

/* The diagram loads on demand, in the chunk the Diagram tab shares (CKP-028). */
const DefinitionDiagram = lazy(() => import('../diagram/DefinitionDiagram'));

type Shown = 'diagram' | 'table';
const LEVELS = [
  { level: 1, key: 'timing.key.1' },
  { level: 2, key: 'timing.key.2' },
  { level: 3, key: 'timing.key.3' },
  { level: 4, key: 'timing.key.4' },
] as const;

/**
 * How long each step takes over a chosen period: the median and the 95th percentile of completed durations, from the
 * engine's hourly rollup, never computed here. On the diagram each step's box is tinted by its 95th percentile against the slowest step, with the figure written on it,
 * and a key of swatches names the four tints. In the table the thin bar is the 95th percentile against the slowest step of the
 * list; steps near the slowest take the warm colour. Numbers are plain and tabular; nothing is boxed.
 */
export function StepTiming({ definitionKey, version, graph }: { definitionKey: string; version: number; graph: DiagramGraph }) {
  const { t, locale } = useI18n();
  const [period, setPeriod] = useState<TimingWindowId>('24h');
  const [now] = useState(() => Date.now());
  const [shown, setShown] = useState<Shown>('diagram');
  const stats = useStepTiming(definitionKey, version, windowStart(now, period));
  const select = (
    <div className="timing__period">
      <label htmlFor="timing-period" className="timing__period-label">{t('timing.period')}</label>
      <select id="timing-period" className="field__input field__input--compact" value={period} onChange={(event) => { setPeriod(event.target.value as TimingWindowId); }}>
        {TIMING_WINDOWS.map((w) => <option key={w.id} value={w.id}>{t(`timing.window.${w.id}`)}</option>)}
      </select>
      <div className="timing__views seg seg--small" role="group" aria-label={t('timing.view')}>
        {(['diagram', 'table'] as const).map((id) => (
          <button key={id} type="button" className="hit" aria-pressed={shown === id} onClick={() => { setShown(id); }}>{t(`timing.view.${id}`)}</button>
        ))}
      </div>
    </div>
  );
  if (stats.isPending) return <div className="timing">{select}<LoadingRows rows={5} /></div>;
  if (stats.isError) return <div className="timing">{select}<AreaError error={stats.error} onRetry={() => { void stats.refetch(); }} /></div>;
  const rows = flowOrder(graph).flatMap((node) => {
    const figures = stats.data.nodes[node.id];
    return figures === undefined ? [] : [{ node, figures }];
  });
  const slowest = rows.reduce((top, { figures }) => Math.max(top, figures.p95Ms ?? 0), 0);
  const behind = stats.data.lagSeconds ?? 0;
  const upTo = formatDateTime(stats.data.asOf, locale);
  const words: DurationWords = { under: t('timing.underSecond'), unit: (unit, n) => t(`timing.unit.${unit}`, { n: String(n) }) };
  const dash = <span className="muted" aria-label={t('rulesArea.none')}>—</span>;
  const duration = (ms: number | null | undefined) => (ms === null || ms === undefined ? dash : formatDuration(ms, words));
  const count = (n: number) => (n === 0 ? dash : n.toLocaleString(locale === 'vi' ? 'vi-VN' : 'en-GB'));
  const heat = new Map<string, StepHeat>(rows.flatMap(({ node, figures }) => {
    if (figures.p95Ms === null || figures.p95Ms === undefined) return [];
    const text = formatDuration(figures.p95Ms, words);
    return [[node.id, { level: heatLevel(shareOfSlowest(figures.p95Ms, slowest)), text, label: t('timing.heatLabel', { time: text }) }] as const];
  }));
  const key = (
    <ul className="timing__key" aria-label={t('timing.key')}>
      {LEVELS.map(({ level, key: words }) => (
        <li key={level} className="timing__key-item"><span className={`timing__swatch timing__swatch--${String(level)}`} aria-hidden="true" />{t(words)}</li>
      ))}
    </ul>
  );
  return (
    <div className="timing">
      {select}
      {rows.length > 0 && shown === 'diagram' ? (
        <Suspense fallback={<LoadingRows rows={5} />}>
          <DefinitionDiagram graph={graph} heat={heat} label={t('timing.diagramLabel', { count: String(rows.length) })} toolbar={key} />
        </Suspense>
      ) : rows.length === 0 ? <div className="card__pad"><EmptyState icon="processes" text={t('timing.empty')} /></div> : (
        <table className="qtable timing__table" aria-label={t('timing.label')}>
          <thead>
            <tr>
              <th scope="col">{t('timing.col.step')}</th>
              <th scope="col" className="num">{t('timing.col.entered')}</th>
              <th scope="col" className="num">{t('timing.col.median')}</th>
              <th scope="col" className="num">{t('timing.col.slow')}</th>
              <th scope="col"><span className="sr-only">{t('timing.col.bar')}</span></th>
              <th scope="col" className="num">{t('timing.col.failed')}</th>
              <th scope="col" className="num">{t('timing.col.incidents')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ node, figures }) => <TimingRow key={node.id} name={node.name ?? node.id} type={nodeTypeLabel(node.type, t)} figures={figures} slowest={slowest} duration={duration} count={count} />)}
          </tbody>
        </table>
      )}
      {upTo !== undefined ? <p className="muted timing__asof">{behind > 60 ? t('timing.asOfBehind', { time: upTo }) : t('timing.asOf', { time: upTo })}</p> : null}
    </div>
  );
}

function TimingRow({ name, type, figures, slowest, duration, count }: {
  name: string; type: string; figures: StepWindowFigures; slowest: number;
  duration: (ms: number | null | undefined) => ReactNode; count: (n: number) => ReactNode;
}) {
  const share = shareOfSlowest(figures.p95Ms, slowest);
  const hot = share >= HOT_SHARE;
  return (
    <tr>
      <th scope="row"><span className="timing__name">{name}</span><span className="timing__type">{type}</span></th>
      <td className="num tnum">{count(figures.entered)}</td>
      <td className="num tnum">{duration(figures.p50Ms)}</td>
      <td className="num tnum">{duration(figures.p95Ms)}</td>
      <td className="timing__track" aria-hidden="true"><span className={`timing__fill timing__fill--w${String(Math.round(share / 5) * 5)}${hot ? ' timing__fill--hot' : ''}`} /></td>
      <td className="num tnum">{count(figures.failed)}</td>
      <td className="num tnum">{count(figures.incidents)}</td>
    </tr>
  );
}
