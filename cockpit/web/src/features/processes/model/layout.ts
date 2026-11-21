import dagre from '@dagrejs/dagre';
import { isKnownNodeType, type DiagramGraph, type DiagramNode } from './bpmnGraph';

/*
 * Automatic layout of a definition with no stored layout (cockpit DESIGN section 10, CKP-069): dagre, left to right,
 * node separation 28, rank separation 24, with edge labels laid out as part of the graph so they keep clear of the
 * nodes. Names are wrapped here, not truncated, so every box is sized to its whole name; the text sizes are the
 * styleguide's label (13 px) and caption (12 px) stops, drawn at 100 % or larger. Stored layouts arrive with the
 * layout API (slice 8.8); until then every definition is laid out here.
 */

export type Shape = 'event' | 'gateway' | 'task';

export interface PlacedNode extends DiagramNode {
  shape: Shape;
  /** Top-left corner and size of the node's box, name included. */
  x: number;
  y: number;
  width: number;
  height: number;
  /** The name broken into the lines it is drawn with. */
  lines: string[];
  /** A box's type in words, broken into lines; empty for events and gateways, whose type is their symbol. */
  typeLines: string[];
}

export interface Point { x: number; y: number }

export interface PlacedEdge {
  id: string;
  source: string;
  target: string;
  label: string | undefined;
  isDefault: boolean;
  points: Point[];
  /** Centre of the edge's label, in the space dagre kept free for it. */
  labelAt: Point | undefined;
}

export interface Layout {
  width: number;
  height: number;
  nodes: PlacedNode[];
  edges: PlacedEdge[];
}

/** Width of a task box; its height follows the number of lines of its name. */
export const TASK_WIDTH = 116;
const TASK_PAD_X = 10;
const TASK_PAD_Y = 10;
const NAME_LINE = 18;
const TYPE_ROW = 17;
/** The type's words start after its 16 px icon and a 6 px gap. */
export const TYPE_INDENT = 22;
const TASK_MIN_HEIGHT = 56;
/** Events and gateways: the symbol in a 48 px box, its name centred below in up to CAPTION_WIDTH. */
export const SYMBOL_SIZE = 48;
export const CAPTION_WIDTH = 84;
export const CAPTION_GAP = 10;
const CAPTION_LINE = 17;
export const EVENT_RADIUS = 20;
export const GATEWAY_HALF = 24;
/** Room kept on each side of an edge label, so it never touches the node or the edge it names. */
const LABEL_PAD = 10;
const LABEL_HEIGHT = 22;

/** Average advance of a character at the label stop (13 px, semibold) and the caption stop (12 px). */
const NAME_CHAR = 7.4;
const CAPTION_CHAR = 6.8;

/** Greedy word wrap by estimated width. A word longer than the line is broken, so nothing is ever cut off. */
export function wrapText(text: string, maxWidth: number, charWidth: number): string[] {
  const perLine = Math.max(4, Math.floor(maxWidth / charWidth));
  const lines: string[] = [];
  let line = '';
  for (const word of text.split(/\s+/).filter((part) => part !== '')) {
    let rest = word;
    while (rest.length > perLine) {
      if (line !== '') { lines.push(line); line = ''; }
      lines.push(rest.slice(0, perLine));
      rest = rest.slice(perLine);
    }
    if (line === '') line = rest;
    else if (line.length + 1 + rest.length <= perLine) line = `${line} ${rest}`;
    else { lines.push(line); line = rest; }
  }
  if (line !== '') lines.push(line);
  return lines.length > 0 ? lines : [''];
}

/** Events and gateways get their symbol; every other node, and any type this cockpit does not know, a box. */
export function shapeOf(type: string): Shape {
  if (!isKnownNodeType(type)) return 'task';
  const base = type.split(':')[0] ?? type;
  if (base.endsWith('Event')) return 'event';
  if (base.endsWith('Gateway')) return 'gateway';
  return 'task';
}

