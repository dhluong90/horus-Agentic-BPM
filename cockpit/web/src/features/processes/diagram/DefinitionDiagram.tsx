import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';
import { isKnownNodeType, type DiagramGraph } from '../model/bpmnGraph';
import {
  CAPTION_GAP,
  CAPTION_LINE_HEIGHT,
  EVENT_RADIUS,
  fitScale,
  GATEWAY_HALF,
  layoutGraph,
  MIN_SCALE,
  SYMBOL_SIZE,
  symbolCentre,
  TASK_TEXT,
  TYPE_INDENT,
  type PlacedEdge,
  type PlacedNode,
  type Point,
} from '../model/layout';
import { nodeIconKind, nodeTypeLabel } from '../model/nodeLabels';
import { NodeIcon } from './NodeIcon';

/*
 * The read-only definition diagram (CKP-069, CKP-070), in its own chunk with dagre so the first screen does not load
 * them (CKP-028). Drawn as SVG with the diagram tokens of the styleguide; agent steps carry the violet that means
 * "agent step" and nothing else. A node type this cockpit does not draw is a dashed box named in words. "Fit" scales
 * the drawing to the canvas, up or down, but never below 100 %, where the smallest text is 12 px.
 */

type Zoom = 'fit' | 'actual';

/** Space kept under the canvas inside the viewport: the card's padding and the page's bottom margin (as in processes.css). */
const BELOW_CANVAS = 52;
const MIN_CANVAS_HEIGHT = 240;
const LABEL_CHAR = 6.8;

