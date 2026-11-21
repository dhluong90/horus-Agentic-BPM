import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
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
  WHOLE_FIT_MIN_SCALE,
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
 * a definition to the canvas, up or down, but never below 100 %, where the smallest text is 12 px; a case (with an
 * overlay) is fitted whole, so every step and end is on screen, and "100 %" shows it at full size.
 */

type Zoom = 'fit' | 'actual';

/** A status badge on one step of an instance (CKP-017): its tone, icon and words, all decided by the caller. */
export interface StepBadge {
  tone: 'token' | 'incident' | 'done';
  icon: string;
  /** Drawn on the badge; empty for a done mark, which is an icon only. */
  text: string;
  /** Read by assistive technology with the step's name. */
  label: string;
}

/** What the instance view adds to the diagram: badges, the selected step and how to select one. */
export interface DiagramOverlay {
  badges: ReadonlyMap<string, StepBadge>;
  selected: string | undefined;
  onSelect: (nodeId: string) => void;
  /** Height kept free under the canvas inside the viewport, for the step panel below the drawing. */
  reserveBelow: number;
}

const BADGE_HEIGHT = 20;
const BADGE_CHAR = 6.6;
/** A done mark stays a disc of at least 23 px on screen at any scale Fit picks, drawn clear of the box's border. */
const DONE_SCREEN_RADIUS = 11.5;
const DONE_MIN_RADIUS = 12;
const DONE_MAX_RADIUS = 16;
const DONE_CLEARANCE = 4;

function doneRadius(scale: number): number {
  return Math.min(DONE_MAX_RADIUS, Math.max(DONE_MIN_RADIUS, DONE_SCREEN_RADIUS / scale));
}

function badgeWidth(badge: StepBadge): number {
  return Math.ceil(badge.text.length * BADGE_CHAR) + 32;
}

/** A badge across the top edge of a box, or over the symbol of an event or gateway. */
function Badge({ node, badge, scale }: { node: PlacedNode; badge: StepBadge; scale: number }) {
  if (badge.tone === 'done') {
    const radius = doneRadius(scale);
    // Above a box's top-right corner and above a symbol, clear of the border and of the flows that leave on the right.
    const cx = node.shape === 'task' ? node.x + node.width - radius - 2 : symbolCentre(node).x;
    const cy = node.y - radius - DONE_CLEARANCE;
    return (
      <g className="dg-badge dg-badge--done" aria-hidden="true" transform={`translate(${cx.toFixed(1)} ${cy.toFixed(1)}) scale(${(radius / DONE_MIN_RADIUS).toFixed(3)})`}>
        <circle cx={0} cy={0} r={DONE_MIN_RADIUS} />
        <text x={0} y={5} textAnchor="middle">{badge.icon}</text>
      </g>
    );
  }
  const width = badgeWidth(badge);
  const x = node.shape === 'task' ? node.x + 6 : symbolCentre(node).x - width / 2;
  // Above the box, clear of the name; the layout leaves a row gap for it.
  const y = node.y - BADGE_HEIGHT - 4;
  return (
    <g className={`dg-badge dg-badge--${badge.tone}`} aria-hidden="true">
      <rect x={x} y={y} width={width} height={BADGE_HEIGHT} rx={BADGE_HEIGHT / 2} />
      <text x={x + 10} y={y + 14}>{badge.icon}</text>
      <text x={x + 24} y={y + 14}>{badge.text}</text>
    </g>
  );
}

/** Space kept under the canvas inside the viewport: the card's padding and the page's bottom margin (as in processes.css). */
const BELOW_CANVAS = 52;
const MIN_FIT_HEIGHT = 200;
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

interface Interaction {
  selected: boolean;
  onSelect: (() => void) | undefined;
}

function interactive({ selected, onSelect }: Interaction) {
  if (onSelect === undefined) return { role: 'img' as const };
  return {
    role: 'button' as const,
    'aria-pressed': selected,
    onClick: onSelect,
    onKeyDown: (event: KeyboardEvent<SVGGElement>) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        onSelect();
      }
    },
  };
}

function TaskNode({ node, kind, label, interaction }: { node: PlacedNode; kind: string; label: string; interaction: Interaction }) {
  const { padX, padY, nameLine, typeRow } = TASK_TEXT;
  const content = 2 * padY + node.lines.length * nameLine + node.typeLines.length * typeRow;
  const top = node.y + padY + Math.max(0, (node.height - content) / 2);
  const typeTop = top + node.lines.length * nameLine;
  return (
    <g className={`dg-node dg-node--${kind}${interaction.selected ? ' dg-node--selected' : ''}${interaction.onSelect ? ' dg-node--pick' : ''}`} data-node={node.id} data-type={node.type} tabIndex={0} aria-label={label} {...interactive(interaction)}>
      <title>{label}</title>
      <rect x={node.x} y={node.y} width={node.width} height={node.height} rx={10} />
      <text aria-hidden="true" className="dg-node__name" x={node.x + padX} y={top + 13}>
        {node.lines.map((line, index) => <tspan key={index} x={node.x + padX} dy={index === 0 ? 0 : nameLine}>{`${line} `}</tspan>)}
      </text>
      <NodeIcon type={nodeIconKind(node.type)} x={node.x + padX} y={typeTop + 1} />
      <text aria-hidden="true" className="dg-node__type" x={node.x + padX + TYPE_INDENT} y={typeTop + 13}>
        {node.typeLines.map((line, index) => <tspan key={index} x={node.x + padX + TYPE_INDENT} dy={index === 0 ? 0 : typeRow}>{index < node.typeLines.length - 1 ? `${line} ` : line}</tspan>)}
      </text>
    </g>
  );
}

