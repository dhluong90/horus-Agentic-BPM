import { CAPTION_GAP, CAPTION_LINE_HEIGHT, EVENT_RADIUS, GATEWAY_HALF, SYMBOL_SIZE, symbolCentre, type PlacedEdge, type PlacedNode } from './layout';
import { lineThrough, overlap, type Box } from './lineHits';

/*
 * Where the name of an event or a gateway stands. By default an event's name hangs under its circle and a gateway's
 * stands to the lower right of its diamond. When a flow runs through that place, the name moves to the first of the
 * other sides that is clear of every flow, step and name; when none is, it stays where it is by default.
 */

/** A name's place: the first baseline and the anchor. On a node it is kept relative to the node's own corner, so moving the node moves its name. */
export interface CaptionAt { x: number; y: number; anchor: 'start' | 'middle' | 'end' }

/** The width of a character at the caption size, as drawn (a little under the 6.8 the wrap allows, so a name is not judged wider than it is). */
const CHAR = 6.1;
const ASCENT = 12;

/** Where a symbol's name stands unless something is in the way: `y` is the baseline of its first line. */
export function defaultCaption(node: PlacedNode): CaptionAt {
  const centre = symbolCentre(node);
  const y = node.y + SYMBOL_SIZE + CAPTION_GAP + ASCENT;
  return node.shape === 'gateway' ? { x: centre.x + 8, y, anchor: 'start' } : { x: centre.x, y, anchor: 'middle' };
}

export const captionOf = (node: PlacedNode): CaptionAt => (node.captionAt === undefined ? defaultCaption(node) : { ...node.captionAt, x: node.x + node.captionAt.x, y: node.y + node.captionAt.y });

/** The box the name takes. */
export function captionBox(node: PlacedNode, at: CaptionAt = captionOf(node)): Box {  // `at` is absolute
  const width = Math.max(0, ...node.lines.map((line) => line.length)) * CHAR;
  const x = at.anchor === 'start' ? at.x : at.anchor === 'end' ? at.x - width : at.x - width / 2;
  return { x, y: at.y - ASCENT, width, height: node.lines.length * CAPTION_LINE_HEIGHT };
}

function alternatives(node: PlacedNode): CaptionAt[] {
  const c = symbolCentre(node);
  const rows = node.lines.length;
  const reach = node.shape === 'gateway' ? GATEWAY_HALF : EVENT_RADIUS;
  const beside = c.y + 4 - ((rows - 1) * CAPTION_LINE_HEIGHT) / 2;
  const below = defaultCaption(node).y;
  const list: CaptionAt[] = [];
  // Above the symbol, then to either side, then below and above again at growing distances, each leaning every way.
  for (const lift of [0, 14, 28]) {
    const above = node.y - 6 - lift - (rows - 1) * CAPTION_LINE_HEIGHT;
    list.push({ x: c.x, y: above, anchor: 'middle' }, { x: c.x + 8, y: above, anchor: 'start' }, { x: c.x - 8, y: above, anchor: 'end' });
    if (lift === 0) list.push({ x: c.x + reach + 6, y: beside, anchor: 'start' }, { x: c.x - reach - 6, y: beside, anchor: 'end' });
  }
  for (const drop of [0, 14, 28]) list.push({ x: c.x, y: below + drop, anchor: 'middle' }, { x: c.x + 8, y: below + drop, anchor: 'start' }, { x: c.x - 8, y: below + drop, anchor: 'end' });
  return list;
}

/** The nodes with their names moved where a flow would run through them; nodes whose name is clear are returned as they are. */
export function placeCaptions(nodes: readonly PlacedNode[], edges: readonly PlacedEdge[]): PlacedNode[] {
  const symbolOf = (node: PlacedNode): Box => (node.shape === 'task' ? node : { x: node.x, y: node.y, width: node.width, height: SYMBOL_SIZE });
  const current = new Map<string, CaptionAt>();
  for (const node of nodes) if (node.shape !== 'task' && node.lines.length > 0) current.set(node.id, defaultCaption(node));
  const clear = (node: PlacedNode, at: CaptionAt): boolean => {
    const box = captionBox(node, at);
    if (edges.some((edge) => lineThrough({ x: box.x + 2, y: box.y + 3, width: box.width - 4, height: box.height - 6 }, edge.points))) return false;
    return !nodes.some((other) => {
      if (other.id === node.id || other.container?.open === true) return false;
      if (overlap(box, symbolOf(other), 2)) return true;
      const theirs = current.get(other.id);
      return theirs !== undefined && overlap(box, captionBox(other, theirs), 2);
    });
  };
  const moved = new Map<string, CaptionAt>();
  for (const node of nodes) {
    const at = current.get(node.id);
    if (at === undefined || clear(node, at)) continue;
    const found = alternatives(node).find((candidate) => clear(node, candidate));
    if (found !== undefined) { current.set(node.id, found); moved.set(node.id, { ...found, x: found.x - node.x, y: found.y - node.y }); }
  }
  return nodes.map((node) => {
    const at = moved.get(node.id);
    return at === undefined ? node : { ...node, captionAt: at };
  });
}
