import type { DiagramGraph } from './bpmnGraph';
import { symbolCentre, type Layout, type PlacedNode, type Point } from './layout';

/*
 * Pools, lanes and message flows drawn around a laid-out definition, read only (#497). The pool is a frame around
 * every step; a lane is a band around the steps it holds; a pool with no process is a bar above or below, in the
 * order of the file; a message flow is a dashed line from the step to that bar. The layout is moved to make room.
 */

export interface PlacedBox { id: string; label: string; x: number; y: number; width: number; height: number; /** For a lane: how many lanes it sits in; its name band is at the left. */ depth?: number }
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
/** The pool's name band and each lane's, vertical at the left edge as BPMN draws them. */
export const POOL_BAND = 28;
export const LANE_BAND = 24;
const LANE_PAD = 12;
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

  // The pool holds notes and groups as well as steps.
  const held = [...layout.nodes, ...(layout.annotations?.items ?? []), ...(layout.groups ?? [])];
  const minX = Math.min(...held.map((n) => n.x));
  const minY = Math.min(...held.map((n) => n.y));
  const maxX = Math.max(...held.map((n) => n.x + n.width));
  const maxY = Math.max(...held.map((n) => n.y + n.height));
  const framed = selfIndex >= 0;
  const pad = framed ? POOL_PAD : LANE_PAD;
  // Room at the left for the pool's band and for one band per level of lanes.
  const depth = new Map<string, number>();
  for (const lane of collaboration.lanes) depth.set(lane.id, lane.parent === undefined ? 0 : (depth.get(lane.parent) ?? 0) + 1);
  const levels = collaboration.lanes.length === 0 ? 0 : Math.max(...depth.values()) + 1;
  const left = (framed ? POOL_BAND : 0) + levels * LANE_BAND;
  const frameWidth = maxX - minX + 2 * pad + left;
  const frameHeight = maxY - minY + 2 * pad;
  const top = MARGIN + above.length * (BAR_HEIGHT + BAR_GAP);
  const dx = MARGIN + pad + left - minX;
  const dy = top + pad - minY;
  const shift = (p: Point): Point => ({ x: p.x + dx, y: p.y + dy });

  const nodes = layout.nodes.map((n) => ({ ...n, x: n.x + dx, y: n.y + dy }));
  const edges = layout.edges.map((e) => ({ ...e, points: e.points.map(shift), labelAt: e.labelAt === undefined ? undefined : shift(e.labelAt) }));
  // Data symbols and their associations move with the steps they hang under.
  const notes = layout.annotations === undefined ? undefined : {
    items: layout.annotations.items.map((item) => ({ ...item, x: item.x + dx, y: item.y + dy })),
    links: layout.annotations.links.map((link) => ({ ...link, points: link.points.map(shift) })),
  };
  const groups = layout.groups?.map((group) => ({ ...group, x: group.x + dx, y: group.y + dy }));
  const data = layout.data === undefined ? undefined : {
    items: layout.data.items.map((item) => ({ ...item, x: item.x + dx, y: item.y + dy })),
    links: layout.data.links.map((link) => ({ ...link, points: link.points.map(shift) })),
  };
  const dataBottom = data === undefined ? 0 : Math.max(0, ...data.items.map((item) => item.y + item.height + item.lines.length * 15 + 24));
  const frameX = MARGIN;
  const pool: PlacedBox | undefined = framed
    ? { id: pools[selfIndex]?.id ?? '', label: pools[selfIndex]?.name ?? graph.name ?? '', x: frameX, y: top, width: frameWidth, height: frameHeight }
    : undefined;
  const blackBoxes: PlacedBox[] = [
    ...above.map((p, i): PlacedBox => ({ id: p.id, label: p.name ?? p.id, x: frameX, y: MARGIN + i * (BAR_HEIGHT + BAR_GAP), width: frameWidth, height: BAR_HEIGHT })),
    ...below.map((p, i): PlacedBox => ({ id: p.id, label: p.name ?? p.id, x: frameX, y: top + frameHeight + BAR_GAP + i * (BAR_HEIGHT + BAR_GAP), width: frameWidth, height: BAR_HEIGHT })),
  ];

  const byId = new Map(nodes.map((n) => [n.id, n]));
  // A lane is a band around the steps it holds, with its name in a vertical band at its left; a lane with child lanes
  // is the box around them, one band further out.
  const lanes: PlacedBox[] = [];
  const rects = new Map<string, PlacedBox>();
  const heldBy = (id: string): PlacedNode[] => {
    const own = collaboration.lanes.find((lane) => lane.id === id)?.nodes.map((ref) => byId.get(ref)).filter((n): n is PlacedNode => n !== undefined) ?? [];
    const below = collaboration.lanes.filter((lane) => lane.parent === id).flatMap((lane) => heldBy(lane.id));
    return [...own, ...below];
  };
  // Lanes of a pool are as wide as one another, so their bands line up at the left.
  const leaves = collaboration.lanes.filter((lane) => !collaboration.lanes.some((other) => other.parent === lane.id) && heldBy(lane.id).length > 0);
  const laneLeft = Math.min(...leaves.map((lane) => Math.min(...heldBy(lane.id).map((n) => n.x)) - LANE_PAD - LANE_BAND));
  const laneRight = Math.max(maxX + dx, ...leaves.map((lane) => Math.max(...heldBy(lane.id).map((n) => n.x + n.width)) + LANE_PAD));
  const place = (id: string): PlacedBox | undefined => {
    const lane = collaboration.lanes.find((candidate) => candidate.id === id);
    if (lane === undefined) return undefined;
    const children = collaboration.lanes.filter((candidate) => candidate.parent === id).map((child) => place(child.id)).filter((box): box is PlacedBox => box !== undefined);
    const level = depth.get(id) ?? 0;
    let box: PlacedBox;
    if (children.length > 0) {
      const x = Math.min(...children.map((c) => c.x)) - LANE_BAND;
      const y = Math.min(...children.map((c) => c.y));
      box = { id, label: lane.name ?? id, x, y, width: Math.max(...children.map((c) => c.x + c.width)) - x, height: Math.max(...children.map((c) => c.y + c.height)) - y, depth: level };
    } else {
      const held = heldBy(id);
      if (held.length === 0) return undefined;
      const x = laneLeft - (level > 0 ? 0 : 0);
      const y = Math.min(...held.map((n) => n.y)) - LANE_PAD;
      box = { id, label: lane.name ?? id, x, y, width: laneRight - x, height: Math.max(...held.map((n) => n.y + n.height)) + LANE_PAD - y, depth: level };
    }
    rects.set(id, box);
    return box;
  };
  for (const lane of collaboration.lanes) if (lane.parent === undefined) place(lane.id);
  lanes.push(...[...rects.values()].sort((a, b) => (a.depth ?? 0) - (b.depth ?? 0)));

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

  const bottom = Math.max(top + frameHeight, dataBottom, ...blackBoxes.map((b) => b.y + b.height)) + MARGIN;
  const width = Math.max(frameWidth + 2 * MARGIN, data === undefined ? 0 : Math.max(...data.items.map((item) => item.x + item.width)) + MARGIN);
  return { layout: { ...layout, nodes, edges, width, height: bottom, ...(data !== undefined ? { data } : {}), ...(notes !== undefined ? { annotations: notes } : {}), ...(groups !== undefined ? { groups } : {}) }, pool, lanes, blackBoxes, flows };
}
