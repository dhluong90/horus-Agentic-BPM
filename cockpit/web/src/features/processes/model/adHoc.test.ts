import { describe, expect, it } from 'vitest';
import type { ProcessInstance } from '../../../api/processes/schemas';
import { readBpmnGraph } from './bpmnGraph';
import { adHocScope } from './adHoc';

const BPMN = 'http://www.omg.org/spec/BPMN/20100524/MODEL';

function graphOf(ordering: string) {
  return readBpmnGraph(`<bpmn:definitions xmlns:bpmn="${BPMN}"><bpmn:process id="customer-onboarding" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:adHocSubProcess id="onboarding" ordering="${ordering}">
      <bpmn:userTask id="collect-id"/><bpmn:userTask id="screen-sanctions"/>
    </bpmn:adHocSubProcess>
    <bpmn:endEvent id="end"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="onboarding"/><bpmn:sequenceFlow id="f2" sourceRef="onboarding" targetRef="end"/>
  </bpmn:process></bpmn:definitions>`);
}

function instance(status: string, tokens: ProcessInstance['tokens']): ProcessInstance {
  return { id: 'I1', definitionKey: 'customer-onboarding', definitionVersion: 1, businessKey: 'CUS-0042', status, lockVersion: 3, startedBy: null, startedAt: '2027-03-01T02:00:00Z', endedAt: null, tokens, tasks: [] };
}

const scope = { id: 'S1', kind: 'SCOPE', scopeTokenId: 'S0', nodeId: 'onboarding', status: 'WAITING_SCOPE' };

describe('ad-hoc sub-process in the instance view (#500)', () => {
  it('offers every activity of a parallel sub-process while others run', () => {
    const running = { id: 'T1', kind: 'FLOW', scopeTokenId: 'S1', nodeId: 'collect-id', status: 'WAITING_USER' };
    const result = adHocScope(graphOf('Parallel'), instance('RUNNING', [scope, running]), 'onboarding');
    expect(result?.scopeTokenId).toBe('S1');
    expect(result?.enabled.map((node) => node.id)).toEqual(['collect-id', 'screen-sanctions']);
  });

  it('offers nothing of a sequential one while an activity runs, and all when none does', () => {
    const running = { id: 'T1', kind: 'FLOW', scopeTokenId: 'S1', nodeId: 'collect-id', status: 'WAITING_USER' };
    expect(adHocScope(graphOf('Sequential'), instance('RUNNING', [scope, running]), 'onboarding')?.enabled).toEqual([]);
    const done = { ...running, status: 'COMPLETED' };
    expect(adHocScope(graphOf('Sequential'), instance('RUNNING', [scope, done]), 'onboarding')?.enabled).toHaveLength(2);
  });

  it('offers nothing once the sub-process has ended or the case is not running', () => {
    expect(adHocScope(graphOf('Parallel'), instance('RUNNING', [{ ...scope, status: 'COMPLETED' }]), 'onboarding')).toBeUndefined();
    expect(adHocScope(graphOf('Parallel'), instance('CANCELLED', [scope]), 'onboarding')).toBeUndefined();
  });

  it('names a multiple event and a complex gateway by their own types', () => {
    const graph = readBpmnGraph(`<bpmn:definitions xmlns:bpmn="${BPMN}"><bpmn:process id="p" isExecutable="true">
      <bpmn:complexGateway id="join"/>
      <bpmn:intermediateCatchEvent id="wait" parallelMultiple="true"><bpmn:signalEventDefinition/><bpmn:messageEventDefinition/></bpmn:intermediateCatchEvent>
      <bpmn:intermediateCatchEvent id="any"><bpmn:signalEventDefinition/><bpmn:messageEventDefinition/></bpmn:intermediateCatchEvent>
    </bpmn:process></bpmn:definitions>`);
    expect(graph.nodes.map((node) => node.type)).toEqual(['complexGateway', 'intermediateCatchEvent:parallelMultiple', 'intermediateCatchEvent:multiple']);
  });
});
