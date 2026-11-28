import { describe, expect, it } from 'vitest';
import { SYMBOL_STRUCTURE_BPMN, SYMBOL_STRUCTURE_LAYOUT } from '../../../mocks/symbolStructure';
import { readBpmnGraph } from './bpmnGraph';
import { placeCollaboration } from './collaborationLayout';
import { footprint } from './edgeLabels';
import { layoutGraph } from './layout';
import { nodeTypeLabel } from './nodeLabels';

const graph = readBpmnGraph(SYMBOL_STRUCTURE_BPMN);
const stored = { ...graph, stored: SYMBOL_STRUCTURE_LAYOUT };
const hits = (a: { x: number; y: number; width: number; height: number }, b: typeof a) => a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;

describe('structure symbols (#209)', () => {
  it('CKP_209_a_receive_task_that_instantiates_is_its_own_known_type', () => {
    const node = graph.nodes.find((n) => n.id === 'receive-order');
    expect(node?.type).toBe('receiveTask:instantiate');
    expect(nodeTypeLabel('receiveTask:instantiate', (key) => key)).toBe('node.receiveTaskStart');
  });

  it('CKP_209_notes_and_groups_are_read_and_their_associations_are_not_flows', () => {
    expect(graph.annotations?.map((a) => a.id)).toEqual(['note-credit', 'note-invoice']);
    expect(graph.annotations?.[0]?.text).toContain('Rule Engine');
    expect(graph.annotationLinks).toEqual([
      { id: 'assoc-credit', annotation: 'note-credit', target: 'check-credit' },
      { id: 'assoc-invoice', annotation: 'note-invoice', target: 'invoice' },
    ]);
    expect(graph.edges.some((e) => e.isAssociation === true)).toBe(false);
    expect(graph.groups).toEqual([{ id: 'group-fraud', label: 'Fraud screening' }]);
  });

  it('CKP_209_a_child_lane_names_its_parent', () => {
    const lanes = graph.collaboration?.lanes ?? [];
    expect(lanes.find((l) => l.id === 'lane-front')?.parent).toBe('lane-sales');
    expect(lanes.find((l) => l.id === 'lane-sales')?.parent).toBeUndefined();
  });

  it('CKP_209_a_note_the_diagram_places_is_drawn_there_and_the_other_is_placed_clear_of_steps', () => {
    const layout = layoutGraph(stored);
    const items = layout.annotations?.items ?? [];
    expect(items).toHaveLength(2);
    const given = items.find((i) => i.id === 'note-invoice');
    const auto = items.find((i) => i.id === 'note-credit');
    const check = layout.nodes.find((n) => n.id === 'check-credit');
    if (!given || !auto || !check) throw new Error('missing');
    // The diagram's note keeps its offset from the step it sits by (620 - 460 = 160, 300 - 360 = -60).
    const invoice = layout.nodes.find((n) => n.id === 'invoice');
    expect(given.x - (invoice?.x ?? 0)).toBeCloseTo(160, 5);
    expect(given.y - (invoice?.y ?? 0)).toBeCloseTo(-60, 5);
    for (const node of layout.nodes) expect(hits(auto, footprint(node)), `note meets ${node.id}`).toBe(false);
    expect(hits(auto, given)).toBe(false);
    expect(layout.annotations?.links).toHaveLength(2);
    for (const link of layout.annotations?.links ?? []) expect(link.points).toHaveLength(2);
  });

  it('CKP_209_a_group_is_drawn_at_the_bounds_of_the_diagram_and_a_note_places_in_an_automatic_layout', () => {
    const layout = layoutGraph(stored);
    expect(layout.groups).toHaveLength(1);
    expect(layout.groups?.[0]?.label).toBe('Fraud screening');
    const auto = layoutGraph(graph);
    expect(auto.groups).toBeUndefined();
    expect(auto.annotations?.items).toHaveLength(2);
  });

  it('CKP_209_lanes_are_vertical_bands_with_the_child_lanes_inside_their_parent', () => {
    const drawing = placeCollaboration(layoutGraph(stored), stored);
    const lane = (id: string) => drawing?.lanes.find((l) => l.id === id);
    const sales = lane('lane-sales');
    const front = lane('lane-front');
    const back = lane('lane-back');
    const finance = lane('lane-finance');
    if (!sales || !front || !back || !finance || drawing?.pool === undefined) throw new Error('missing');
    for (const child of [front, back]) {
      expect(child.x).toBeGreaterThan(sales.x);
      expect(child.x + child.width).toBeLessThanOrEqual(sales.x + sales.width);
      expect(child.y).toBeGreaterThanOrEqual(sales.y);
      expect(child.y + child.height).toBeLessThanOrEqual(sales.y + sales.height);
    }
    expect(front.depth).toBe(1);
    expect(sales.depth).toBe(0);
    expect(front.x).toBeCloseTo(back.x, 5);
    expect(sales.x).toBeGreaterThan(drawing.pool.x);
    // Every lane and the pool's name sit in a band at the left, inside the pool.
    for (const box of drawing.lanes) {
      expect(box.x).toBeGreaterThanOrEqual(drawing.pool.x);
      expect(box.x + box.width).toBeLessThanOrEqual(drawing.pool.x + drawing.pool.width);
    }
  });
});
