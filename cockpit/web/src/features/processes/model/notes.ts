import type { DiagramGraph } from './bpmnGraph';
import { footprint, type Rect } from './edgeLabels';
import { SYMBOL_SIZE, wrapWords, type Layout, type PlacedNode, type Point } from './layout';

/*
 * Text annotations and groups (#209). A note is an open bracket with its text, joined to the element it notes by a
 * dotted association; a group is a dashed frame with the name of its category value. Where the model's diagram gives
 * bounds the note or group is drawn there; a note without bounds is set beside the element it notes, at the first spot
 * clear of every step, caption, data symbol and other note. A group with no bounds has no place the cockpit can
 * know (BPMN keeps no member list for it), so it is left out of an automatic layout.
 */

export interface PlacedAnnotation { id: string; text: string; lines: string[]; x: number; y: number; width: number; height: number }
export interface PlacedAnnotationLink { id: string; annotation: string; target: string; points: Point[] }
export interface PlacedGroup { id: string; label: string | undefined; x: number; y: number; width: number; height: number }
export interface Notes { items: PlacedAnnotation[]; links: PlacedAnnotationLink[] }

const NOTE_WIDTH = 150;
const NOTE_PAD = 8;
const NOTE_PAD_Y = 4;
const NOTE_LINE = 15;
const NOTE_CHAR = 6.8;
const NOTE_GAP = 14;

/**
 * A note's lines and size. With `height` (the diagram's bounds) the box keeps that size: the text wraps at word
 * boundaries inside the bracket and, if it needs more lines than fit, the last line ends with an ellipsis (the full
 * text stays in the tooltip). Without `height` the box grows to hold every line.
 */
export function noteBox(text: string, width = NOTE_WIDTH, height = 0): { lines: string[]; width: number; height: number } {
  const w = Math.max(60, width);
  const room = w - 2 * NOTE_PAD;
  const perLine = Math.max(4, Math.floor(room / NOTE_CHAR));
  const wrapped = wrapWords(text, room, NOTE_CHAR);
  const rows = height > 0 ? Math.max(1, Math.floor((height - 2 * NOTE_PAD_Y) / NOTE_LINE)) : wrapped.length;
  const kept = wrapped.slice(0, rows);
  if (wrapped.length > rows) kept[rows - 1] = `${(kept[rows - 1] ?? '').slice(0, perLine - 1).trimEnd()}…`;
  const lines = kept.map((line) => (line.length > perLine ? `${line.slice(0, perLine - 1)}…` : line));
  return { lines, width: w, height: height > 0 ? height : 2 * NOTE_PAD_Y + lines.length * NOTE_LINE };
}

const apart = (a: Rect, b: Rect, pad = 0) => a.x + a.width + pad <= b.x || b.x + b.width + pad <= a.x || a.y + a.height + pad <= b.y || b.y + b.height + pad <= a.y;

/** The box of the element itself (a symbol without its caption). */
function bodyOf(node: PlacedNode): Rect {
  if (node.shape === 'task') return { x: node.x, y: node.y, width: node.width, height: node.height };
  return { x: node.x + node.width / 2 - SYMBOL_SIZE / 2, y: node.y, width: SYMBOL_SIZE, height: SYMBOL_SIZE };
}

/** Where the line from the middle of `box` toward `toward` leaves it. */
function leaves(box: Rect, toward: Point): Point {
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  const dx = toward.x - cx;
  const dy = toward.y - cy;
  const scale = Math.min(dx === 0 ? Infinity : box.width / 2 / Math.abs(dx), dy === 0 ? Infinity : box.height / 2 / Math.abs(dy));
  return Number.isFinite(scale) ? { x: cx + dx * scale, y: cy + dy * scale } : { x: cx, y: cy };
}