function path(points: readonly Point[]): string {
  return points.map((p, index) => `${index === 0 ? 'M' : 'L'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ');
}

/** A short stroke across a default flow near its source, the BPMN mark for "default". */
function defaultMark(points: readonly Point[]): string | undefined {
  const [a, b] = points;
  if (!a || !b) return undefined;
  const length = Math.hypot(b.x - a.x, b.y - a.y) || 1;
  const ux = (b.x - a.x) / length;
  const uy = (b.y - a.y) / length;
  const cx = a.x + ux * 12;
  const cy = a.y + uy * 12;
  return `M${(cx - uy * 6 - ux * 3).toFixed(1)} ${(cy + ux * 6 - uy * 3).toFixed(1)} L${(cx + uy * 6 + ux * 3).toFixed(1)} ${(cy - ux * 6 + uy * 3).toFixed(1)}`;
}

function Edge({ edge, marker, defaultLabel }: { edge: PlacedEdge; marker: string; defaultLabel: string }) {
  const mark = edge.isDefault ? defaultMark(edge.points) : undefined;
  const at = edge.labelAt;
  const pill = edge.label !== undefined ? Math.ceil(edge.label.length * LABEL_CHAR) + 14 : 0;
  return (
    <g className="dg-edge" data-edge={edge.id}>
      <path d={path(edge.points)} markerEnd={`url(#${marker})`} />
      {mark !== undefined ? <path className="dg-edge__default" d={mark}><title>{defaultLabel}</title></path> : null}
      {edge.label !== undefined && at ? (
        <g className="dg-edge__label">
          <rect x={at.x - pill / 2} y={at.y - 10} width={pill} height={20} rx={10} />
          <text x={at.x} y={at.y + 4} textAnchor="middle">{edge.label}</text>
        </g>
      ) : null}
    </g>
  );
}

function TaskNode({ node, kind, label }: { node: PlacedNode; kind: string; label: string }) {
  const { padX, padY, nameLine, typeRow } = TASK_TEXT;
  const content = 2 * padY + node.lines.length * nameLine + node.typeLines.length * typeRow;
  const top = node.y + padY + Math.max(0, (node.height - content) / 2);
  const typeTop = top + node.lines.length * nameLine;
  return (
    <g className={`dg-node dg-node--${kind}`} data-node={node.id} data-type={node.type} tabIndex={0} role="img" aria-label={label}>
      <title>{label}</title>
      <rect x={node.x} y={node.y} width={node.width} height={node.height} rx={10} />
      <text className="dg-node__name" x={node.x + padX} y={top + 13}>
        {node.lines.map((line, index) => <tspan key={index} x={node.x + padX} dy={index === 0 ? 0 : nameLine}>{index < node.lines.length - 1 ? `${line} ` : line}</tspan>)}
      </text>
      <NodeIcon type={nodeIconKind(node.type)} x={node.x + padX} y={typeTop + 1} />
      <text className="dg-node__type" x={node.x + padX + TYPE_INDENT} y={typeTop + 13}>
        {node.typeLines.map((line, index) => <tspan key={index} x={node.x + padX + TYPE_INDENT} dy={index === 0 ? 0 : typeRow}>{index < node.typeLines.length - 1 ? `${line} ` : line}</tspan>)}
      </text>
    </g>
  );
}

function SymbolNode({ node, label }: { node: PlacedNode; label: string }) {
  const centre = symbolCentre(node);
  const glyph = node.type === 'exclusiveGateway' ? '×' : node.type === 'parallelGateway' ? '+' : node.type === 'inclusiveGateway' ? '○' : undefined;
  const intermediate = node.type.startsWith('intermediate');
  const captionTop = node.y + SYMBOL_SIZE + CAPTION_GAP;
  return (
    <g className={`dg-node dg-node--${node.shape} dg-node--${node.type.replace(':', '-')}`} data-node={node.id} data-type={node.type} tabIndex={0} role="img" aria-label={label}>
      <title>{label}</title>
      {node.shape === 'event' ? (
        <>
          <circle cx={centre.x} cy={centre.y} r={EVENT_RADIUS} />
          {intermediate ? <circle cx={centre.x} cy={centre.y} r={EVENT_RADIUS - 4} /> : null}
          {intermediate ? <NodeIcon type={nodeIconKind(node.type)} x={centre.x - 8} y={centre.y - 8} /> : null}
        </>
      ) : (
        <>
          <path d={`M${String(centre.x)} ${String(centre.y - GATEWAY_HALF)} L${String(centre.x + GATEWAY_HALF)} ${String(centre.y)} L${String(centre.x)} ${String(centre.y + GATEWAY_HALF)} L${String(centre.x - GATEWAY_HALF)} ${String(centre.y)} Z`} />
          {glyph !== undefined ? <text x={centre.x} y={centre.y + 7} textAnchor="middle" className="dg-node__glyph" aria-hidden="true">{glyph}</text> : null}
        </>
      )}
      <text className="dg-node__caption" x={centre.x} y={captionTop + 12} textAnchor="middle">
        {node.lines.map((line, index) => <tspan key={index} x={centre.x} dy={index === 0 ? 0 : CAPTION_LINE_HEIGHT}>{index < node.lines.length - 1 ? `${line} ` : line}</tspan>)}
      </text>
    </g>
  );
}

function Node({ node, typeLabel }: { node: PlacedNode; typeLabel: string }) {
  const known = isKnownNodeType(node.type);
  const label = `${node.name ?? node.id}, ${typeLabel}`;
  if (node.shape === 'task' || !known) {
    // A type this cockpit does not draw is laid out and drawn as a dashed box, named in words.
    const kind = node.type === 'serviceTask:agentTask' ? 'agent' : known ? 'task' : 'generic';
    return <TaskNode node={node} kind={kind} label={label} />;
  }
  return <SymbolNode node={node} label={label} />;
}

export default function DefinitionDiagram({ graph, label }: { graph: DiagramGraph; label: string }) {
  const { t } = useI18n();
  const marker = useId().replace(/:/g, '');
  const layout = useMemo(() => layoutGraph(graph, (type) => nodeTypeLabel(type, t)), [graph, t]);
  const [zoom, setZoom] = useState<Zoom>('fit');
  const frame = useRef<HTMLDivElement>(null);
  const [canvas, setCanvas] = useState<{ width: number; height: number } | undefined>(undefined);

  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    const measure = () => {
      const top = element.getBoundingClientRect().top + window.scrollY;
      setCanvas({ width: element.clientWidth, height: Math.max(MIN_CANVAS_HEIGHT, window.innerHeight - top - BELOW_CANVAS) });
    };
    measure();
    window.addEventListener('resize', measure);
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(measure);
    observer?.observe(element.parentElement ?? element);
    return () => {
      window.removeEventListener('resize', measure);
      observer?.disconnect();
    };
  }, []);

  const scale = zoom === 'fit' && canvas !== undefined ? fitScale(layout, canvas) : MIN_SCALE;
  const width = Math.ceil(layout.width * scale);
  const height = Math.ceil(layout.height * scale);
  return (
    <div className="dg">
      <div className="dg__tools seg seg--small" role="group" aria-label={t('diagram.zoom')}>
        <button type="button" className="hit" aria-pressed={zoom === 'fit'} onClick={() => { setZoom('fit'); }}>{t('diagram.fit')}</button>
        <button type="button" className="hit" aria-pressed={zoom === 'actual'} onClick={() => { setZoom('actual'); }}>{t('diagram.actual')}</button>
      </div>
      <div ref={frame} className="dg__frame">
        <svg className="dg__svg" role="group" aria-label={label} width={width} height={height}
          viewBox={`0 0 ${String(layout.width)} ${String(layout.height)}`} data-layout="auto" data-scale={scale.toFixed(2)}>
          <defs>
            <marker id={marker} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
              <path d="M0 0 L10 5 L0 10 z" className="dg-arrow" />
            </marker>
          </defs>
          <g>{layout.edges.map((edge) => <Edge key={edge.id} edge={edge} marker={marker} defaultLabel={t('diagram.defaultFlow')} />)}</g>
          <g>{layout.nodes.map((node) => <Node key={node.id} node={node} typeLabel={nodeTypeLabel(node.type, t)} />)}</g>
        </svg>
      </div>
    </div>
  );
}
