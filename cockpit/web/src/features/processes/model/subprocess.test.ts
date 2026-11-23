import { describe, expect, it } from 'vitest';
import { ancestorsOf, flowOrder, readBpmnGraph } from './bpmnGraph';
import { layoutGraph } from './layout';

/** An onboarding whose customer checks run inside the sub-process `onboard` (#486). */
const ONBOARDING = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t">
  <bpmn:process id="customer-onboarding" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:subProcess id="onboard" name="Onboard the customer">
      <bpmn:incoming>f1</bpmn:incoming>
      <bpmn:outgoing>f2</bpmn:outgoing>
      <bpmn:startEvent id="onboard-start"/>
      <bpmn:serviceTask id="bureau" name="Credit bureau" horus:handler="credit-bureau"/>
      <bpmn:endEvent id="onboard-end"/>
      <bpmn:sequenceFlow id="s1" sourceRef="onboard-start" targetRef="bureau"/>
      <bpmn:sequenceFlow id="s2" sourceRef="bureau" targetRef="onboard-end"/>
    </bpmn:subProcess>
    <bpmn:serviceTask id="open-account" name="Open account" horus:handler="open-account"/>
    <bpmn:endEvent id="end"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="onboard"/>
    <bpmn:sequenceFlow id="f2" sourceRef="onboard" targetRef="open-account"/>
    <bpmn:sequenceFlow id="f3" sourceRef="open-account" targetRef="end"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('a sub-process in the diagram and the step list', () => {
  it('CKP_SUBPROCESS_the_graph_keeps_inner_steps_with_their_container', () => {
    const graph = readBpmnGraph(ONBOARDING);

    expect(graph.nodes.find((node) => node.id === 'bureau')?.parentId).toBe('onboard');
    expect(graph.nodes.find((node) => node.id === 'open-account')?.parentId).toBeUndefined();
    expect(graph.nodes.find((node) => node.id === 'onboard')?.type).toBe('subProcess');
    expect(ancestorsOf(graph, 'bureau').map((node) => node.id)).toEqual(['onboard']);
    expect(flowOrder(graph).map((node) => node.id)).toEqual(
      ['start', 'onboard', 'onboard-start', 'bureau', 'onboard-end', 'open-account', 'end']);
  });

  it('CKP_SUBPROCESS_a_closed_sub_process_is_one_box_with_the_count_of_its_steps', () => {
    const layout = layoutGraph(readBpmnGraph(ONBOARDING));

    expect(layout.nodes.map((node) => node.id)).toEqual(['start', 'onboard', 'open-account', 'end']);
    expect(layout.nodes.find((node) => node.id === 'onboard')?.container).toEqual({ steps: 1, open: false });
  });

  it('CKP_SUBPROCESS_an_open_sub_process_is_a_frame_with_its_steps_inside_and_the_flows_stay_outside', () => {
    const layout = layoutGraph(readBpmnGraph(ONBOARDING), (type) => type, { open: new Set(['onboard']) });
    const frame = layout.nodes.find((node) => node.id === 'onboard');
    const inner = layout.nodes.filter((node) => node.id === 'bureau' || node.id === 'onboard-start' || node.id === 'onboard-end');

    expect(frame?.container).toEqual({ steps: 1, open: true });
    expect(inner).toHaveLength(3);
    for (const node of inner) {
      expect(node.x, node.id).toBeGreaterThanOrEqual(frame?.x ?? Infinity);
      expect(node.y, node.id).toBeGreaterThanOrEqual(frame?.y ?? Infinity);
      expect(node.x + node.width, node.id).toBeLessThanOrEqual((frame?.x ?? 0) + (frame?.width ?? 0));
      expect(node.y + node.height, node.id).toBeLessThanOrEqual((frame?.y ?? 0) + (frame?.height ?? 0));
    }
    expect(layout.edges.map((edge) => edge.id).sort()).toEqual(['f1', 'f2', 'f3', 's1', 's2']);
  });
});
