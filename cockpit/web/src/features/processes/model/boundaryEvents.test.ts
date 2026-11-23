import { describe, expect, it } from 'vitest';
import { readBpmnGraph } from './bpmnGraph';
import { layoutGraph, EVENT_RADIUS, symbolCentre } from './layout';
import { entryKey } from './instanceView';
import { nodeTypeLabel } from './nodeLabels';

/** A claim review with an overdue timer that does not interrupt, an error boundary, and a link pair (#489, #493). */
const CLAIM = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t">
  <bpmn:process id="claim-flow" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:userTask id="review" name="Review the claim"/>
    <bpmn:boundaryEvent id="overdue" attachedToRef="review" cancelActivity="false"><bpmn:timerEventDefinition><bpmn:timeDuration>PT4H</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:boundaryEvent>
    <bpmn:boundaryEvent id="no-funds" attachedToRef="review"><bpmn:errorEventDefinition errorRef="E1"/></bpmn:boundaryEvent>
    <bpmn:serviceTask id="remind" name="Send a reminder"/>
    <bpmn:intermediateThrowEvent id="to-fees"><bpmn:linkEventDefinition name="fees"/></bpmn:intermediateThrowEvent>
    <bpmn:intermediateCatchEvent id="from-fees"><bpmn:linkEventDefinition name="fees"/></bpmn:intermediateCatchEvent>
    <bpmn:endEvent id="stop"><bpmn:terminateEventDefinition/></bpmn:endEvent>
    <bpmn:endEvent id="end"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
    <bpmn:sequenceFlow id="f2" sourceRef="review" targetRef="to-fees"/>
    <bpmn:sequenceFlow id="f3" sourceRef="overdue" targetRef="remind"/>
    <bpmn:sequenceFlow id="f4" sourceRef="remind" targetRef="end"/>
    <bpmn:sequenceFlow id="f5" sourceRef="from-fees" targetRef="stop"/>
    <bpmn:sequenceFlow id="f6" sourceRef="no-funds" targetRef="end"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('boundary, terminate and link events in the diagram', () => {
  it('CKP_BOUNDARY_the_graph_knows_what_a_boundary_event_sits_on_and_whether_it_interrupts', () => {
    const graph = readBpmnGraph(CLAIM);

    expect(graph.nodes.find((n) => n.id === 'overdue')).toMatchObject({ type: 'boundaryEvent:timer', attachedTo: 'review', interrupting: false });
    expect(graph.nodes.find((n) => n.id === 'no-funds')).toMatchObject({ type: 'boundaryEvent:error', attachedTo: 'review', interrupting: true });
    expect(graph.nodes.find((n) => n.id === 'stop')?.type).toBe('endEvent:terminate');
    expect(graph.edges.find((e) => e.isLink === true)).toMatchObject({ source: 'to-fees', target: 'from-fees', label: 'fees' });
  });

  it('CKP_BOUNDARY_a_boundary_event_sits_on_the_lower_edge_of_its_activity_and_its_flow_leaves_below_it', () => {
    const layout = layoutGraph(readBpmnGraph(CLAIM));
    const host = layout.nodes.find((n) => n.id === 'review');
    const timer = layout.nodes.find((n) => n.id === 'overdue');
    const error = layout.nodes.find((n) => n.id === 'no-funds');
    if (!host || !timer || !error) throw new Error('nodes missing');

    expect(symbolCentre(timer).y).toBeCloseTo(host.y + host.height, 5);
    expect(symbolCentre(timer).x).toBeGreaterThan(host.x);
    expect(symbolCentre(timer).x).toBeLessThan(host.x + host.width);
    expect(symbolCentre(error).x).not.toBeCloseTo(symbolCentre(timer).x, 0);
    const flow = layout.edges.find((e) => e.id === 'f3');
    expect(flow?.points[0]?.y).toBeCloseTo(symbolCentre(timer).y + EVENT_RADIUS, 5);
    // The boundary events take no rank of their own: remind follows review directly.
    expect(layout.nodes.find((n) => n.id === 'remind')?.x).toBeGreaterThan(host.x + host.width);
  });

  it('CKP_BOUNDARY_the_new_events_and_timeline_entries_have_words_of_their_own', () => {
    const t = (key: string) => key;
    expect(nodeTypeLabel('boundaryEvent:timer', t)).toBe('node.boundaryTimer');
    expect(nodeTypeLabel('endEvent:terminate', t)).toBe('node.terminateEnd');
    expect(nodeTypeLabel('intermediateCatchEvent:link', t)).toBe('node.linkCatch');
    for (const type of ['BOUNDARY_FIRED', 'ERROR_THROWN', 'ESCALATION_THROWN', 'TERMINATED', 'LINK_JUMPED']) {
      expect(entryKey(type)).toBe(`instance.entry.${type}`);
    }
  });
});
