import { describe, expect, it } from 'vitest';
import { SYMBOL_GALLERY_BPMN, SYMBOL_GALLERY_LAYOUT } from '../../../mocks/symbolGallery';
import { isKnownNodeType, readBpmnGraph } from './bpmnGraph';
import { footprint, labelSize } from './edgeLabels';
import { layoutGraph, overlaps, wrapWords } from './layout';
import { nodeTypeLabel } from './nodeLabels';

const graph = readBpmnGraph(SYMBOL_GALLERY_BPMN);
const byId = new Map(graph.nodes.map((node) => [node.id, node]));

describe('BPMN symbols (#209)', () => {
  it('CKP_209_every_step_of_the_gallery_is_a_type_the_cockpit_draws', () => {
    const unknown = graph.nodes.filter((node) => !isKnownNodeType(node.type)).map((node) => `${node.id}:${node.type}`);
    expect(unknown).toEqual([]);
    for (const node of graph.nodes) expect(nodeTypeLabel(node.type, (key) => key), node.id).not.toBe('node.other');
  });

  it('CKP_209_an_event_with_no_definition_or_several_throws_is_a_known_event', () => {
    expect(byId.get('t-none')?.type).toBe('intermediateThrowEvent');
    expect(byId.get('c-none')?.type).toBe('intermediateCatchEvent');
    expect(byId.get('t-multiple')?.type).toBe('intermediateThrowEvent:multiple');
    expect(byId.get('e-multiple')?.type).toBe('endEvent:multiple');
  });

  it('CKP_209_an_event_gateway_says_whether_it_waits_for_all_and_whether_it_starts_the_process', () => {
    expect(byId.get('g-event')?.eventGateway).toEqual({ parallel: false, instantiate: false });
    expect(byId.get('g-event-all')?.eventGateway).toEqual({ parallel: true, instantiate: false });
    expect(byId.get('g-event-start')?.eventGateway).toEqual({ parallel: false, instantiate: true });
    expect(byId.get('g-event-start-all')?.eventGateway).toEqual({ parallel: true, instantiate: true });
  });

  it('CKP_209_any_activity_for_compensation_and_any_flow_with_a_condition_is_marked', () => {
    expect(byId.get('m-undo-user')?.compensation).toBe(true);
    expect(byId.get('k-user')?.compensation).toBeUndefined();
    expect(graph.edges.find((edge) => edge.id === 'flow-if')?.conditional).toBe(true);
    expect(graph.edges.find((edge) => edge.id === 'flow-else')?.conditional).toBeUndefined();
  });

  it('CKP_209_a_step_with_a_marker_keeps_a_row_for_it_clear_of_its_text', () => {
    const plain = layoutGraph(graph).nodes;
    const height = (id: string) => plain.find((node) => node.id === id)?.height ?? 0;
    expect(height('m-loop')).toBeGreaterThan(height('k-user'));
    expect(height('m-undo-user')).toBeGreaterThan(height('k-user'));
  });

  it('CKP_209_the_gallery_is_drawn_without_steps_overlapping', () => {
    const layout = layoutGraph({ ...graph, stored: SYMBOL_GALLERY_LAYOUT });
    const solid = layout.nodes.filter((node) => node.attachedTo === undefined);
    for (const [index, a] of solid.entries()) {
      for (const b of solid.slice(index + 1)) expect(overlaps(a, b), `${a.id} overlaps ${b.id}`).toBe(false);
    }
  });

  it('CKP_209_a_type_label_breaks_only_at_spaces_and_hyphens', () => {
    const words: Record<string, string> = { transaction: 'Transaction', subProcess: 'Sub-process', 'subProcess:event': 'Event sub-process' };
    const layout = layoutGraph(graph, (type) => words[type] ?? type);
    expect(layout.nodes.find((n) => n.id === 'p-transaction')?.typeLines).toEqual(['Transaction']);
    expect(layout.nodes.find((n) => n.id === 'p-embedded')?.typeLines).toEqual(['Sub-', 'process']);
    expect(layout.nodes.find((n) => n.id === 'p-event-soft')?.lines).toEqual(['Event non-', 'interrupting']);
    expect(wrapWords('Event sub-process', 74, 6.8)).toEqual(['Event sub-', 'process']);
  });

  it('CKP_209_a_name_wider_than_its_box_is_cut_with_an_ellipsis_not_in_the_middle_of_a_word', () => {
    const long = { name: 'x', nodes: [{ id: 'a', name: 'Supercalifragilisticexpialidocious', type: 'task' }], edges: [] };
    const lines = layoutGraph(long).nodes[0]?.lines ?? [];
    expect(lines.join(' ')).toMatch(/^Supercalifr.*…$/);
  });

  it('CKP_209_the_name_of_a_flow_between_close_boxes_clears_both', () => {
    const layout = layoutGraph({ ...graph, stored: SYMBOL_GALLERY_LAYOUT });
    const edge = layout.edges.find((e) => e.id === 'flow-if');
    const size = labelSize(edge?.label ?? '');
    const at = edge?.labelAt ?? { x: 0, y: 0 };
    const rect = { x: at.x - size.width / 2, y: at.y - size.height / 2, ...size };
    for (const id of ['k-task', 'k-user']) {
      const node = layout.nodes.find((n) => n.id === id);
      if (node === undefined) throw new Error(`${id} is not drawn`);
      const box = footprint(node);
      const hit = rect.x < box.x + box.width && box.x < rect.x + rect.width && rect.y < box.y + box.height && box.y < rect.y + rect.height;
      expect(hit, id).toBe(false);
    }
  });
});
