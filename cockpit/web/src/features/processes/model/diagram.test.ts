import { describe, expect, it } from 'vitest';
import { KYC_ONBOARDING_BPMN, LOAN_ORIGINATION_BPMN } from '../../../mocks/processDemo';
import { DiagramReadError, flowOrder, isKnownNodeType, readBpmnGraph, type DiagramGraph } from './bpmnGraph';
import { layoutGraph, overlaps, symbolCentre, type PlacedNode } from './layout';

/** A synthetic definition of `size` steps: a chain with a gateway every tenth step and a branch that rejoins. */
function largeGraph(size: number): DiagramGraph {
  const nodes: DiagramGraph['nodes'] = [{ id: 'n0', name: 'Start', type: 'startEvent' }];
  const edges: DiagramGraph['edges'] = [];
  for (let i = 1; i < size; i += 1) {
    const type = i === size - 1 ? 'endEvent' : i % 10 === 0 ? 'exclusiveGateway' : i % 7 === 0 ? 'serviceTask:agentTask' : 'serviceTask';
    nodes.push({ id: `n${String(i)}`, name: `Step ${String(i)}`, type });
    edges.push({ id: `e${String(i)}`, source: `n${String(i - 1)}`, target: `n${String(i)}`, label: undefined, isDefault: false });
    if (i % 10 === 0 && i + 3 < size) edges.push({ id: `b${String(i)}`, source: `n${String(i)}`, target: `n${String(i + 3)}`, label: 'Skip', isDefault: true });
  }
  return { name: 'Large', nodes, edges };
}

function centreX(node: PlacedNode): number {
  return node.shape === 'task' ? node.x + node.width / 2 : symbolCentre(node).x;
}

describe('CKP-069 a definition without stored layout is laid out automatically', () => {
  it('CKP_069_loan_origination_is_laid_out_left_to_right_without_overlapping_nodes', () => {
    const layout = layoutGraph(readBpmnGraph(LOAN_ORIGINATION_BPMN));

    expect(layout.nodes).toHaveLength(14);
    for (const [index, a] of layout.nodes.entries()) {
      for (const b of layout.nodes.slice(index + 1)) expect(overlaps(a, b), `${a.id} overlaps ${b.id}`).toBe(false);
    }
    // Left to right: every flow ends further right than it starts, so the diagram reads in flow order.
    const byId = new Map(layout.nodes.map((node) => [node.id, node]));
    for (const edge of layout.edges) {
      const source = byId.get(edge.source);
      const target = byId.get(edge.target);
      if (!source || !target) throw new Error(`edge ${edge.id} has an unplaced end`);
      expect(centreX(target), edge.id).toBeGreaterThan(centreX(source));
      expect(edge.points.length, edge.id).toBeGreaterThanOrEqual(2);
    }
    expect(layout.width).toBeGreaterThan(0);
  });

  it('CKP_069_every_flow_ends_on_its_target_and_the_default_flow_is_marked', () => {
    const graph = readBpmnGraph(LOAN_ORIGINATION_BPMN);
    expect(graph.edges.find((edge) => edge.id === 'to-decline')).toMatchObject({ isDefault: true, label: 'Declined' });
    expect(graph.edges.find((edge) => edge.id === 'to-book')).toMatchObject({ isDefault: false, label: 'Approved' });
    const layout = layoutGraph(graph);
    const target = layout.nodes.find((node) => node.id === 'booked');
    const last = layout.edges.find((edge) => edge.id === 'f6')?.points.at(-1);
    if (!target || !last) throw new Error('missing node or edge');
    // The arrow touches the end event's circle (radius 20), not the edge of its label box.
    expect(Math.hypot(last.x - symbolCentre(target).x, last.y - symbolCentre(target).y)).toBeCloseTo(20, 0);
  });

  it('CKP_069_a_200_step_definition_is_laid_out_within_the_one_second_budget', () => {
    const graph = largeGraph(200);
    const started = performance.now();
    const layout = layoutGraph(graph);
    const elapsed = performance.now() - started;

    expect(layout.nodes).toHaveLength(200);
    expect(elapsed).toBeLessThan(1000);
    const sorted = [...layout.nodes].sort((a, b) => a.x - b.x);
    for (const [index, a] of sorted.entries()) {
      for (const b of sorted.slice(index + 1)) {
        if (b.x >= a.x + a.width) break;
        expect(overlaps(a, b), `${a.id} overlaps ${b.id}`).toBe(false);
      }
    }
  });

  it('CKP_069_the_step_list_follows_the_flow_from_the_start', () => {
    const order = flowOrder(readBpmnGraph(KYC_ONBOARDING_BPMN)).map((node) => node.id);
    expect(order.slice(0, 2)).toEqual(['start', 'split']);
    expect(order.indexOf('risk-rating')).toBeGreaterThan(order.indexOf('join'));
    expect(order.at(-1)).toBe('end');
    expect(order).toHaveLength(11);
  });
});

