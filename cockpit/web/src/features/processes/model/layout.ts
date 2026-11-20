import dagre from '@dagrejs/dagre';
import { isKnownNodeType, type DiagramGraph, type DiagramNode } from './bpmnGraph';

/*
 * Automatic layout of a definition with no stored layout (cockpit DESIGN section 10, CKP-069): dagre, left to right,
 * node separation 40, rank separation 60. Stored layouts arrive with the layout API (slice 8.8); until then every
 * definition is laid out here.
 */

export type Shape = 'event' | 'gateway' | 'task';

export interface PlacedNode extends DiagramNode {
  shape: Shape;
  /** Top-left corner and size of the node's box, label included. */
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface Point { x: number; y: number }

export interface PlacedEdge {
  id: string;
  source: string;
  target: string;
  label: string | undefined;
  isDefault: boolean;
  points: Point[];
}

export interface Layout {
  width: number;
  height: number;
  nodes: PlacedNode[];
  edges: PlacedEdge[];
}

export const TASK_SIZE = { width: 152, height: 64 } as const;
/** Events and gateways keep room under the symbol for a two-line name. */
export const SYMBOL_BOX = { width: 96, height: 96 } as const;
export const EVENT_RADIUS = 20;
export const GATEWAY_HALF = 24;

/** Events and gateways get their symbol; every other node, and any type this cockpit does not know, a box. */
export function shapeOf(type: string): Shape {
  if (!isKnownNodeType(type)) return 'task';
  const base = type.split(':')[0] ?? type;
  if (base.endsWith('Event')) return 'event';
  if (base.endsWith('Gateway')) return 'gateway';
  return 'task';
}

/** The centre of the symbol of an event or gateway: in the upper part of its box, above the name. */
export function symbolCentre(node: Pick<PlacedNode, 'x' | 'y' | 'width'>): Point {
  return { x: node.x + node.width / 2, y: node.y + GATEWAY_HALF + 8 };
}

function sizeOf(shape: Shape) {
  return shape === 'task' ? TASK_SIZE : SYMBOL_BOX;
}

/** Moves an edge end from the box edge onto the symbol's outline, so the arrow touches the circle or diamond. */
function onOutline(end: Point, toward: Point, node: PlacedNode): Point {
  if (node.shape === 'task') return end;
  const centre = symbolCentre(node);
  const dx = toward.x - centre.x;
  const dy = toward.y - centre.y;
  const length = Math.hypot(dx, dy) || 1;
  const radius = node.shape === 'event' ? EVENT_RADIUS : GATEWAY_HALF * 1.1;
  return { x: centre.x + (dx / length) * radius, y: centre.y + (dy / length) * radius };
}

export function layoutGraph(graph: DiagramGraph): Layout {
  const g = new dagre.graphlib.Graph({ multigraph: true });
  g.setGraph({ rankdir: 'LR', nodesep: 40, ranksep: 60, marginx: 24, marginy: 24 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const node of graph.nodes) g.setNode(node.id, { ...sizeOf(shapeOf(node.type)) });
  for (const edge of graph.edges) g.setEdge(edge.source, edge.target, {}, edge.id);
  dagre.layout(g);

  const nodes: PlacedNode[] = graph.nodes.map((node) => {
    const shape = shapeOf(node.type);
    const { width, height } = sizeOf(shape);
    const placed = g.node(node.id) as { x: number; y: number };
    // Symbols sit high in their box; shift the box so the symbol centre lies on dagre's centre line and edges stay straight.
    const lift = shape === 'task' ? 0 : height / 2 - (GATEWAY_HALF + 8);
    return { ...node, shape, width, height, x: placed.x - width / 2, y: placed.y - height / 2 + lift };
  });
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const edges: PlacedEdge[] = graph.edges.map((edge) => {
    const raw = ((g.edge({ v: edge.source, w: edge.target, name: edge.id }) as { points?: Point[] } | undefined)?.points ?? []).map((p) => ({ x: p.x, y: p.y }));
    const source = byId.get(edge.source);
    const target = byId.get(edge.target);
    const points = [...raw];
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
    return { ...edge, points };
  });
  const box = g.graph() as { width?: number; height?: number };
  return { width: Math.ceil(box.width ?? 0), height: Math.ceil(box.height ?? 0), nodes, edges };
}

/** True when two placed nodes' boxes intersect. */
export function overlaps(a: Pick<PlacedNode, 'x' | 'y' | 'width' | 'height'>, b: Pick<PlacedNode, 'x' | 'y' | 'width' | 'height'>): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}