function SymbolNode({ node, label, interaction }: { node: PlacedNode; label: string; interaction: Interaction }) {
  const centre = symbolCentre(node);
  const glyph = node.type === 'exclusiveGateway' ? '×' : node.type === 'parallelGateway' ? '+' : node.type === 'inclusiveGateway' ? '○' : undefined;
  const intermediate = node.type.startsWith('intermediate');
  const captionTop = node.y + SYMBOL_SIZE + CAPTION_GAP;
  return (
    <g className={`dg-node dg-node--${node.shape} dg-node--${node.type.replace(':', '-')}${interaction.selected ? ' dg-node--selected' : ''}${interaction.onSelect ? ' dg-node--pick' : ''}`} data-node={node.id} data-type={node.type} tabIndex={0} aria-label={label} {...interactive(interaction)}>
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
      <text aria-hidden="true" className="dg-node__caption" x={centre.x} y={captionTop + 12} textAnchor="middle">
        {node.lines.map((line, index) => <tspan key={index} x={centre.x} dy={index === 0 ? 0 : CAPTION_LINE_HEIGHT}>{index < node.lines.length - 1 ? `${line} ` : line}</tspan>)}
      </text>
    </g>
  );
}

function Node({ node, typeLabel, badge, interaction }: { node: PlacedNode; typeLabel: string; badge: StepBadge | undefined; interaction: Interaction }) {
  const known = isKnownNodeType(node.type);
  // A selectable step's name starts with the words drawn on it, in order, so speech input can name it (WCAG 2.5.3).
  const label = interaction.onSelect !== undefined
    ? `${node.name ?? node.id} ${typeLabel}${badge !== undefined ? `, ${badge.label}` : ''}`
    : `${node.name ?? node.id}, ${typeLabel}`;
  if (node.shape === 'task' || !known) {
    // A type this cockpit does not draw is laid out and drawn as a dashed box, named in words.
    const kind = node.type === 'serviceTask:agentTask' ? 'agent' : known ? 'task' : 'generic';
    return <TaskNode node={node} kind={kind} label={label} interaction={interaction} />;
  }
  return <SymbolNode node={node} label={label} interaction={interaction} />;
}

export default function DefinitionDiagram({ graph, label, overlay }: { graph: DiagramGraph; label: string; overlay?: DiagramOverlay | undefined }) {
  const { t } = useI18n();
  const marker = useId().replace(/:/g, '');
  const withBadges = overlay !== undefined;
  const layout = useMemo(() => layoutGraph(graph, (type) => nodeTypeLabel(type, t), { badges: withBadges }), [graph, t, withBadges]);
  const [zoom, setZoom] = useState<Zoom>('fit');
  const frame = useRef<HTMLDivElement>(null);
  const [canvas, setCanvas] = useState<{ width: number; height: number } | undefined>(undefined);
  const reserveBelow = overlay?.reserveBelow ?? 0;

  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    const measure = () => {
      const top = element.getBoundingClientRect().top + window.scrollY;
      const free = window.innerHeight - top - BELOW_CANVAS - reserveBelow;
      setCanvas({ width: element.clientWidth, height: Math.max(reserveBelow > 0 ? MIN_FIT_HEIGHT : MIN_CANVAS_HEIGHT, free) });
    };
    measure();
    window.addEventListener('resize', measure);
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(measure);
    observer?.observe(element.parentElement ?? element);
    return () => {
      window.removeEventListener('resize', measure);
      observer?.disconnect();
    };
  }, [reserveBelow]);

  const scale = zoom === 'fit' && canvas !== undefined ? fitScale(layout, canvas, undefined, withBadges ? WHOLE_FIT_MIN_SCALE : MIN_SCALE) : MIN_SCALE;
  const selected = overlay?.selected;
  useEffect(() => {
    // Keep the selected step in view when the drawing is wider than its canvas.
    const element = frame.current;
    const placed = selected !== undefined ? layout.nodes.find((item) => item.id === selected) : undefined;
    if (!element || !placed) return;
    const centreX = (placed.x + placed.width / 2) * scale;
    const centreY = (placed.y + placed.height / 2) * scale;
    const left = centreX - element.clientWidth / 2;
    const top = centreY - element.clientHeight / 2;
    if (centreX < element.scrollLeft + 40 || centreX > element.scrollLeft + element.clientWidth - 40) element.scrollLeft = Math.max(0, left);
    if (centreY < element.scrollTop + 40 || centreY > element.scrollTop + element.clientHeight - 40) element.scrollTop = Math.max(0, top);
  }, [selected, layout, scale]);
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
          <g>{layout.nodes.map((node) => (
            <Node key={node.id} node={node} typeLabel={nodeTypeLabel(node.type, t)} badge={overlay?.badges.get(node.id)}
              interaction={{ selected: overlay?.selected === node.id, onSelect: overlay ? () => { overlay.onSelect(node.id); } : undefined }} />
          ))}</g>
          {overlay !== undefined ? (
            <g>{layout.nodes.map((node) => {
              const badge = overlay.badges.get(node.id);
              return badge !== undefined ? <Badge key={node.id} node={node} badge={badge} scale={scale} /> : null;
            })}</g>
          ) : null}
        </svg>
      </div>
    </div>
  );
}