/** The centre of the symbol of an event or gateway: at the top of its box, above the name. */
export function symbolCentre(node: Pick<PlacedNode, 'x' | 'y' | 'width'>): Point {
  return { x: node.x + node.width / 2, y: node.y + SYMBOL_SIZE / 2 };
}

interface Size { width: number; height: number; lines: string[]; typeLines: string[] }

function sized(node: DiagramNode, shape: Shape, typeLabel: string): Size {
  const name = node.name ?? node.id;
  if (shape === 'task') {
    const lines = wrapText(name, TASK_WIDTH - 2 * TASK_PAD_X, NAME_CHAR);
    const typeLines = wrapText(typeLabel, TASK_WIDTH - 2 * TASK_PAD_X - TYPE_INDENT, CAPTION_CHAR);
    const content = 2 * TASK_PAD_Y + lines.length * NAME_LINE + typeLines.length * TYPE_ROW;
    return { width: TASK_WIDTH, height: Math.max(TASK_MIN_HEIGHT, content), lines, typeLines };
  }
  const lines = wrapText(name, CAPTION_WIDTH, CAPTION_CHAR);
  return { width: SYMBOL_SIZE, height: SYMBOL_SIZE + CAPTION_GAP + lines.length * CAPTION_LINE, lines, typeLines: [] };
}

export const TASK_TEXT = { padX: TASK_PAD_X, padY: TASK_PAD_Y, nameLine: NAME_LINE, typeRow: TYPE_ROW } as const;
export const CAPTION_LINE_HEIGHT = CAPTION_LINE;

/** Moves an edge end from the box edge onto the symbol's outline, so the arrow touches the circle or diamond. */
function onOutline(end: Point, toward: Point, node: PlacedNode): Point {
  if (node.shape === 'task') return end;
  const centre = symbolCentre(node);
  const dx = toward.x - centre.x;
  const dy = toward.y - centre.y;
  const length = Math.hypot(dx, dy) || 1;
  const radius = node.shape === 'event' ? EVENT_RADIUS : GATEWAY_HALF * 1.05;
  return { x: centre.x + (dx / length) * radius, y: centre.y + (dy / length) * radius };
}

export interface LayoutOptions {
  /**
   * Leave room for status badges on the steps of an instance (CKP-017): a badge sits on a box's top edge and may be
   * wider than the box, so ranks and rows are spaced to keep it clear of the next step.
   */
  badges?: boolean;
}

/** Spacing with badges: the widest badge (about 150 px) fits over a 116 px box and the gap to the next rank, and clears a caption that hangs below the symbol above it. */
const BADGE_RANKSEP = 56;
const BADGE_NODESEP = 64;

/**
 * Lays the graph out. `typeLabel` gives each node's type in words in the user's language, so a box is sized to the
 * words it shows.
 */
