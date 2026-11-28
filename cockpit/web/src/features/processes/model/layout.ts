import dagre from '@dagrejs/dagre';
import { applyStoredLayout } from './storedLayout';
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
  /**
   * Set on a sub-process that holds steps of its own (#486): how many steps it holds, counting every level but not
   * start and end events, and whether it is drawn open. An open one is a frame around its steps, which are placed
   * inside it; a closed one is a box with a count.
   */
  container?: { steps: number; open: boolean };
}

export interface Point { x: number; y: number }

export interface PlacedEdge {
  id: string;
  source: string;
  target: string;
  label: string | undefined;
  isDefault: boolean;
  /** A link between a throw and its catch (#493), drawn dashed. */
  isLink?: boolean;
  /** The line from a compensation boundary event to its compensation activity (#488), drawn dotted. */
  isAssociation?: boolean;
  /** The flow carries a condition (BPMN's small diamond at its start when it leaves an activity). */
  conditional?: boolean;
  points: Point[];
  /** Centre of the edge's label, in the space dagre kept free for it. */
  labelAt: Point | undefined;
}

/** A data object or data store drawn in the band under the steps (#499); `x`, `y` are the top-left of the symbol, the caption hangs below it. */
export interface PlacedDataItem {
  id: string;
  kind: 'dataObject' | 'dataStore';
  name: string | undefined;
  x: number;
  y: number;
  width: number;
  height: number;
  lines: string[];
}

/** The dotted association between a step and a data symbol; the arrowhead is at `points`' last point. */
export interface PlacedDataLink { id: string; activity: string; item: string; direction: 'read' | 'write'; points: Point[] }

export interface Layout {
  width: number;
  height: number;
  nodes: PlacedNode[];
  edges: PlacedEdge[];
  /** Data symbols and their associations, in a band under every step; absent when the process has none. */
  data?: { items: PlacedDataItem[]; links: PlacedDataLink[] };
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
/** Room kept at the foot of a step for its loop or compensation marker. */
export const MARKER_ROW = 16;
export const SYMBOL_SIZE = 48;
export const CAPTION_WIDTH = 84;
export const CAPTION_GAP = 10;
const CAPTION_LINE = 17;
export const EVENT_RADIUS = 20;
export const GATEWAY_HALF = 24;
/** Boundary events (#489): the first sits this far in from the right of its activity (clear of the type label at the left), the next ones this far apart, and a flow drops this far under the symbol before it runs on. */
const BOUNDARY_INSET = 24;
const BOUNDARY_STEP = 44;
const BOUNDARY_DROP = 14;
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

/**
 * Wrap for a type label (#609): a line breaks only at a space or just after a hyphen, never inside a word. A word that is
 * wider than the line stays whole on its own line rather than being cut in two.
 */
export function wrapWords(text: string, maxWidth: number, charWidth: number): string[] {
  const perLine = Math.max(4, Math.floor(maxWidth / charWidth));
  const lines: string[] = [];
  let line = '';
  for (const word of text.split(/\s+/).filter((part) => part !== '')) {
    const parts = word.match(/[^-]+-?|-/g) ?? [word];
    parts.forEach((part, index) => {
      const joined = index === 0 ? (line === '' ? part : `${line} ${part}`) : line + part;
      if (line === '' || joined.length <= perLine) line = joined;
      else { lines.push(line); line = part; }
    });
  }
  if (line !== '') lines.push(line);
  return lines.length > 0 ? lines : [''];
}

/** A line wider than its room is cut with an ellipsis; the step's own title carries the whole text. */
function fitted(lines: string[], maxWidth: number, charWidth: number): string[] {
  // Wrapping estimates generously; the cut is made at the width the text really draws at (about 0.9 of the estimate).
  const perLine = Math.max(4, Math.floor(maxWidth / (charWidth * 0.9)));
  return lines.map((line) => (line.length > perLine ? `${line.slice(0, perLine - 1)}…` : line));
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
    const lines = fitted(wrapWords(name, TASK_WIDTH - 2 * TASK_PAD_X, NAME_CHAR), TASK_WIDTH - 2 * TASK_PAD_X, NAME_CHAR);
    const typeLines = fitted(wrapWords(typeLabel, TASK_WIDTH - 2 * TASK_PAD_X - TYPE_INDENT, CAPTION_CHAR), TASK_WIDTH - 2 * TASK_PAD_X - TYPE_INDENT, CAPTION_CHAR);
    // A step that repeats or undoes another carries a marker at its foot (#496): the box keeps a row for it, clear of the text.
    const marked = node.loop !== undefined || (node.compensation === true && node.type !== 'serviceTask:compensation');
    const content = 2 * TASK_PAD_Y + lines.length * NAME_LINE + typeLines.length * TYPE_ROW + (marked ? MARKER_ROW : 0);
    return { width: TASK_WIDTH, height: Math.max(TASK_MIN_HEIGHT, content), lines, typeLines };
  }
  const lines = wrapText(name, CAPTION_WIDTH, CAPTION_CHAR);
  return { width: SYMBOL_SIZE, height: SYMBOL_SIZE + CAPTION_GAP + lines.length * CAPTION_LINE, lines, typeLines: [] };
}