/** The side of a note that faces the element, and the point on it the association leaves from. */
export function attachPoint(note: Rect, target: Rect): { at: Point; side: 'left' | 'right' | 'top' | 'bottom' } {
  if (note.x >= target.x + target.width) return { at: { x: note.x, y: note.y + note.height / 2 }, side: 'left' };
  if (note.x + note.width <= target.x) return { at: { x: note.x + note.width, y: note.y + note.height / 2 }, side: 'right' };
  if (note.y >= target.y + target.height) return { at: { x: note.x + note.width / 2, y: note.y }, side: 'top' };
  return { at: { x: note.x + note.width / 2, y: note.y + note.height }, side: 'bottom' };
}

function linkPoints(note: PlacedAnnotation, target: PlacedNode): Point[] {
  const body = bodyOf(target);
  const { at } = attachPoint(note, body);
  return [at, leaves(body, at)];
}

/** The layout with every note placed and joined, and its drawing extent grown to hold them. */
export function placeNotes(layout: Layout, graph: DiagramGraph): Layout {
  const notes = graph.annotations;
  if (notes === undefined || notes.length === 0) return layout;
  const byId = new Map(layout.nodes.map((node) => [node.id, node]));
  const items: PlacedAnnotation[] = [...(layout.annotations?.items ?? [])];
  const placed = new Set(items.map((item) => item.id));
  const targetOf = new Map((graph.annotationLinks ?? []).map((link) => [link.annotation, link.target]));
  const obstacles = (): Rect[] => [
    ...layout.nodes.filter((node) => node.container?.open !== true).map(footprint),
    ...(layout.data?.items ?? []).map((item) => ({ x: item.x - 4, y: item.y, width: item.width + 8, height: item.height + 40 })),
    ...items,
  ];
  const free = (rect: Rect) => obstacles().every((box) => apart(rect, box, 6)) && rect.x >= 4 && rect.y >= 4;
  for (const note of notes) {
    if (placed.has(note.id)) continue;
    const size = noteBox(note.text);
    const target = byId.get(targetOf.get(note.id) ?? '');
    const anchor = target === undefined ? undefined : bodyOf(target);
    const spots: Rect[] = [];
    if (anchor !== undefined) {
      const right = anchor.x + anchor.width + NOTE_GAP;
      const left = anchor.x - NOTE_GAP - size.width;
      for (let drop = 0; drop <= 4; drop += 1) {
        const rise = drop * 24;
        spots.push({ x: right, y: anchor.y - size.height - 6 - rise, width: size.width, height: size.height });
        spots.push({ x: right, y: anchor.y + anchor.height + 6 + rise, width: size.width, height: size.height });
        spots.push({ x: left, y: anchor.y + anchor.height + 6 + rise, width: size.width, height: size.height });
        spots.push({ x: left, y: anchor.y - size.height - 6 - rise, width: size.width, height: size.height });
      }
    }
    const fallbackY = Math.max(0, ...layout.nodes.map((node) => node.y + node.height)) + NOTE_GAP + items.length * 8;
    const rect = spots.find(free) ?? spots.find((spot) => spot.x >= 4 && spot.y >= 4) ?? { x: 12, y: fallbackY, width: size.width, height: size.height };
    items.push({ id: note.id, text: note.text, lines: size.lines, x: rect.x, y: rect.y, width: rect.width, height: rect.height });
  }
  const links: PlacedAnnotationLink[] = [];
  for (const link of graph.annotationLinks ?? []) {
    const item = items.find((candidate) => candidate.id === link.annotation);
    const target = byId.get(link.target);
    if (item !== undefined && target !== undefined) links.push({ id: link.id, annotation: link.annotation, target: link.target, points: linkPoints(item, target) });
  }
  const width = Math.max(layout.width, Math.ceil(Math.max(...items.map((item) => item.x + item.width)) + 12));
  const height = Math.max(layout.height, Math.ceil(Math.max(...items.map((item) => item.y + item.height)) + 12));
  return { ...layout, width, height, annotations: { items, links } };
}
