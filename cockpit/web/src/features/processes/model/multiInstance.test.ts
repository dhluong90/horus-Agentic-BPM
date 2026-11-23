import { describe, expect, it } from 'vitest';
import type { InstanceActivities } from '../../../api/processes/schemas';
import { readBpmnGraph } from './bpmnGraph';
import { entryKey, loopProgress } from './instanceView';

/** A loan whose four co-signers approve in parallel, whose owners are screened one by one, and whose payment is retried (#496). */
const LOAN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t">
  <bpmn:process id="loan-cosigning" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:userTask id="cosign" name="Co-signer approval" horus:assignee="role:CREDIT_OFFICER" horus:resultVariable="verdict">
      <bpmn:multiInstanceLoopCharacteristics horus:collection="cosigners" horus:elementVariable="cosigner"/>
    </bpmn:userTask>
    <bpmn:serviceTask id="screen" name="Screen owners" horus:handler="sanctions-screen">
      <bpmn:multiInstanceLoopCharacteristics isSequential="true" horus:collection="owners" horus:elementVariable="owner"/>
    </bpmn:serviceTask>
    <bpmn:serviceTask id="charge" name="Charge the card" horus:handler="charge-card">
      <bpmn:standardLoopCharacteristics loopMaximum="3"/>
    </bpmn:serviceTask>
    <bpmn:subProcess id="branches" name="Per branch">
      <bpmn:multiInstanceLoopCharacteristics horus:collection="branches" horus:elementVariable="branch"/>
      <bpmn:startEvent id="b-start"/>
      <bpmn:endEvent id="b-end"/>
      <bpmn:sequenceFlow id="b1" sourceRef="b-start" targetRef="b-end"/>
    </bpmn:subProcess>
    <bpmn:endEvent id="end"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="cosign"/>
    <bpmn:sequenceFlow id="f2" sourceRef="cosign" targetRef="screen"/>
    <bpmn:sequenceFlow id="f3" sourceRef="screen" targetRef="charge"/>
    <bpmn:sequenceFlow id="f4" sourceRef="charge" targetRef="branches"/>
    <bpmn:sequenceFlow id="f5" sourceRef="branches" targetRef="end"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('a step that repeats in the diagram and the timeline', () => {
  it('CKP_LOOP_the_graph_marks_each_kind_of_repetition_and_never_draws_the_marker_as_a_step', () => {
    const graph = readBpmnGraph(LOAN);

    expect(graph.nodes.find((node) => node.id === 'cosign')?.loop).toBe('parallel');
    expect(graph.nodes.find((node) => node.id === 'screen')?.loop).toBe('sequential');
    expect(graph.nodes.find((node) => node.id === 'charge')?.loop).toBe('loop');
    expect(graph.nodes.find((node) => node.id === 'branches')?.loop).toBe('parallel');
    expect(graph.nodes.find((node) => node.id === 'start')?.loop).toBeUndefined();
    expect(graph.nodes.map((node) => node.id)).toEqual(['start', 'cosign', 'screen', 'charge', 'branches', 'b-start', 'b-end', 'end']);
  });

  it('CKP_LOOP_the_progress_of_a_step_is_the_latest_count_the_engine_wrote', () => {
    const activities: InstanceActivities = {
      instanceId: 'I9', startedAt: 'x', endedAt: null, hasMore: false, nodes: [],
      entries: [
        { id: '1', type: 'MI_STARTED', nodeId: 'cosign', actor: null, occurredAt: 'x', instances: { nrOfInstances: 5, nrOfActiveInstances: 0, nrOfCompletedInstances: 0 } },
        { id: '2', type: 'MI_INSTANCE_COMPLETED', nodeId: 'cosign', actor: null, occurredAt: 'x', instances: { loopCounter: 2, nrOfInstances: 5, nrOfActiveInstances: 2, nrOfCompletedInstances: 3 } },
        { id: '3', type: 'MI_INSTANCE_COMPLETED', nodeId: 'charge', actor: null, occurredAt: 'x', instances: { loopCounter: 1, nrOfActiveInstances: 0, nrOfCompletedInstances: 1 } },
        { id: '4', type: 'NODE_ENTERED', nodeId: 'book', actor: null, occurredAt: 'x' },
      ],
    };

    const progress = loopProgress(activities);

    expect(progress.get('cosign')).toEqual({ done: 3, total: 5, active: 2 });
    expect(progress.get('charge')).toEqual({ done: 1, total: undefined, active: 0 });
    expect(progress.has('book')).toBe(false);
    expect(entryKey('MI_INSTANCE_COMPLETED')).toBe('instance.entry.MI_INSTANCE_COMPLETED');
  });
});
