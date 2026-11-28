import { CAPTION_LINE_HEIGHT, CAPTION_WIDTH, SYMBOL_SIZE, type Layout, type PlacedNode, type Point } from './layout';
import type { CollaborationDrawing } from './collaborationLayout';

/*
 * The box that holds everything drawn, for "Fit": every step and its caption, boundary events, flows and their label
 * pills, data symbols with their captions and associations, pools, lanes and black-box pools, message flows and the
 * badges of a case. The layout's own width and height are only the steps' extent, so a fit taken from them crops
 * whatever hangs outside it.
 */

export interface Box { x: number; y: number; width: number; height: number }

/** Room kept around the drawing inside the frame, in drawing units. */
export const FIT_PADDING = 24;

const LABEL_CHAR = 6.8;
const LABEL_HALF_HEIGHT = 10;
const DATA_CAPTION_WIDTH = 104;
const DATA_CAPTION_TOP = 16;
const DESCENT = 5;
/** A badge sits above its box; the tallest (a done mark) reaches this high above the box's top edge. */
const BADGE_RISE = 40;

function symbolReach(node: PlacedNode): Box {
  // A caption under an event or gateway is wider than the symbol and centred on it.
  const half = Math.max(SYMBOL_SIZE, CAPTION_WIDTH) / 2;
  const centre = node.x + node.width / 2;
  return { x: centre - half, y: node.y, width: 2 * half, height: node.height + DESCENT };
}

/** The boxes of everything drawn, before padding. */
export function drawnBoxes(layout: Layout, collaboration?: CollaborationDrawing, badges: readonly Box[] = []): Box[] {
  const boxes: Box[] = [];
  const point = (p: Point) => boxes.push({ x: p.x, y: p.y, width: 0, height: 0 });
  const label = (at: Point | undefined, text: string | undefined) => {
    if (at === undefined || text === undefined) return;
    const half = (Math.ceil(text.length * LABEL_CHAR) + 14) / 2;
    boxes.push({ x: at.x - half, y: at.y - LABEL_HALF_HEIGHT, width: 2 * half, height: 2 * LABEL_HALF_HEIGHT });
  };
  for (const node of layout.nodes) boxes.push(node.shape === 'task' ? node : symbolReach(node));
  for (const edge of layout.edges) { edge.points.forEach(point); label(edge.labelAt, edge.label); }
  for (const item of layout.data?.items ?? []) {
    boxes.push({ x: item.x, y: item.y, width: item.width, height: item.height });
    const centre = item.x + item.width / 2;
    boxes.push({ x: centre - DATA_CAPTION_WIDTH / 2, y: item.y + item.height, width: DATA_CAPTION_WIDTH, height: DATA_CAPTION_TOP + item.lines.length * CAPTION_LINE_HEIGHT + DESCENT });
  }
  for (const link of layout.data?.links ?? []) link.points.forEach(point);
  for (const note of layout.annotations?.items ?? []) boxes.push({ x: note.x, y: note.y, width: note.width, height: note.height });
  for (const link of layout.annotations?.links ?? []) link.points.forEach(point);
  boxes.push(...(layout.groups ?? []));
  if (collaboration !== undefined) {
    if (collaboration.pool !== undefined) boxes.push(collaboration.pool);
    boxes.push(...collaboration.lanes, ...collaboration.blackBoxes);
    for (const flow of collaboration.flows) { flow.points.forEach(point); label(flow.labelAt, flow.label); }
  }
  boxes.push(...badges);
  return boxes;
}

/** The box that holds every drawn element, grown by `padding` on each side; never smaller than the layout's own extent from the origin. */
export function drawingBounds(layout: Layout, collaboration?: CollaborationDrawing, badges: readonly Box[] = [], padding = FIT_PADDING): Box {
  const boxes = drawnBoxes(layout, collaboration, badges);
  if (boxes.length === 0) return { x: 0, y: 0, width: Math.max(1, layout.width), height: Math.max(1, layout.height) };
  const left = Math.min(...boxes.map((b) => b.x));
  const top = Math.min(...boxes.map((b) => b.y));
  const right = Math.max(...boxes.map((b) => b.x + b.width));
  const bottom = Math.max(...boxes.map((b) => b.y + b.height));
  return { x: Math.floor(left - padding), y: Math.floor(top - padding), width: Math.ceil(right - left + 2 * padding), height: Math.ceil(bottom - top + 2 * padding) };
}

/** Where a badge is drawn: across the top edge of a box, or over an event or gateway; `width` is the badge's own. */
export function badgeBox(node: PlacedNode, width: number): Box {
  const centre = node.x + node.width / 2;
  const left = node.shape === 'task' ? node.x + 6 : centre - width / 2;
  const right = Math.max(left + width, node.x + node.width);
  return { x: Math.min(left, node.x), y: node.y - BADGE_RISE, width: right - Math.min(left, node.x), height: BADGE_RISE };
}
