import type { DiagramGraph } from './bpmnGraph';
import { symbolCentre, type Layout, type PlacedNode, type Point } from './layout';

/*
 * Pools, lanes and message flows drawn around a laid-out definition, read only (#497). The pool is a frame around
 * every step; a lane is a band around the steps it holds; a pool with no process is a bar above or below, in the
 * order of the file; a message flow is a dashed line from the step to that bar. The layout is moved to make room.
 */

export interface PlacedBox { id: string; label: string; x: number; y: number; width: number; height: number }
export interface PlacedMessageFlow { id: string; label: string | undefined; points: Point[]; labelAt: Point }
export interface CollaborationDrawing {
  layout: Layout;
  pool: PlacedBox | undefined;
  lanes: PlacedBox[];
  blackBoxes: PlacedBox[];
  flows: PlacedMessageFlow[];
}

const MARGIN = 16;
const POOL_PAD = 28;
export const POOL_HEADER = 26;
const LANE_PAD = 12;
const LANE_HEADER = 18;
const BAR_HEIGHT = 30;
const BAR_GAP = 56;

function centreX(node: PlacedNode): number {
  return node.shape === 'task' ? node.x + node.width / 2 : symbolCentre(node).x;
}

/** The layout moved and enlarged for the collaboration of the graph, or undefined when the graph has none. */
export function placeCollaboration(layout: Layout, graph: DiagramGraph): CollaborationDrawing | undefined {
  const collaboration = graph.collaboration;
  if (collaboration === undefined || layout.nodes.length === 0) return undefined;
  const pools = collaboration.pools;
  const selfIndex = pools.findIndex((pool) => pool.self);
  const others = pools.filter((pool) => !pool.self);
  const above = others.filter((pool) => selfIndex < 0 || pools.indexOf(pool) < selfIndex);
  const below = others.filter((pool) => !above.includes(pool));

  const minX = Math.min(...layout.nodes.map((n) => n.x));
  const minY = Math.min(...layout.nodes.map((n) => n.y));
  const maxX = Math.max(...layout.nodes.map((n) => n.x + n.width));
  const maxY = Math.max(...layout.nodes.map((n) => n.y + n.height));
  const framed = selfIndex >= 0;
  const pad = framed ? POOL_PAD : LANE_PAD;
  const frameWidth = maxX - minX + 2 * pad;
  const frameHeight = maxY - minY + 2 * pad + (framed ? POOL_HEADER : 0);
  const top = MARGIN + above.length * (BAR_HEIGHT + BAR_GAP);
  const dx = MARGIN + pad - minX;
  const dy = top + pad + (framed ? POOL_HEADER : 0) - minY;
  const shift = (p: Point): Point => ({ x: p.x + dx, y: p.y + dy });

  const nodes = layout.nodes.map((n) => ({ ...n, x: n.x + dx, y: n.y + dy }));
  const edges = layout.edges.map((e) => ({ ...e, points: e.points.map(shift), labelAt: e.labelAt === undefined ? undefined : shift(e.labelAt) }));
  const frameX = MARGIN;
  const pool: PlacedBox | undefined = framed
    ? { id: pools[selfIndex]?.id ?? '', label: pools[selfIndex]?.name ?? graph.name ?? '', x: frameX, y: top, width: frameWidth, height: frameHeight }
    : undefined;
  const blackBoxes: PlacedBox[] = [
    ...above.map((p, i): PlacedBox => ({ id: p.id, label: p.name ?? p.id, x: frameX, y: MARGIN + i * (BAR_HEIGHT + BAR_GAP), width: frameWidth, height: BAR_HEIGHT })),
    ...below.map((p, i): PlacedBox => ({ id: p.id, label: p.name ?? p.id, x: frameX, y: top + frameHeight + BAR_GAP + i * (BAR_HEIGHT + BAR_GAP), width: frameWidth, height: BAR_HEIGHT })),
  ];

  const byId = new Map(nodes.map((n) => [n.id, n]));
  const lanes: PlacedBox[] = [];
  for (const lane of collaboration.lanes) {
    const held = lane.nodes.map((id) => byId.get(id)).filter((n): n is PlacedNode => n !== undefined);
    if (held.length === 0) continue;
    const x = Math.min(...held.map((n) => n.x)) - LANE_PAD;
    const y = Math.min(...held.map((n) => n.y)) - LANE_PAD - LANE_HEADER;
    lanes.push({ id: lane.id, label: lane.name ?? lane.id, x, y, width: Math.max(...held.map((n) => n.x + n.width)) + LANE_PAD - x, height: Math.max(...held.map((n) => n.y + n.height)) + LANE_PAD - y });
  }

  const flows: PlacedMessageFlow[] = [];
  for (const flow of collaboration.messageFlows) {
    const sourceNode = byId.get(flow.source);
    const targetNode = byId.get(flow.target);
    const sourceBar = blackBoxes.find((b) => b.id === flow.source);
    const targetBar = blackBoxes.find((b) => b.id === flow.target);
    const node = sourceNode ?? targetNode;
    const bar = targetBar ?? sourceBar;
    if (node === undefined || bar === undefined) continue;
    const up = bar.y < node.y;
    const x = Math.min(Math.max(centreX(node), bar.x + 12), bar.x + bar.width - 12);
    const here: Point = { x: centreX(node), y: up ? node.y : node.y + node.height };
    const there: Point = { x, y: up ? bar.y + bar.height : bar.y };
    const points = sourceNode !== undefined ? [here, there] : [there, here];
    const label = flow.label === undefined ? undefined : flow.correlationKey === undefined ? flow.label : `${flow.label} (${flow.correlationKey})`;
    flows.push({ id: flow.id, label, points, labelAt: { x: (here.x + there.x) / 2, y: (here.y + there.y) / 2 } });
  }

  const bottom = Math.max(top + frameHeight, ...blackBoxes.map((b) => b.y + b.height)) + MARGIN;
  return { layout: { ...layout, nodes, edges, width: frameWidth + 2 * MARGIN, height: bottom }, pool, lanes, blackBoxes, flows };
}