export function layoutGraph(graph: DiagramGraph, typeLabel: (type: string) => string = (type) => type, options: LayoutOptions = {}): Layout {
  const g = new dagre.graphlib.Graph({ multigraph: true });
  const badges = options.badges === true;
  g.setGraph({ rankdir: 'LR', nodesep: badges ? BADGE_NODESEP : 28, ranksep: badges ? BADGE_RANKSEP : 24, marginx: 4, marginy: badges ? 36 : 12 });
  g.setDefaultEdgeLabel(() => ({}));
  const sizes = new Map(graph.nodes.map((node) => [node.id, sized(node, shapeOf(node.type), typeLabel(node.type))]));
  for (const node of graph.nodes) {
    const size = sizes.get(node.id);
    if (size) g.setNode(node.id, { width: size.width, height: size.height });
  }
  for (const edge of graph.edges) {
    const label = edge.label === undefined ? {} : { width: Math.ceil(edge.label.length * CAPTION_CHAR) + 2 * LABEL_PAD, height: LABEL_HEIGHT, labelpos: 'c' };
    g.setEdge(edge.source, edge.target, label, edge.id);
  }
  dagre.layout(g);

  const nodes: PlacedNode[] = graph.nodes.map((node) => {
    const shape = shapeOf(node.type);
    const size = sizes.get(node.id) ?? { width: TASK_WIDTH, height: TASK_MIN_HEIGHT, lines: [node.id], typeLines: [] };
    const placed = g.node(node.id) as { x: number; y: number };
    // Symbols sit at the top of their box; shift the box so the symbol centre lies on dagre's centre line and edges stay straight.
    const lift = shape === 'task' ? 0 : size.height / 2 - SYMBOL_SIZE / 2;
    return { ...node, shape, width: size.width, height: size.height, lines: size.lines, typeLines: size.typeLines, x: placed.x - size.width / 2, y: placed.y - size.height / 2 + lift };
  });
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const edges: PlacedEdge[] = graph.edges.map((edge) => {
    const laid = g.edge({ v: edge.source, w: edge.target, name: edge.id }) as { points?: Point[]; x?: number; y?: number } | undefined;
    const points = (laid?.points ?? []).map((p) => ({ x: p.x, y: p.y }));
    const source = byId.get(edge.source);
    const target = byId.get(edge.target);
    if (source && points.length > 1) {
      const first = points[0];
      const second = points[1];
      if (first && second) points[0] = onOutline(first, second, source);
    }
    if (target && points.length > 1) {
      const last = points[points.length - 1];
      const before = points[points.length - 2];
      if (last && before) points[points.length - 1] = onOutline(last, before, target);
    }
    const labelAt = edge.label !== undefined && laid?.x !== undefined && laid.y !== undefined ? { x: laid.x, y: laid.y } : undefined;
    return { ...edge, points, labelAt };
  });
  const box = g.graph() as { width?: number; height?: number };
  // Captions may reach past a symbol's box; widen the drawing so none is clipped at the sides or the bottom.
  const overhang = (CAPTION_WIDTH - SYMBOL_SIZE) / 2;
  const symbols = nodes.filter((n) => n.shape !== 'task');
  const minX = Math.min(0, ...symbols.map((n) => n.x - overhang));
  const maxX = Math.max(box.width ?? 0, ...symbols.map((n) => n.x + n.width + overhang));
  const maxY = Math.max(box.height ?? 0, ...nodes.map((n) => n.y + n.height + 12));
  if (minX < 0) {
    for (const node of nodes) node.x -= minX;
    for (const edge of edges) {
      for (const point of edge.points) point.x -= minX;
      if (edge.labelAt) edge.labelAt.x -= minX;
    }
  }
  return { width: Math.ceil(maxX - minX), height: Math.ceil(maxY), nodes, edges };
}

/** True when two placed nodes' boxes intersect. */
export function overlaps(a: Pick<PlacedNode, 'x' | 'y' | 'width' | 'height'>, b: Pick<PlacedNode, 'x' | 'y' | 'width' | 'height'>): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

/** The scale "100 %" draws at, and the smallest Fit uses for a definition: its smallest text is then the 12 px caption stop. */
export const MIN_SCALE = 1;

/** The smallest scale Fit uses for a whole case; below it the drawing scrolls rather than become unreadable. */
export const WHOLE_FIT_MIN_SCALE = 0.4;

/**
 * The scale that fits a drawing into its canvas, up to `max` and down to `min`. A definition keeps `min` at 100 %, so
 * a wider drawing scrolls rather than shrink text below 12 px (CKP-069); a case passes WHOLE_FIT_MIN_SCALE so the
 * operator sees every step and end of the case at once, and "100 %" shows it at full size (CKP-017).
 */
export function fitScale(drawing: { width: number; height: number }, canvas: { width: number; height: number }, max = 1.6, min = MIN_SCALE): number {
  if (drawing.width <= 0 || drawing.height <= 0 || canvas.width <= 0 || canvas.height <= 0) return MIN_SCALE;
  return Math.max(min, Math.min(max, canvas.width / drawing.width, canvas.height / drawing.height));
}