export const TASK_TEXT = { padX: TASK_PAD_X, padY: TASK_PAD_Y, nameLine: NAME_LINE, typeRow: TYPE_ROW } as const;
export const CAPTION_LINE_HEIGHT = CAPTION_LINE;

/** Moves an edge end from the box edge onto the symbol's outline, so the arrow touches the circle or diamond. */
export function onOutline(end: Point, toward: Point, node: PlacedNode): Point {
  if (node.shape === 'task') return end;
  const centre = symbolCentre(node);
  const dx = toward.x - centre.x;
  const dy = toward.y - centre.y;
  const length = Math.hypot(dx, dy) || 1;
  const radius = node.shape === 'event' ? EVENT_RADIUS : GATEWAY_HALF * 1.05;
  return { x: centre.x + (dx / length) * radius, y: centre.y + (dy / length) * radius };
}

export interface LayoutOptions {
  /** The sub-processes drawn open, by id; the others are drawn as one box with a count of their steps. */
  open?: ReadonlySet<string>;
  /**
   * Leave room for status badges on the steps of an instance (CKP-017): a badge sits on a box's top edge and may be
   * wider than the box, so ranks and rows are spaced to keep it clear of the next step.
   */
  badges?: boolean;
}

/** Spacing with badges: the widest badge (about 150 px) fits over a 116 px box and the gap to the next rank, and clears a caption that hangs below the symbol above it. */
const BADGE_RANKSEP = 56;
const BADGE_NODESEP = 64;

/** Frame of an open sub-process: a header with its name and the room kept around its steps. */
export const FRAME_HEADER = 30;
/** Extra height of a closed sub-process: a row at its foot for the count of its steps and the control that opens it. */
export const CLOSED_CONTAINER_EXTRA = 26;
export const FRAME_PAD = 14;

interface Level { width: number; height: number; nodes: PlacedNode[]; edges: PlacedEdge[] }

function shift(level: Level, dx: number, dy: number): void {
  for (const node of level.nodes) { node.x += dx; node.y += dy; }
  for (const edge of level.edges) {
    for (const point of edge.points) { point.x += dx; point.y += dy; }
    if (edge.labelAt) { edge.labelAt.x += dx; edge.labelAt.y += dy; }
  }
}

/** Counts the steps under a container at every level, leaving out start and end events. */
function stepCount(graph: DiagramGraph, id: string): number {
  return graph.nodes.filter((node) => node.parentId === id).reduce(
    (sum, node) => sum + (node.type.startsWith('startEvent') || node.type === 'endEvent' ? 0 : 1) + stepCount(graph, node.id), 0);
}

/**
 * Lays one container's own steps out with dagre. An open sub-process is laid out first, on its own, and then takes
 * its place as one box sized to its steps, so a flow never crosses a frame; a closed one is a task box.
 */
