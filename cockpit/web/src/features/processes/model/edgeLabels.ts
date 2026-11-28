import { CAPTION_GAP, GATEWAY_HALF, CAPTION_LINE_HEIGHT, SYMBOL_SIZE, type PlacedEdge, type PlacedNode, type Point } from './layout';

/*
 * Where the name of a flow is drawn on a stored layout. The diagram's own label bounds win when it has them. Without
 * them the name is set beside the flow, a fixed gap off the line, at the first spot that is clear of every step and
 * its caption, of the names already placed, and of the other flows.
 */

export interface Rect { x: number; y: number; width: number; height: number }

const CHAR = 6.8;
const PILL_PAD = 14;
const PILL_HEIGHT = 20;
const GAPS = [6, 14, 26, 38, 50] as const;
const SHIFT = 8;
const REACH = 12;

export const labelSize = (text: string) => ({ width: Math.ceil(text.length * CHAR) + PILL_PAD, height: PILL_HEIGHT });

/** The room a step takes: its box, or for an event or gateway the symbol and the caption hanging under it. */
export function footprint(node: PlacedNode): Rect {
  if (node.shape === 'task') return { x: node.x, y: node.y, width: node.width, height: node.height };
  const cx = node.x + node.width / 2;
  const caption = node.lines.length === 0 ? 0 : CAPTION_GAP + node.lines.length * CAPTION_LINE_HEIGHT + 4;
  const width = node.lines.length === 0 ? SYMBOL_SIZE : Math.max(SYMBOL_SIZE, 84);
  return { x: cx - width / 2, y: node.y, width, height: SYMBOL_SIZE + caption };
}

/** A gateway is a diamond: the corners of its box are free, so a name may tuck in beside it. */
function diamondApart(rect: Rect, node: PlacedNode, pad: number): boolean {
  const cx = node.x + node.width / 2;
  const cy = node.y + SYMBOL_SIZE / 2;
  const nx = Math.max(rect.x, Math.min(cx, rect.x + rect.width));
  const ny = Math.max(rect.y, Math.min(cy, rect.y + rect.height));
  return Math.abs(nx - cx) + Math.abs(ny - cy) >= GATEWAY_HALF + pad;
}

const apart = (a: Rect, b: Rect, pad = 0) => a.x + a.width + pad <= b.x || b.x + b.width + pad <= a.x || a.y + a.height + pad <= b.y || b.y + b.height + pad <= a.y;

/** Flows run along rows and columns, so a segment is a thin box. */
function crosses(rect: Rect, points: readonly Point[]): boolean {
  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1];
    const b = points[i];
    if (a === undefined || b === undefined) continue;
    const box = { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), width: Math.abs(a.x - b.x), height: Math.abs(a.y - b.y) };
    if (!apart(rect, box)) return true;
  }
  return false;
}

export function placeLabels(nodes: readonly PlacedNode[], edges: readonly PlacedEdge[], given: Readonly<Record<string, Rect>> = {}): PlacedEdge[] {
  const solid = nodes.filter((node) => node.container?.open !== true);
  const clearOfSteps = (rect: Rect) => solid.every((node) => {
    if (node.shape !== 'gateway') return apart(rect, footprint(node), 2);
    // The diamond itself, then the caption hanging under it.
    const box = footprint(node);
    return diamondApart(rect, node, 2) && (node.lines.length === 0 || apart(rect, { x: box.x, y: node.y + SYMBOL_SIZE, width: box.width, height: box.height - SYMBOL_SIZE }, 2));
  });
  const taken: Rect[] = [];
  const result = new Map<string, PlacedEdge>();
  // Names the diagram places come first, so the ones this places step around them.
  for (const edge of edges) {
    const bounds = edge.label === undefined ? undefined : given[`${edge.source}->${edge.target}`];
    if (bounds === undefined) continue;
    const size = labelSize(edge.label ?? '');
    const width = Math.max(bounds.width, size.width);
    const height = Math.max(bounds.height, size.height);
    const centre = { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
    taken.push({ x: centre.x - width / 2, y: centre.y - height / 2, width, height });
    result.set(edge.id, { ...edge, labelAt: centre });
  }
  for (const edge of edges) {
    if (edge.label === undefined || result.has(edge.id)) continue;
    const { width, height } = labelSize(edge.label);
    const others = edges.filter((other) => other.id !== edge.id);
    const fits = (rect: Rect) => clearOfSteps(rect) && taken.every((box) => apart(rect, box, 4)) && others.every((other) => !crosses(rect, other.points)) && !crosses(rect, edge.points);
    // Every spot beside a segment, nearest to its middle and to the line first; the first clear one wins.
    const spots: { rect: Rect; cost: number }[] = [];
    for (let i = 1; i < edge.points.length; i += 1) {
      const a = edge.points[i - 1];
      const b = edge.points[i];
      if (a === undefined || b === undefined) continue;
      const horizontal = Math.abs(a.y - b.y) <= Math.abs(a.x - b.x);
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      for (const gap of GAPS) {
        for (let step = -REACH; step <= REACH; step += 1) {
          for (const side of [-1, 1]) {
            const along = step * SHIFT;
            const rect: Rect = horizontal
              ? { x: mid.x + along - width / 2, y: side < 0 ? mid.y - gap - height : mid.y + gap, width, height }
              : { x: side < 0 ? mid.x - gap - width : mid.x + gap, y: mid.y + along - height / 2, width, height };
            spots.push({ rect, cost: (i - 1) * 60 + gap * 3 + Math.abs(along) + (side > 0 ? 1 : 0) });
          }
        }
      }
    }
    spots.sort((x, y) => x.cost - y.cost);
    const chosen = spots.find((spot) => fits(spot.rect))?.rect;
    const first = spots[0]?.rect;
    const rect = chosen ?? first;
    if (rect === undefined) { result.set(edge.id, edge); continue; }
    taken.push(rect);
    result.set(edge.id, { ...edge, labelAt: { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 } });
  }
  return edges.map((edge) => result.get(edge.id) ?? edge);
}
