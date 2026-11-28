import type { Point } from './layout';

export interface Box { x: number; y: number; width: number; height: number }

/** True when two boxes overlap, leaving `pad` clear between them. */
export const overlap = (a: Box, b: Box, pad = 0): boolean => !(a.x + a.width + pad <= b.x || b.x + b.width + pad <= a.x || a.y + a.height + pad <= b.y || b.y + b.height + pad <= a.y);

/** True when a line through these points runs through the box, slanted segments included (a box of a slanted line's bounds would be too wide). */
export function lineThrough(box: Box, points: readonly Point[]): boolean {
  for (let i = 1; i < points.length; i += 1) {
    const a = points[i - 1];
    const b = points[i];
    if (a === undefined || b === undefined) continue;
    const steps = Math.max(1, Math.ceil(Math.hypot(b.x - a.x, b.y - a.y) / 2));
    for (let s = 0; s <= steps; s += 1) {
      const x = a.x + ((b.x - a.x) * s) / steps;
      const y = a.y + ((b.y - a.y) * s) / steps;
      if (x > box.x && x < box.x + box.width && y > box.y && y < box.y + box.height) return true;
    }
  }
  return false;
}
