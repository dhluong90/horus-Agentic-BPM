import { useCallback, useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import { useI18n } from '../../../i18n/I18nProvider';
import { ancestorsOf, isKnownNodeType, type DiagramGraph, type LoopKind } from '../model/bpmnGraph';
import {
  CAPTION_GAP,
  CAPTION_LINE_HEIGHT,
  EVENT_RADIUS,
  fitScale,
  FRAME_HEADER,
  FRAME_PAD,
  GATEWAY_HALF,
  layoutGraph,
  MIN_SCALE,
  READABLE_SCALE,
  WHOLE_FIT_MIN_SCALE,
  SYMBOL_SIZE,
  symbolCentre,
  CLOSED_CONTAINER_EXTRA,
  TASK_TEXT,
  TYPE_INDENT,
  type PlacedDataItem,
  type PlacedDataLink,
  type PlacedEdge,
  type PlacedNode,
  type Point,
} from '../model/layout';
import { edgeKey, type DiagramMarks, type Mark } from '../model/graphDiff';
import { nodeIconKind, nodeTypeLabel } from '../model/nodeLabels';
import { placeCollaboration, POOL_HEADER, type CollaborationDrawing } from '../model/collaborationLayout';
import { Minimap, type View } from './Minimap';
import { NodeIcon } from './NodeIcon';

/*
 * The read-only definition diagram (CKP-069, CKP-070), in its own chunk with dagre so the first screen does not load
 * them (CKP-028). Drawn as SVG with the diagram tokens of the styleguide; agent steps carry the violet that means
 * "agent step" and nothing else. A node type this cockpit does not draw is a dashed box named in words. "Fit" scales
 * the drawing to the canvas, up or down, but never below 100 %, where the smallest text is 12 px. A drawing larger
 * than its canvas at that floor opens centred on the selected step, pans by drag and by the arrow keys, and shows a
 * minimap of the part in view; a case also offers "Whole case", an overview that may draw below the floor.
 */

type Zoom = 'fit' | 'actual' | 'whole';

/** How far one arrow key press pans the canvas, in screen pixels; with Shift, three times as far. */
const PAN_STEP = 80;

/** The least scale "Fit" may reach when it must show the whole drawing: the text is small, and Actual zooms in. */
const FIT_ALL_MIN_SCALE = 0.2;

/** A status badge on one step of an instance (CKP-017): its tone, icon and words, all decided by the caller. */
export interface StepBadge {
  tone: 'token' | 'incident' | 'undone' | 'done' | 'warn';
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
const MIN_CANVAS_HEIGHT = 240;
const LABEL_CHAR = 6.8;

function floorOf(withBadges: boolean): number {
  return withBadges ? READABLE_SCALE : MIN_SCALE;
}

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

/** The glyph that names a mark beside its colour: a diff never rests on colour alone (WCAG 1.4.1). */
const MARK_GLYPH: Record<Mark, string> = { added: '+', changed: '•', removed: '−' };

function MarkBadge({ mark, x, y }: { mark: Mark; x: number; y: number }) {
  return (
    <g className={`dg-mark dg-mark--${mark}`} aria-hidden="true">
      <circle cx={x} cy={y} r={9} />
      <text x={x} y={y + 4.5} textAnchor="middle">{MARK_GLYPH[mark]}</text>
    </g>
  );
}

/** The pool frame, the lane bands and the black-box pools behind the steps (#497). */
function Collaboration({ drawing }: { drawing: CollaborationDrawing }) {
  return (
    <g className="dg-collab" data-collaboration="true">
      {drawing.pool !== undefined ? (
        <g className="dg-pool" data-pool={drawing.pool.id}>
          <rect x={drawing.pool.x} y={drawing.pool.y} width={drawing.pool.width} height={drawing.pool.height} rx={4} />
          <text className="dg-pool__name" x={drawing.pool.x + 12} y={drawing.pool.y + POOL_HEADER - 9}>{drawing.pool.label}</text>
        </g>
      ) : null}
      {drawing.lanes.map((lane) => (
        <g key={lane.id} className="dg-lane" data-lane={lane.id}>
          <rect x={lane.x} y={lane.y} width={lane.width} height={lane.height} rx={6} />
          <text className="dg-lane__name" x={lane.x + 10} y={lane.y + 14}>{lane.label}</text>
        </g>
      ))}
      {drawing.blackBoxes.map((box) => (
        <g key={box.id} className="dg-pool dg-pool--black-box" data-pool={box.id}>
          <rect x={box.x} y={box.y} width={box.width} height={box.height} rx={4} />
          <text className="dg-pool__name" x={box.x + box.width / 2} y={box.y + box.height / 2 + 4} textAnchor="middle">{box.label}</text>
        </g>
      ))}
    </g>
  );
}

/** A message flow: a dashed line with an arrow and, when it has one, a label (#497). */
function MessageFlowLine({ flow, marker }: { flow: { id: string; label: string | undefined; points: readonly Point[]; labelAt: Point }; marker: string }) {
  const pill = flow.label !== undefined ? Math.ceil(flow.label.length * LABEL_CHAR) + 14 : 0;
  return (
    <g className="dg-edge dg-edge--message" data-message-flow={flow.id}>
      <path d={path(flow.points)} markerEnd={`url(#${marker})`} />
      {flow.label !== undefined ? (
        <g className="dg-edge__label">
          <rect x={flow.labelAt.x - pill / 2} y={flow.labelAt.y - 10} width={pill} height={20} rx={10} />
          <text x={flow.labelAt.x} y={flow.labelAt.y + 4} textAnchor="middle">{flow.label}</text>
        </g>
      ) : null}
    </g>
  );
}

function Edge({ edge, marker, defaultLabel, mark }: { edge: PlacedEdge; marker: string; defaultLabel: string; mark: Mark | undefined }) {
  const tick = edge.isDefault ? defaultMark(edge.points) : undefined;
  const at = edge.labelAt;
  const pill = edge.label !== undefined ? Math.ceil(edge.label.length * LABEL_CHAR) + 14 : 0;
  return (
    <g className={`${mark !== undefined ? `dg-edge dg-edge--${mark}` : 'dg-edge'}${edge.isLink === true ? ' dg-edge--link' : ''}${edge.isAssociation === true ? ' dg-edge--association' : ''}`} data-edge={edge.id} {...(mark !== undefined ? { 'data-mark': mark } : {})}>
      <path d={path(edge.points)} markerEnd={`url(#${marker}${mark !== undefined ? `-${mark}` : ''})`} />
      {tick !== undefined ? <path className="dg-edge__default" d={tick}><title>{defaultLabel}</title></path> : null}
      {edge.label !== undefined && at ? (
        <g className="dg-edge__label">
          <rect x={at.x - pill / 2} y={at.y - 10} width={pill} height={20} rx={10} />
          <text x={at.x} y={at.y + 4} textAnchor="middle">{edge.label}</text>
        </g>
      ) : null}
    </g>
  );
}

/**
 * A data object (a page with a folded corner) or a data store (a drum), BPMN's own shapes (#499). Read only: it takes
 * no focus and no click, but names itself to assistive technology as "Data object: Applicant profile".
 */
function DataSymbol({ item, kindLabel }: { item: PlacedDataItem; kindLabel: string }) {
  const { x, y, width: w, height: h } = item;
  const centre = x + w / 2;
  const name = item.name ?? item.id;
  return (
    <g className={`dg-data dg-data--${item.kind}`} data-data={item.id} data-kind={item.kind} role="img" aria-label={`${kindLabel}: ${name}`}>
      <title>{`${kindLabel}: ${name}`}</title>
      {item.kind === 'dataObject' ? (
        <>
          <path d={`M${String(x)} ${String(y)} L${String(x + w - 9)} ${String(y)} L${String(x + w)} ${String(y + 9)} L${String(x + w)} ${String(y + h)} L${String(x)} ${String(y + h)} Z`} />
          <path className="dg-data__fold" d={`M${String(x + w - 9)} ${String(y)} L${String(x + w - 9)} ${String(y + 9)} L${String(x + w)} ${String(y + 9)}`} />
        </>
      ) : (
        <>
          <path d={`M${String(x)} ${String(y + 6)} L${String(x)} ${String(y + h - 6)} A${String(w / 2)} 6 0 0 0 ${String(x + w)} ${String(y + h - 6)} L${String(x + w)} ${String(y + 6)} A${String(w / 2)} 6 0 0 0 ${String(x)} ${String(y + 6)} Z`} />
          <path className="dg-data__fold" d={`M${String(x)} ${String(y + 6)} A${String(w / 2)} 6 0 0 0 ${String(x + w)} ${String(y + 6)} M${String(x)} ${String(y + 13)} A${String(w / 2)} 6 0 0 0 ${String(x + w)} ${String(y + 13)}`} />
        </>
      )}
      <text aria-hidden="true" className="dg-node__caption" x={centre} y={y + h + 16} textAnchor="middle">
        {item.lines.map((line, index) => <tspan key={index} x={centre} dy={index === 0 ? 0 : CAPTION_LINE_HEIGHT}>{line}</tspan>)}
      </text>
    </g>
  );
}

/** The dotted line of a data association; its arrowhead points at where the data goes (a step for a read, a symbol for a write). */
function DataLine({ link, marker }: { link: PlacedDataLink; marker: string }) {
  return (
    <g className={`dg-edge dg-edge--data dg-edge--${link.direction}`} data-data-link={link.id} data-direction={link.direction}>
      <path d={path(link.points)} markerEnd={`url(#${marker})`} />
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

/** The node's classes and data attributes for its diff mark and its selection in a change list. */
function markProps(mark: Mark | undefined, picked: boolean) {
  return {
    className: `${mark !== undefined ? ` dg-node--${mark}` : ''}${picked ? ' is-selected' : ''}`,
    data: mark !== undefined ? { 'data-mark': mark } : {},
  };
}

interface NodeProps { node: PlacedNode; label: string; interaction: Interaction; mark: Mark | undefined; picked: boolean }

function TaskNode({ node, kind, label, interaction, mark, picked, steps, onToggle, toggleLabel }: NodeProps & { kind: string; steps?: string | undefined; onToggle?: (() => void) | undefined; toggleLabel?: string | undefined }) {
  const { padX, padY, nameLine, typeRow } = TASK_TEXT;
  const content = 2 * padY + node.lines.length * nameLine + node.typeLines.length * typeRow;
  // A closed sub-process keeps a row at the foot of its box for the count of its steps and the control that opens it.
  const reserve = steps !== undefined ? CLOSED_CONTAINER_EXTRA : 0;
  const top = node.y + padY + Math.max(0, (node.height - reserve - content) / 2);
  const typeTop = top + node.lines.length * nameLine;
  const extra = markProps(mark, picked);
  return (
    <g className={`dg-node dg-node--${kind}${interaction.selected ? ' dg-node--selected' : ''}${interaction.onSelect ? ' dg-node--pick' : ''}${extra.className}`} data-node={node.id} data-type={node.type} tabIndex={0} aria-label={label} {...interactive(interaction)} {...extra.data}>
      <title>{label}</title>
      <rect x={node.x} y={node.y} width={node.width} height={node.height} rx={10} />
      <text aria-hidden="true" className="dg-node__name" x={node.x + padX} y={top + 13}>
        {node.lines.map((line, index) => <tspan key={index} x={node.x + padX} dy={index === 0 ? 0 : nameLine}>{`${line} `}</tspan>)}
      </text>
      <NodeIcon type={nodeIconKind(node.type)} x={node.x + padX} y={typeTop + 1} />
      <text aria-hidden="true" className="dg-node__type" x={node.x + padX + TYPE_INDENT} y={typeTop + 13}>
        {node.typeLines.map((line, index) => <tspan key={index} x={node.x + padX + TYPE_INDENT} dy={index === 0 ? 0 : typeRow}>{index < node.typeLines.length - 1 ? `${line} ` : line}</tspan>)}
      </text>
      {node.loop !== undefined ? <LoopMarker kind={node.loop} cx={node.x + node.width / 2} cy={node.y + node.height - 11} /> : null}
      <TriggerMark node={node} x={node.x + node.width - 28} y={node.y + 4} />
      {steps !== undefined ? <text aria-hidden="true" className="dg-node__steps" x={node.x + padX} y={node.y + node.height - 9}>{steps}</text> : null}
      {mark !== undefined ? <MarkBadge mark={mark} x={node.x + node.width - 2} y={node.y + 2} /> : null}
      {onToggle !== undefined && toggleLabel !== undefined ? <Toggle x={node.x + node.width - 26} y={node.y + node.height - 24} open={false} label={toggleLabel} onToggle={onToggle} /> : null}
    </g>
  );
}

/**
 * The BPMN marker of a step that repeats (#496), centred on the foot of its box: three vertical bars for a parallel
 * multi-instance step, three horizontal bars for a sequential one, a circular arrow for a standard loop. The words
 * are in the step's accessible name; the marker itself is decoration and never the only sign.
 */
function LoopMarker({ kind, cx, cy }: { kind: LoopKind; cx: number; cy: number }) {
  const half = 7;
  const path = kind === 'parallel'
    ? [-4, 0, 4].map((dx) => `M${(cx + dx).toFixed(1)} ${(cy - half).toFixed(1)}V${(cy + half).toFixed(1)}`).join('')
    : kind === 'sequential'
      ? [-4, 0, 4].map((dy) => `M${(cx - half).toFixed(1)} ${(cy + dy).toFixed(1)}H${(cx + half).toFixed(1)}`).join('')
      : `M${(cx + 6).toFixed(1)} ${(cy - 2).toFixed(1)}A6.5 6.5 0 1 0 ${(cx + 4).toFixed(1)} ${(cy + 5).toFixed(1)}M${(cx + 6).toFixed(1)} ${(cy - 7).toFixed(1)}V${(cy - 2).toFixed(1)}H${(cx + 1).toFixed(1)}`;
  return <path className={`dg-loop dg-loop--${kind}`} data-loop={kind} aria-hidden="true" d={path} />;
}

/** The control that opens or closes a sub-process in place: a small square with a plus or a minus, reachable by keyboard. */
function Toggle({ x, y, open, label, onToggle }: { x: number; y: number; open: boolean; label: string; onToggle: () => void }) {
  return (
    <g className="dg-toggle" role="button" tabIndex={0} aria-expanded={open} aria-label={label}
      onClick={(event) => { event.stopPropagation(); onToggle(); }}
      onKeyDown={(event: KeyboardEvent<SVGGElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          event.stopPropagation();
          onToggle();
        }
      }}>
      <title>{label}</title>
      <rect x={x} y={y} width={18} height={18} rx={4} />
      <path d={open ? `M${String(x + 5)} ${String(y + 9)}H${String(x + 13)}` : `M${String(x + 5)} ${String(y + 9)}H${String(x + 13)}M${String(x + 9)} ${String(y + 5)}V${String(y + 13)}`} />
    </g>
  );
}

/** The start symbol of an event sub-process, in the corner of its box or frame: what starts it, ringed dashed when it does not interrupt (#487). */
function TriggerMark({ node, x, y }: { node: PlacedNode; x: number; y: number }) {
  if (node.type !== 'subProcess:event' || node.trigger === undefined) return null;
  const kind = `startEvent:${node.trigger}`;
  if (!isKnownNodeType(kind)) return null;
  return (
    <g className="dg-trigger" aria-hidden="true" data-trigger={node.trigger} data-interrupting={String(node.interrupting !== false)}>
      <circle cx={x + 10} cy={y + 10} r={11} strokeDasharray={node.interrupting === false ? '3 2.5' : undefined} />
      <NodeIcon type={kind} x={x + 2} y={y + 2} />
    </g>
  );
}

/** An open sub-process: a frame with its name, drawn behind its steps. */
function Frame({ node, label, interaction, onToggle, toggleLabel }: { node: PlacedNode; label: string; interaction: Interaction; onToggle: () => void; toggleLabel: string }) {
  return (
    <g className={`dg-frame${interaction.selected ? ' dg-frame--selected' : ''}${interaction.onSelect ? ' dg-node--pick' : ''}`} data-node={node.id} data-type={node.type} data-open="true" tabIndex={0} aria-label={label} {...interactive(interaction)}>
      <title>{label}</title>
      <rect x={node.x} y={node.y} width={node.width} height={node.height} rx={12} />
      <text aria-hidden="true" className="dg-frame__name" x={node.x + FRAME_PAD} y={node.y + FRAME_HEADER - 11}>{node.name ?? node.id}</text>
      {node.loop !== undefined ? <LoopMarker kind={node.loop} cx={node.x + node.width - FRAME_PAD - 38} cy={node.y + 15} /> : null}
      <TriggerMark node={node} x={node.x + node.width - FRAME_PAD - 18 - 30} y={node.y + 2} />
      <Toggle x={node.x + node.width - FRAME_PAD - 18} y={node.y + 6} open label={toggleLabel} onToggle={onToggle} />
    </g>
  );
}

function SymbolNode({ node, label, interaction, mark, picked }: NodeProps) {
  const centre = symbolCentre(node);
  const glyph = node.type === 'exclusiveGateway' ? '×' : node.type === 'parallelGateway' ? '+' : node.type === 'inclusiveGateway' ? '○' : node.type === 'complexGateway' ? '✳' : undefined;
  // #489, #493: a boundary event sits on its activity with a double ring, dashed when it does not interrupt; an end event
  // that raises or terminates carries its symbol inside the thick ring.
  const boundary = node.type.startsWith('boundaryEvent');
  const intermediate = node.type.startsWith('intermediate') || boundary;
  const soft = (boundary || node.type.startsWith('startEvent')) && node.interrupting === false;
  const marked = intermediate || node.type.includes(':');
  const captionTop = node.y + SYMBOL_SIZE + CAPTION_GAP;
  const extra = markProps(mark, picked);
  return (
    <g className={`dg-node dg-node--${node.shape} dg-node--${node.type.replace(':', '-')}${soft ? ' dg-node--soft' : ''}${interaction.selected ? ' dg-node--selected' : ''}${interaction.onSelect ? ' dg-node--pick' : ''}${extra.className}`} data-node={node.id} data-type={node.type} {...(boundary || node.type.startsWith('startEvent') ? { 'data-interrupting': String(!soft) } : {})} tabIndex={0} aria-label={label} {...interactive(interaction)} {...extra.data}>
      <title>{label}</title>
      {node.shape === 'event' ? (
        <>
          <circle cx={centre.x} cy={centre.y} r={EVENT_RADIUS} />
          {intermediate ? <circle cx={centre.x} cy={centre.y} r={EVENT_RADIUS - 4} /> : null}
          {marked ? <NodeIcon type={nodeIconKind(node.type)} x={centre.x - 8} y={centre.y - 8} /> : null}
        </>
      ) : (
        <>
          <path d={`M${String(centre.x)} ${String(centre.y - GATEWAY_HALF)} L${String(centre.x + GATEWAY_HALF)} ${String(centre.y)} L${String(centre.x)} ${String(centre.y + GATEWAY_HALF)} L${String(centre.x - GATEWAY_HALF)} ${String(centre.y)} Z`} />
          {glyph !== undefined ? <text x={centre.x} y={centre.y + 7} textAnchor="middle" className="dg-node__glyph" aria-hidden="true">{glyph}</text> : null}
          {node.type === 'eventBasedGateway' ? (
            // #495: a double ring around a pentagon, the symbol of a gateway that waits for the first of several events.
            <g className="dg-node__event-gateway" aria-hidden="true">
              <circle cx={centre.x} cy={centre.y} r={11} />
              <circle cx={centre.x} cy={centre.y} r={8.5} />
              <path d={`M${String(centre.x)} ${String(centre.y - 5.5)} L${String(centre.x + 5.2)} ${String(centre.y - 1.7)} L${String(centre.x + 3.2)} ${String(centre.y + 4.5)} L${String(centre.x - 3.2)} ${String(centre.y + 4.5)} L${String(centre.x - 5.2)} ${String(centre.y - 1.7)} Z`} />
            </g>
          ) : null}
        </>
      )}
      {node.lines.length > 0 ? (
        <text aria-hidden="true" className="dg-node__caption" x={centre.x} y={captionTop + 12} textAnchor="middle">
          {node.lines.map((line, index) => <tspan key={index} x={centre.x} dy={index === 0 ? 0 : CAPTION_LINE_HEIGHT}>{index < node.lines.length - 1 ? `${line} ` : line}</tspan>)}
        </text>
      ) : null}
      {mark !== undefined ? <MarkBadge mark={mark} x={centre.x + 20} y={centre.y - 20} /> : null}
    </g>
  );
}

function Node({ node, typeLabel, loopLabel, badge, interaction, mark, markLabel, picked, steps, onToggle, toggleLabel }: {
  node: PlacedNode; typeLabel: string; loopLabel: string | undefined; badge: StepBadge | undefined; interaction: Interaction;
  mark: Mark | undefined; markLabel: ((mark: Mark) => string) | undefined; picked: boolean;
  steps: string | undefined; onToggle: (() => void) | undefined; toggleLabel: string | undefined;
}) {
  const known = isKnownNodeType(node.type);
  // A selectable step's name starts with the words drawn on it, in order, so speech input can name it (WCAG 2.5.3).
  const label = interaction.onSelect !== undefined
    ? `${node.name ?? node.id} ${typeLabel}${[loopLabel, badge?.label, mark !== undefined && markLabel !== undefined ? markLabel(mark) : undefined].filter((part) => part !== undefined).map((part) => `, ${part}`).join('')}`
    : `${node.name ?? node.id}, ${typeLabel}${loopLabel !== undefined ? `, ${loopLabel}` : ''}${mark !== undefined && markLabel !== undefined ? `, ${markLabel(mark)}` : ''}`;
  if (node.shape === 'task' || !known) {
    // A type this cockpit does not draw is laid out and drawn as a dashed box, named in words.
    const kind = node.type === 'serviceTask:agentTask' ? 'agent' : known ? 'task' : 'generic';
    return <TaskNode node={node} kind={kind} label={label} interaction={interaction} mark={mark} picked={picked} steps={steps} onToggle={onToggle} toggleLabel={toggleLabel} />;
  }
  return <SymbolNode node={node} label={label} interaction={interaction} mark={mark} picked={picked} />;
}

/**
 * `overlay` draws a case on this diagram (CKP-017): badges and a selectable step. `marks` draws a change (CKP-024):
 * added, changed and removed steps and links, each in its colour with its glyph, and named in the step's accessible
 * name through `markLabel`; `selected` outlines one step of the change list. `toolbar` sits left of the zoom control;
 * `reserve` is the room kept under the canvas, for a docked decision; `fill` makes the canvas take that height, so the
 * change list beside it keeps the same frame.
 */
export default function DefinitionDiagram({ graph, label, overlay, marks, markLabel, selected: picked, toolbar, reserve = BELOW_CANVAS, fill = false, fitAll = false, sharedScale, onFitAll }: {
  graph: DiagramGraph;
  label: string;
  overlay?: DiagramOverlay | undefined;
  marks?: DiagramMarks | undefined;
  markLabel?: ((mark: Mark) => string) | undefined;
  selected?: string | undefined;
  toolbar?: ReactNode;
  reserve?: number;
  fill?: boolean;
  /**
   * "Fit" shows the whole drawing, even below 100 %, so nothing is cropped on opening (CKP-027). `sharedScale` caps
   * that scale, and `onFitAll` reports it, so two diagrams side by side can be drawn at the smaller of the two.
   */
  fitAll?: boolean;
  sharedScale?: number | undefined;
  onFitAll?: ((scale: number) => void) | undefined;
}) {
  const { t } = useI18n();
  const marker = useId().replace(/:/g, '');
  const noteId = useId();
  const withBadges = overlay !== undefined;
  // A sub-process opens by itself when a step inside it carries a badge or is the selected step, so a case never hides
  // where it is; the reader's own choice, made with the control on the box, wins over that.
  const [toggled, setToggled] = useState<ReadonlyMap<string, boolean>>(() => new Map());
  const wanted = [...(overlay?.badges.keys() ?? []), ...(overlay?.selected !== undefined ? [overlay.selected] : []), ...(picked !== undefined ? [picked] : [])].join('\u0000');
  const open = useMemo(() => {
    const out = new Set<string>();
    for (const id of wanted === '' ? [] : wanted.split('\u0000')) ancestorsOf(graph, id).forEach((node) => out.add(node.id));
    toggled.forEach((value, id) => { if (value) out.add(id); else out.delete(id); });
    return out;
  }, [graph, wanted, toggled]);
  const toggle = useCallback((id: string) => {
    setToggled((current) => new Map(current).set(id, !open.has(id)));
  }, [open]);
  const plain = useMemo(() => layoutGraph(graph, (type) => nodeTypeLabel(type, t), { badges: withBadges, open }), [graph, t, withBadges, open]);
  // #497: pools, lanes and message flows are drawn around the laid-out steps, read only.
  const collaboration = useMemo(() => placeCollaboration(plain, graph), [plain, graph]);
  const layout = collaboration?.layout ?? plain;
  const [zoom, setZoom] = useState<Zoom>('fit');
  const frame = useRef<HTMLDivElement>(null);
  const [canvas, setCanvas] = useState<{ width: number; height: number } | undefined>(undefined);
  const [view, setView] = useState<View | undefined>(undefined);

  useEffect(() => {
    const element = frame.current;
    if (!element) return;
    const measure = () => {
      const top = element.getBoundingClientRect().top + window.scrollY;
      const height = Math.max(MIN_CANVAS_HEIGHT, window.innerHeight - top - reserve);
      // Through the CSSOM: the cockpit's CSP allows no inline style attribute.
      if (fill) element.style.height = `${String(height)}px`;
      setCanvas({ width: element.clientWidth, height });
    };
    measure();
    window.addEventListener('resize', measure);
    const observer = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(measure);
    observer?.observe(element.parentElement ?? element);
    // The canvas also moves when something above it grows after the first measure: a toolbar note, a wrapped
    // legend, a header that loads late. The diagram block and the page are watched, so the canvas keeps its
    // room above the docked decision (#443).
    const block = element.closest('.dg');
    if (block !== null) observer?.observe(block);
    observer?.observe(document.body);
    return () => {
      window.removeEventListener('resize', measure);
      observer?.disconnect();
    };
  }, [reserve, fill]);

  const whole = canvas !== undefined ? fitScale(layout, canvas, undefined, fitAll ? FIT_ALL_MIN_SCALE : WHOLE_FIT_MIN_SCALE) : MIN_SCALE;
  // A case draws no smaller than READABLE_SCALE, where its smallest text is 13 px; a definition keeps the 12 px floor.
  const floor = floorOf(withBadges);
  const fit = fitAll ? Math.min(whole, sharedScale ?? whole) : canvas !== undefined ? fitScale(layout, canvas, undefined, floor) : floor;
  useEffect(() => {
    if (fitAll && canvas !== undefined) onFitAll?.(whole);
  }, [fitAll, canvas, whole, onFitAll]);
  // "Whole case" (a case) or "Whole process" (a change) is offered only when the drawing does not fit at the 12 px
  // floor, so no edge of a change is ever out of reach (#443).
  const offerWhole = !fitAll && (withBadges || marks !== undefined) && whole < floorOf(withBadges);
  const mode: Zoom = zoom === 'whole' && !offerWhole ? 'fit' : zoom;
  const scale = mode === 'fit' ? fit : mode === 'whole' ? whole : MIN_SCALE;
  const width = Math.ceil(layout.width * scale);
  const height = Math.ceil(layout.height * scale);

  /** Reads which part of the drawing is in view, for the minimap. */
  const readView = useCallback(() => {
    const element = frame.current;
    if (!element) return;
    const overflows = element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1;
    setView(overflows ? {
      x: element.scrollLeft / scale, y: element.scrollTop / scale,
      width: Math.min(layout.width, element.clientWidth / scale), height: Math.min(layout.height, element.clientHeight / scale),
    } : undefined);
  }, [scale, layout]);

  const centreOn = useCallback((x: number, y: number) => {
    const element = frame.current;
    if (!element) return;
    element.scrollLeft = Math.max(0, x * scale - element.clientWidth / 2);
    element.scrollTop = Math.max(0, y * scale - element.clientHeight / 2);
    readView();
  }, [scale, readView]);

  // The step to keep in view: the case's selected step, or the one chosen in a change list.
  const selected = overlay?.selected ?? picked;
  const centredAt = useRef<string | undefined>(undefined);
  useEffect(() => {
    // Opening, or changing the zoom, centres the selected step; picking another step scrolls only if it is out of view.
    const element = frame.current;
    const placed = selected !== undefined ? layout.nodes.find((item) => item.id === selected) : undefined;
    if (!element || canvas === undefined) return;
    const key = `${mode}:${scale.toFixed(3)}:${String(layout.width)}`;
    const fresh = centredAt.current !== key;
    centredAt.current = key;
    if (!placed) { readView(); return; }
    const x = placed.x + placed.width / 2;
    const y = placed.y + placed.height / 2;
    const outOfView = x * scale < element.scrollLeft + 40 || x * scale > element.scrollLeft + element.clientWidth - 40
      || y * scale < element.scrollTop + 40 || y * scale > element.scrollTop + element.clientHeight - 40;
    if (fresh || outOfView) centreOn(x, y);
    else readView();
  }, [selected, layout, scale, mode, canvas, centreOn, readView]);

  const drag = useRef<{ x: number; y: number; left: number; top: number } | undefined>(undefined);
  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    const element = frame.current;
    if (!element || view === undefined || event.button !== 0) return;
    if ((event.target as Element).closest('.dg-node--pick, .dg-toggle') !== null) return;
    drag.current = { x: event.clientX, y: event.clientY, left: element.scrollLeft, top: element.scrollTop };
    element.setPointerCapture(event.pointerId);
  };
  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const element = frame.current;
    const start = drag.current;
    if (!element || !start) return;
    element.scrollLeft = start.left - (event.clientX - start.x);
    element.scrollTop = start.top - (event.clientY - start.y);
  };
  const onPointerUp = (event: PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return;
    drag.current = undefined;
    frame.current?.releasePointerCapture(event.pointerId);
  };
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const element = frame.current;
    if (!element || event.target !== element) return;
    const step = event.shiftKey ? PAN_STEP * 3 : PAN_STEP;
    const moves: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    element.scrollBy({ left: move[0], top: move[1] });
  };

  const pannable = view !== undefined;
  return (
    <div className="dg">
      <div className={toolbar !== undefined ? 'dg__toolbar dg__bar' : 'dg__toolbar'}>
        {toolbar}
        {mode === 'whole' ? <p className="dg__note" id={noteId}>{withBadges ? t('diagram.wholeNote') : t('diagram.wholeProcessNote')}</p> : null}
        <div className="dg__tools seg seg--small" role="group" aria-label={t('diagram.zoom')}>
          <button type="button" className="hit" aria-pressed={mode === 'fit'} onClick={() => { setZoom('fit'); }}>{t('diagram.fit')}</button>
          <button type="button" className="hit" aria-pressed={mode === 'actual'} onClick={() => { setZoom('actual'); }}>{t('diagram.actual')}</button>
          {offerWhole ? (
            <button type="button" className="hit" aria-pressed={mode === 'whole'} aria-describedby={mode === 'whole' ? noteId : undefined}
              onClick={() => { setZoom('whole'); }}>{withBadges ? t('diagram.whole') : t('diagram.wholeProcess')}</button>
          ) : null}
        </div>
      </div>
      <div className="dg__stage">
        <div ref={frame} className={`dg__frame${fill ? ' dg__frame--fill' : ''}${pannable ? ' dg__frame--pannable' : ''}`} onScroll={readView}
          tabIndex={pannable ? 0 : undefined} role={pannable ? 'region' : undefined} aria-label={pannable ? t('diagram.pan') : undefined}
          onKeyDown={onKeyDown} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
          <svg className="dg__svg" role="group" aria-label={label} width={width} height={height}
            viewBox={`0 0 ${String(layout.width)} ${String(layout.height)}`} data-layout="auto" data-scale={scale.toFixed(2)}>
            <defs>
              <marker id={marker} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
                <path d="M0 0 L10 5 L0 10 z" className="dg-arrow" />
              </marker>
              {marks !== undefined ? (['added', 'removed'] as const).map((mark) => (
                <marker key={mark} id={`${marker}-${mark}`} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                  <path d="M0 0 L10 5 L0 10 z" className={`dg-arrow dg-arrow--${mark}`} />
                </marker>
              )) : null}
            </defs>
            {collaboration !== undefined ? <Collaboration drawing={collaboration} /> : null}
            <g>{layout.nodes.filter((node) => node.container?.open === true).map((node) => (
              <Frame key={node.id} node={node} label={`${node.name ?? node.id}, ${nodeTypeLabel(node.type, t)}${node.loop !== undefined ? `, ${t(`diagram.loop.${node.loop}`)}` : ''}`}
                interaction={{ selected: overlay?.selected === node.id, onSelect: overlay ? () => { overlay.onSelect(node.id); } : undefined }}
                onToggle={() => { toggle(node.id); }} toggleLabel={t('diagram.collapse', { name: node.name ?? node.id })} />
            ))}</g>
            {collaboration !== undefined ? (
              <g>{collaboration.flows.map((flow) => <MessageFlowLine key={flow.id} flow={flow} marker={marker} />)}</g>
            ) : null}
            <g>{layout.edges.map((edge) => <Edge key={edge.id} edge={edge} marker={marker} defaultLabel={t('diagram.defaultFlow')} mark={marks?.edges.get(edgeKey(edge.source, edge.target))} />)}</g>
            {layout.data !== undefined ? (
              <g className="dg-data-layer">
                {layout.data.links.map((link) => <DataLine key={link.id} link={link} marker={marker} />)}
                {layout.data.items.map((item) => <DataSymbol key={item.id} item={item} kindLabel={t(item.kind === 'dataStore' ? 'diagram.data.store' : 'diagram.data.object')} />)}
              </g>
            ) : null}
            <g>{layout.nodes.filter((node) => node.container?.open !== true).map((node) => (
              <Node key={node.id} node={node} typeLabel={nodeTypeLabel(node.type, t)} loopLabel={node.loop !== undefined ? t(`diagram.loop.${node.loop}`) : undefined} badge={overlay?.badges.get(node.id)}
                interaction={{ selected: overlay?.selected === node.id, onSelect: overlay ? () => { overlay.onSelect(node.id); } : undefined }}
                mark={marks?.nodes.get(node.id)} markLabel={markLabel} picked={picked === node.id}
                steps={node.container !== undefined ? t('diagram.stepCount', { count: String(node.container.steps) }) : undefined}
                onToggle={node.container !== undefined ? () => { toggle(node.id); } : undefined}
                toggleLabel={node.container !== undefined ? t('diagram.expand', { name: node.name ?? node.id }) : undefined} />
            ))}</g>
            {overlay !== undefined ? (
              <g>{layout.nodes.map((node) => {
                const badge = overlay.badges.get(node.id);
                return badge !== undefined ? <Badge key={node.id} node={node} badge={badge} scale={scale} /> : null;
              })}</g>
            ) : null}
          </svg>
        </div>
        {view !== undefined ? <Minimap layout={layout} view={view} selected={selected} onCentre={centreOn} /> : null}
      </div>
    </div>
  );
}
