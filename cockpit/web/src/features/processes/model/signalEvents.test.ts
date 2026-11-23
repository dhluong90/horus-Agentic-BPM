import { describe, expect, it } from 'vitest';
import { isKnownNodeType, readBpmnGraph } from './bpmnGraph';
import { entryKey } from './instanceView';
import { nodeIconKind } from './nodeLabels';

const BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="urn:t">
  <bpmn:signal id="Signal_rate" name="rate-card-updated"/>
  <bpmn:process id="rate-card-watch" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:intermediateCatchEvent id="wait"><bpmn:signalEventDefinition signalRef="Signal_rate"/></bpmn:intermediateCatchEvent>
    <bpmn:intermediateCatchEvent id="limit"><bpmn:conditionalEventDefinition><bpmn:condition>limit &gt; 0</bpmn:condition></bpmn:conditionalEventDefinition></bpmn:intermediateCatchEvent>
    <bpmn:endEvent id="end"><bpmn:signalEventDefinition signalRef="Signal_rate"/></bpmn:endEvent>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
    <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="limit"/>
    <bpmn:sequenceFlow id="f3" sourceRef="limit" targetRef="end"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('PRE-275 signal and conditional events are drawn and named in the timeline, read only', () => {
  it('PRE_275_signal_and_conditional_events_are_known_symbols_not_generic_nodes', () => {
    const graph = readBpmnGraph(BPMN);

    expect(graph.nodes.map((node) => node.type)).toEqual([
      'startEvent', 'intermediateCatchEvent:signal', 'intermediateCatchEvent:conditional', 'endEvent:signal',
    ]);
    for (const node of graph.nodes) {
      expect(isKnownNodeType(node.type), node.type).toBe(true);
      expect(nodeIconKind(node.type), node.type).not.toBe('generic');
    }
  });

  it('PRE_275_the_timeline_names_the_signal_and_condition_entries', () => {
    expect(entryKey('SIGNAL_THROWN')).toBe('instance.entry.SIGNAL_THROWN');
    expect(entryKey('CONDITION_MET')).toBe('instance.entry.CONDITION_MET');
  });
});
