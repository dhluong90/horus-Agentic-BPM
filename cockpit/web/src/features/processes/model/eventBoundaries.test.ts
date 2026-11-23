import { describe, expect, it } from 'vitest';
import { isKnownNodeType, readBpmnGraph } from './bpmnGraph';
import { entryKey } from './instanceView';
import { nodeIconKind, nodeTypeLabel } from './nodeLabels';
import { shapeOf } from './layout';

const BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="urn:t">
  <bpmn:message id="M_cancel" name="payment-cancelled"/>
  <bpmn:signal id="S_freeze" name="account-frozen"/>
  <bpmn:process id="payment-review" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:userTask id="review"/>
    <bpmn:boundaryEvent id="cancelled" attachedToRef="review"><bpmn:messageEventDefinition messageRef="M_cancel"/></bpmn:boundaryEvent>
    <bpmn:boundaryEvent id="frozen" attachedToRef="review" cancelActivity="false"><bpmn:signalEventDefinition signalRef="S_freeze"/></bpmn:boundaryEvent>
    <bpmn:boundaryEvent id="risky" attachedToRef="review"><bpmn:conditionalEventDefinition><bpmn:condition>risk &gt; 80</bpmn:condition></bpmn:conditionalEventDefinition></bpmn:boundaryEvent>
    <bpmn:eventBasedGateway id="wait"/>
    <bpmn:endEvent id="end"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('PRE-295 message, signal and conditional boundaries and the event-based gateway are drawn and named, read only', () => {
  it('PRE_295_the_new_boundaries_and_the_gateway_are_known_symbols_that_say_whether_they_interrupt', () => {
    const graph = readBpmnGraph(BPMN);
    const byId = (id: string) => graph.nodes.find((node) => node.id === id);

    expect(byId('cancelled')).toMatchObject({ type: 'boundaryEvent:message', attachedTo: 'review', interrupting: true });
    expect(byId('frozen')).toMatchObject({ type: 'boundaryEvent:signal', attachedTo: 'review', interrupting: false });
    expect(byId('risky')).toMatchObject({ type: 'boundaryEvent:conditional', interrupting: true });
    expect(byId('wait')?.type).toBe('eventBasedGateway');
    for (const id of ['cancelled', 'frozen', 'risky', 'wait']) {
      const node = byId(id);
      expect(isKnownNodeType(node?.type ?? ''), id).toBe(true);
      expect(nodeIconKind(node?.type ?? ''), id).not.toBe('generic');
    }
    expect(shapeOf('eventBasedGateway')).toBe('gateway');
    expect(shapeOf('boundaryEvent:message')).toBe('event');
  });

  it('PRE_295_the_timeline_names_the_gateway_and_boundary_entries_and_the_types_are_words', () => {
    expect(entryKey('EVENT_GATEWAY_RESOLVED')).toBe('instance.entry.EVENT_GATEWAY_RESOLVED');
    expect(entryKey('EVENT_GATEWAY_WAITING')).toBe('instance.entry.EVENT_GATEWAY_WAITING');
    expect(entryKey('BOUNDARY_FIRED')).toBe('instance.entry.BOUNDARY_FIRED');
    expect(nodeTypeLabel('eventBasedGateway', (key) => key)).toBe('node.eventBasedGateway');
    expect(nodeTypeLabel('boundaryEvent:signal', (key) => key)).toBe('node.boundarySignal');
  });
});
