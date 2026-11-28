import type { DefinitionLayout } from '../../../api/processes/schemas';
import { onOutline, SYMBOL_SIZE, type Layout, type PlacedEdge, type PlacedNode, type Point } from './layout';

/*
 * The model's own diagram (BPMNDI, kept by the engine as layout revision 1) placed over the automatic layout. A step
 * the layout lists is moved to its bounds, a flow it lists is routed through its waypoints, and everything it does
 * not list keeps the place dagre gave it: a partial diagram is completed, never refused. Coordinates are absolute, as
 * BPMN diagrams keep them, so the steps inside an open sub-process need no offset.
 */

const MARGIN = 12;
const edgeKey = (edge: Pick<PlacedEdge, 'source' | 'target'>) => `${edge.source}->${edge.target}`;

function centreOf(node: PlacedNode): Point {
  return node.shape === 'task' ? { x: node.x + node.width / 2, y: node.y + node.height / 2 } : { x: node.x + node.width / 2, y: node.y + SYMBOL_SIZE / 2 };
}

/**
 * A flow the diagram does not route but whose ends have moved: straight when the steps are level or one above the other, otherwise one bend
 * halfway between them, so it runs along the rows and columns of the drawing and never at a slant.
 */
function straight(source: PlacedNode, target: PlacedNode): Point[] {
  const from = centreOf(source);
  const to = centreOf(target);
  if (Math.abs(from.y - to.y) < 6 || Math.abs(from.x - to.x) < 6) return [onShape(from, to, source), onShape(to, from, target)];
  const middle = (from.x + to.x) / 2;
  const leaving = onShape(from, { x: middle, y: from.y }, source);
  const arriving = onShape(to, { x: middle, y: to.y }, target);
  return [leaving, { x: middle, y: leaving.y }, { x: middle, y: arriving.y }, arriving];
}

/** Where the line from the node's centre toward `toward` leaves its box (tasks) or symbol (events, gateways). */
function onShape(centre: Point, toward: Point, node: PlacedNode): Point {
  if (node.shape !== 'task') return onOutline(centre, toward, node);
  const dx = toward.x - centre.x;
  const dy = toward.y - centre.y;
  const scale = Math.min(dx === 0 ? Infinity : node.width / 2 / Math.abs(dx), dy === 0 ? Infinity : node.height / 2 / Math.abs(dy));
  return Number.isFinite(scale) ? { x: centre.x + dx * scale, y: centre.y + dy * scale } : centre;
}

const midpoint = (points: readonly Point[]): Point | undefined => {
  const a = points[Math.floor((points.length - 1) / 2)];
  const b = points[Math.ceil((points.length - 1) / 2)];
  return a === undefined || b === undefined ? undefined : { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
};

export function applyStoredLayout(layout: Layout, stored: DefinitionLayout): Layout {
  const boxes = new Map(Object.entries(stored.nodes));
  const before = new Map(layout.nodes.map((node) => [node.id, { x: node.x, y: node.y }]));
  const moved = new Set<string>();
  const nodes: PlacedNode[] = layout.nodes.map((node) => {
    const box = boxes.get(node.id);
    if (box === undefined) return { ...node };
    moved.add(node.id);
    if (node.shape === 'task') {
      // A box is never smaller than its own text: the diagram's bounds are the least it is drawn at.
      return { ...node, x: box.x, y: box.y, width: Math.max(box.width ?? 0, node.width), height: Math.max(box.height ?? 0, node.height) };
    }
    // The symbol is drawn at the cockpit's size, centred where the diagram centres it.
    const cx = box.x + (box.width ?? SYMBOL_SIZE) / 2;
    const cy = box.y + (box.height ?? SYMBOL_SIZE) / 2;
    return { ...node, x: cx - node.width / 2, y: cy - SYMBOL_SIZE / 2 };
  });
  const byId = new Map(nodes.map((node) => [node.id, node]));
  // A boundary event the diagram does not place travels with the step it sits on.
  for (const node of nodes) {
    const host = node.attachedTo === undefined ? undefined : byId.get(node.attachedTo);
    const was = before.get(node.id);
    const hostWas = host === undefined ? undefined : before.get(host.id);
    if (moved.has(node.id) || host === undefined || was === undefined || hostWas === undefined || !moved.has(host.id)) continue;
    node.x = was.x + (host.x - hostWas.x);
    node.y = was.y + (host.y - hostWas.y);
    moved.add(node.id);
  }
  const waypoints = stored.edges ?? {};
  const edges: PlacedEdge[] = layout.edges.map((edge) => {
    const source = byId.get(edge.source);
    const target = byId.get(edge.target);
    if (source === undefined || target === undefined) return edge;
    const own = waypoints[edgeKey(edge)];
    if (own !== undefined && own.length >= 2) {
      const points = own.map((p) => ({ x: p.x, y: p.y }));
      const first = points[0];
      const second = points[1];
      const last = points[points.length - 1];
      const before2 = points[points.length - 2];
      if (source.shape !== 'task' && first && second) points[0] = onOutline(first, second, source);
      if (target.shape !== 'task' && last && before2) points[points.length - 1] = onOutline(last, before2, target);
      return { ...edge, points, labelAt: edge.label === undefined ? undefined : midpoint(points) };
    }
    if (moved.has(edge.source) || moved.has(edge.target)) {
      const points = straight(source, target);
      return { ...edge, points, labelAt: edge.label === undefined ? undefined : midpoint(points) };
    }
    return edge;
  });
  return fitted({ ...layout, nodes, edges });
}

/** The drawing's size after the move: everything shifted so the top left sits at the margin, captions included. */
function fitted(layout: Layout): Layout {
  const xs: number[] = [];
  const ys: number[] = [];
  const widthOf = (node: PlacedNode) => (node.shape === 'task' ? node.width : Math.max(node.width, 84));
  for (const node of layout.nodes) {
    const left = node.shape === 'task' ? node.x : node.x + node.width / 2 - widthOf(node) / 2;
    xs.push(left, left + widthOf(node));
    ys.push(node.y, node.y + node.height);
  }
  for (const edge of layout.edges) for (const p of edge.points) { xs.push(p.x); ys.push(p.y); }
  const minX = Math.min(...xs);
  const minY = Math.min(...ys);
  const dx = MARGIN - minX;
  const dy = MARGIN - minY;
  const nodes = layout.nodes.map((node) => ({ ...node, x: node.x + dx, y: node.y + dy }));
  const edges = layout.edges.map((edge) => ({
    ...edge,
    points: edge.points.map((p) => ({ x: p.x + dx, y: p.y + dy })),
    labelAt: edge.labelAt === undefined ? undefined : { x: edge.labelAt.x + dx, y: edge.labelAt.y + dy },
  }));
  return { ...layout, width: Math.ceil(Math.max(...xs) + dx + MARGIN), height: Math.ceil(Math.max(...ys) + dy + MARGIN), nodes, edges };
}