function layoutLevel(graph: DiagramGraph, scope: string | undefined, typeLabel: (type: string) => string, options: LayoutOptions): Level {
  const badges = options.badges === true;
  const everyoneHere = graph.nodes.filter((node) => node.parentId === scope);
  const hereIds = new Set(everyoneHere.map((node) => node.id));
  // A boundary event (#489) sits on the lower edge of its activity, so it takes no place of its own in the ranks.
  const boundaries = everyoneHere.filter((node) => node.attachedTo !== undefined && hereIds.has(node.attachedTo));
  const boundaryIds = new Set(boundaries.map((node) => node.id));
  const hostOf = new Map(boundaries.map((node) => [node.id, node.attachedTo ?? '']));
  const nodesHere = everyoneHere.filter((node) => !boundaryIds.has(node.id));
  const inner = new Map<string, Level>();
  const sizes = new Map<string, Size>();
  const containers = new Map<string, { steps: number; open: boolean }>();
  for (const node of nodesHere) {
    const steps = stepCount(graph, node.id);
    const holds = graph.nodes.some((other) => other.parentId === node.id);
    if (holds) containers.set(node.id, { steps, open: options.open?.has(node.id) === true });
    const base = sized(node, shapeOf(node.type), typeLabel(node.type));
    if (holds && options.open?.has(node.id) === true) {
      const level = layoutLevel(graph, node.id, typeLabel, options);
      inner.set(node.id, level);
      sizes.set(node.id, { ...base, width: Math.max(base.width, level.width + 2 * FRAME_PAD), height: level.height + FRAME_HEADER + FRAME_PAD, lines: base.lines, typeLines: [] });
    } else if (holds) {
      sizes.set(node.id, { ...base, height: base.height + CLOSED_CONTAINER_EXTRA });
    } else {
      sizes.set(node.id, base);
    }
  }
  const g = new dagre.graphlib.Graph({ multigraph: true });
  g.setGraph({ rankdir: 'LR', nodesep: badges ? BADGE_NODESEP : 28, ranksep: badges ? BADGE_RANKSEP : 24, marginx: 4, marginy: badges ? 36 : 12 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const node of nodesHere) {
    const size = sizes.get(node.id);
    if (size) g.setNode(node.id, { width: size.width, height: size.height });
  }
  const edgesHere = graph.edges.filter((edge) => hereIds.has(edge.source) && hereIds.has(edge.target));
  for (const edge of edgesHere) {
    const label = edge.label === undefined ? {} : { width: Math.ceil(edge.label.length * CAPTION_CHAR) + 2 * LABEL_PAD, height: LABEL_HEIGHT, labelpos: 'c' };
    // A flow that leaves a boundary event is ranked as if it left the activity the boundary sits on.
    g.setEdge(hostOf.get(edge.source) ?? edge.source, edge.target, label, edge.id);
  }
  dagre.layout(g);

  const nodes: PlacedNode[] = nodesHere.map((node) => {
    const shape = shapeOf(node.type);
    const size = sizes.get(node.id) ?? { width: TASK_WIDTH, height: TASK_MIN_HEIGHT, lines: [node.id], typeLines: [] };
    const placed = g.node(node.id) as { x: number; y: number };
    // Symbols sit at the top of their box; shift the box so the symbol centre lies on dagre's centre line and edges stay straight.
    const lift = shape === 'task' ? 0 : size.height / 2 - SYMBOL_SIZE / 2;
    const container = containers.get(node.id);
    return { ...node, shape, width: size.width, height: size.height, lines: size.lines, typeLines: size.typeLines, x: placed.x - size.width / 2, y: placed.y - size.height / 2 + lift, ...(container !== undefined ? { container } : {}) };
  });
  // Boundary events: on the lower edge of the activity, side by side when there are several (#489).
  const placedPerHost = new Map<string, number>();
  for (const node of boundaries) {
    const host = nodes.find((candidate) => candidate.id === node.attachedTo);
    if (host === undefined) continue;
    const index = placedPerHost.get(host.id) ?? 0;
    placedPerHost.set(host.id, index + 1);
    const centreX = host.x + host.width - BOUNDARY_INSET - index * BOUNDARY_STEP;
    const centreY = host.y + host.height;
    const shape = shapeOf(node.type);
    nodes.push({ ...node, shape, width: SYMBOL_SIZE, height: SYMBOL_SIZE, lines: [], typeLines: [], x: centreX - SYMBOL_SIZE / 2, y: centreY - SYMBOL_SIZE / 2 });
  }
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const edges: PlacedEdge[] = edgesHere.map((edge) => {
    const laid = g.edge({ v: hostOf.get(edge.source) ?? edge.source, w: edge.target, name: edge.id }) as { points?: Point[]; x?: number; y?: number } | undefined;
    const points = (laid?.points ?? []).map((p) => ({ x: p.x, y: p.y }));
    const source = byId.get(edge.source);
    const target = byId.get(edge.target);
    const leavesBoundary = boundaryIds.has(edge.source);
    if (leavesBoundary && source && points.length > 1) {
      // Down from the boundary symbol, along under the activity, then on as dagre routed the flow.
      const start = symbolCentre(source);
      const first = points[1];
      const lane = start.y + EVENT_RADIUS + BOUNDARY_DROP;
      const rest = points.slice(1);
      points.length = 0;
      points.push({ x: start.x, y: start.y + EVENT_RADIUS }, { x: start.x, y: lane });
      if (first) points.push({ x: first.x, y: lane });
      points.push(...rest);
    } else if (source && points.length > 1) {
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
  const level: Level = { width: Math.ceil(maxX - minX), height: Math.ceil(maxY), nodes, edges };
  if (minX < 0) shift(level, -minX, 0);
  // An open sub-process holds its own level, moved into the frame below the header.
  const placedInner: PlacedNode[] = [];
  const placedInnerEdges: PlacedEdge[] = [];
  for (const node of nodes) {
    const content = inner.get(node.id);
    if (content === undefined) continue;
    shift(content, node.x + FRAME_PAD, node.y + FRAME_HEADER);
    placedInner.push(...content.nodes);
    placedInnerEdges.push(...content.edges);
  }
  return { ...level, nodes: [...nodes, ...placedInner], edges: [...edges, ...placedInnerEdges] };
}

/**
 * Lays the graph out. `typeLabel` gives each node's type in words in the user's language, so a box is sized to the
 * words it shows. A sub-process is one box with a count of its steps, or, when `options.open` names it, a frame with
 * its steps laid out inside (#486).
 */
export function layoutGraph(graph: DiagramGraph, typeLabel: (type: string) => string = (type) => type, options: LayoutOptions = {}): Layout {
  const level = layoutLevel(graph, undefined, typeLabel, options);
  const placed: Layout = { width: level.width, height: level.height, nodes: level.nodes, edges: level.edges };
  // The model's own diagram wins where it has coordinates; automatic layout stands for the rest.
  const base = graph.stored === undefined ? placed : applyStoredLayout(placed, graph.stored);
  return graph.data === undefined ? base : placeData(graph.data, base);
}

/** The data object is a page 30 by 38, the store a drum 44 by 38; each has its name below, up to DATA_CAPTION wide. */
const DATA_OBJECT_SIZE = { width: 30, height: 38 };
const DATA_STORE_SIZE = { width: 44, height: 38 };
const DATA_CAPTION = 104;
/** Room between the last step and the band, and from one symbol to the next. */
const DATA_BAND_GAP = 44;
const DATA_SYMBOL_GAP = 12;
/** Parallel associations at one step leave it this far apart, so none lies over another. */
const DATA_LINK_STEP = 14;

/**
 * Puts the data symbols (#499) in one band under every step, each under the step it is joined to (the middle of the
 * steps when it has several), pushed right so no two captions touch. An association leaves the step's lower edge,
 * runs down to a line between the steps and the band, across, and down to the symbol, so it never crosses a symbol.
 * A symbol in a sub-process that is drawn closed is left out, as its step is.
 */
function placeData(data: NonNullable<DiagramGraph['data']>, layout: Layout): Layout {
  const byId = new Map(layout.nodes.map((node) => [node.id, node]));
  const visible = data.items.filter((item) => item.parentId === undefined || byId.get(item.parentId)?.container?.open === true);
  if (visible.length === 0) return layout;
  const linksOf = (id: string) => data.links.filter((link) => link.item === id && byId.has(link.activity));
  const anchored = visible.map((item) => {
    const centres = linksOf(item.id).map((link) => { const node = byId.get(link.activity); return node === undefined ? 0 : node.x + node.width / 2; });
    return { item, anchor: centres.length === 0 ? Number.POSITIVE_INFINITY : centres.reduce((a, b) => a + b, 0) / centres.length };
  }).sort((a, b) => a.anchor - b.anchor);
  const top = layout.height + DATA_BAND_GAP;
  const items: PlacedDataItem[] = [];
  let cursor = 0;
  for (const { item, anchor } of anchored) {
    const size = item.kind === 'dataStore' ? DATA_STORE_SIZE : DATA_OBJECT_SIZE;
    const lines = wrapText(item.name ?? item.id, DATA_CAPTION, CAPTION_CHAR);
    const centre = Number.isFinite(anchor) ? anchor : cursor + DATA_CAPTION / 2;
    const x = Math.max(cursor, centre - DATA_CAPTION / 2);
    items.push({ id: item.id, kind: item.kind, name: item.name, x: x + (DATA_CAPTION - size.width) / 2, y: top, ...size, lines });
    cursor = x + DATA_CAPTION + DATA_SYMBOL_GAP;
  }
  const placed = new Map(items.map((item) => [item.id, item]));
  const laneY = layout.height + DATA_BAND_GAP / 2;
  const used = new Map<string, number>();
  const links: PlacedDataLink[] = [];
  for (const link of data.links) {
    const node = byId.get(link.activity);
    const item = placed.get(link.item);
    if (node === undefined || item === undefined) continue;
    // Each association at a step takes its own place along the step's lower edge.
    const index = used.get(link.activity) ?? 0;
    used.set(link.activity, index + 1);
    const startX = Math.min(node.x + node.width - 8, node.x + node.width / 2 + (index % 2 === 0 ? 1 : -1) * Math.ceil(index / 2) * DATA_LINK_STEP);
    const endX = item.x + item.width / 2;
    const stepFoot = { x: startX, y: node.y + node.height };
    const symbolTop = { x: endX, y: item.y };
    const down = [stepFoot, { x: startX, y: laneY + index * 4 }, { x: endX, y: laneY + index * 4 }, symbolTop];
    links.push({ id: link.id, activity: link.activity, item: link.item, direction: link.direction, points: link.direction === 'write' ? down : [...down].reverse() });
  }
  const width = Math.max(layout.width, Math.ceil(cursor - DATA_SYMBOL_GAP));
  const bottom = Math.max(...items.map((item) => item.y + item.height + (Math.max(...items.map((i) => i.lines.length)) * CAPTION_LINE) + 14));
  return { ...layout, width, height: Math.ceil(bottom), data: { items, links } };
}

/** True when two placed nodes' boxes intersect. */
export function overlaps(a: Pick<PlacedNode, 'x' | 'y' | 'width' | 'height'>, b: Pick<PlacedNode, 'x' | 'y' | 'width' | 'height'>): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

/** The scale "100 %" draws at, and the smallest Fit uses for a definition: its smallest text is then the 12 px caption stop. */
export const MIN_SCALE = 1;

/** The scale a case's Fit never goes below: the smallest text, the 12 px caption, is then 13 px (#509). */
export const READABLE_SCALE = 13 / 12;

/** The smallest scale of the "Whole case" overview; below it the drawing scrolls rather than become unreadable. */
export const WHOLE_FIT_MIN_SCALE = 0.4;

/**
 * The scale that fits a drawing into its canvas, up to `max` and down to `min`. Fit keeps `min` at 100 %, so a wider
 * drawing pans rather than shrink text below 12 px (CKP-069, CKP-017); only the "Whole case" overview passes
 * WHOLE_FIT_MIN_SCALE.
 */
export function fitScale(drawing: { width: number; height: number }, canvas: { width: number; height: number }, max = 1.6, min = MIN_SCALE): number {
  if (drawing.width <= 0 || drawing.height <= 0 || canvas.width <= 0 || canvas.height <= 0) return MIN_SCALE;
  return Math.max(min, Math.min(max, canvas.width / drawing.width, canvas.height / drawing.height));
}