describe('PRE-250 task, manual task and script task are viewable steps', () => {
  it('PRE_250_the_three_tasks_are_known_nodes_and_a_script_task_names_its_result_variable', () => {
    const xml = `<?xml version="1.0"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d">
  <bpmn:process id="card-fee-review" isExecutable="true">
    <bpmn:task id="note" name="Note"/>
    <bpmn:manualTask id="post-letter" name="Post letter"/>
    <bpmn:scriptTask id="double-fee" name="Double fee" horus:resultVariable="fee2"><bpmn:script>amount * 2</bpmn:script></bpmn:scriptTask>
  </bpmn:process>
</bpmn:definitions>`;
    const nodes = readBpmnGraph(xml).nodes;

    expect(nodes.map((node) => node.type)).toEqual(['task', 'manualTask', 'scriptTask']);
    expect(nodes.every((node) => isKnownNodeType(node.type))).toBe(true);
  });
});

describe('CKP-070 diagram nodes are keyed by BPMN element type; unknown types render as generic nodes', () => {
  it('CKP_070_steps_are_keyed_by_bpmn_type_with_the_horus_subtype', () => {
    const types = Object.fromEntries(readBpmnGraph(LOAN_ORIGINATION_BPMN).nodes.map((node) => [node.id, node.type]));
    expect(types).toMatchObject({
      received: 'startEvent',
      capture: 'userTask',
      'assess-documents': 'serviceTask:agentTask',
      'credit-decision': 'businessRuleTask',
      approved: 'exclusiveGateway',
      'book-loan': 'serviceTask',
      booked: 'endEvent',
    });
    const kyc = Object.fromEntries(readBpmnGraph(KYC_ONBOARDING_BPMN).nodes.map((node) => [node.id, node.type]));
    expect(kyc).toMatchObject({ split: 'parallelGateway', cooling: 'intermediateCatchEvent:timer' });
  });

  it('CKP_070_an_unknown_type_stays_in_the_graph_with_its_type_name_and_the_rest_is_intact', () => {
    const graph = readBpmnGraph(LOAN_ORIGINATION_BPMN);
    const boundary = graph.nodes.find((node) => node.id === 'no-documents');
    expect(boundary).toEqual({ id: 'no-documents', name: 'No documents in 2 days', type: 'boundaryEvent:timer' });
    expect(isKnownNodeType('boundaryEvent:timer')).toBe(false);
    expect(graph.edges.some((edge) => edge.source === 'no-documents' && edge.target === 'remind')).toBe(true);
    expect(layoutGraph(graph).nodes.find((node) => node.id === 'no-documents')).toBeDefined();
  });

  it('CKP_070_a_document_with_a_doctype_or_no_process_is_refused_not_guessed', () => {
    expect(() => readBpmnGraph('<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "b">]><x/>')).toThrow(DiagramReadError);
    expect(() => readBpmnGraph('<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"/>')).toThrow(DiagramReadError);
    expect(() => readBpmnGraph('<not-closed')).toThrow(DiagramReadError);
  });
});
