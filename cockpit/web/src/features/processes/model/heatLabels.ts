import type { PlacedEdge, PlacedNode } from './layout';

/** Width of one character of the figure under a step, in diagram units, and the height of its line. */
const CHAR = 6.8;
const PAD = 4;
const LINE = 14;
/** Where the figure sits under its step: the same offset the drawing uses. */
const GAP = 4;

interface Rect { x: number; y: number; width: number; height: number }

const hits = (a: Rect, b: Rect): boolean => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;

function crossesLine(rect: Rect, points: readonly { x: number; y: number }[]): boolean {
  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1];
    const b = points[i];
    if (a === undefined || b === undefined) continue;
    const steps = Math.max(1, Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / 3));
    for (let s = 0; s <= steps; s += 1) {
      const x = a.x + ((b.x - a.x) * s) / steps;
      const y = a.y + ((b.y - a.y) * s) / steps;
      if (x > rect.x && x < rect.x + rect.width && y > rect.y && y < rect.y + rect.height) return true;
    }
  }
  return false;
}

/**
 * The steps whose heat figure has no room under them (#688): the figure would sit on another step, on a flow line or on
 * another figure. Those figures are not drawn by default and appear when the step is hovered or focused; the step's
 * accessible name carries the figure either way. Steps are taken top to bottom, so the ones kept are stable.
 */
export function crowdedHeatLabels(nodes: readonly PlacedNode[], edges: readonly PlacedEdge[], figures: ReadonlyMap<string, string>): Set<string> {
  const crowded = new Set<string>();
  const kept: Rect[] = [];
  const ordered = nodes.filter((node) => figures.has(node.id)).sort((a, b) => a.y - b.y || a.x - b.x);
  for (const node of ordered) {
    const text = figures.get(node.id) ?? '';
    const width = text.length * CHAR + PAD * 2;
    const rect: Rect = { x: node.x + node.width / 2 - width / 2, y: node.y + node.height + GAP, width, height: LINE };
    const onStep = nodes.some((other) => other.id !== node.id && hits(rect, other));
    const onLine = edges.some((edge) => crossesLine(rect, edge.points));
    const onFigure = kept.some((other) => hits(rect, other));
    if (onStep || onLine || onFigure) crowded.add(node.id);
    else kept.push(rect);
  }
  return crowded;
}
