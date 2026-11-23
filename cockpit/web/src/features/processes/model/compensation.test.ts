import { describe, expect, it } from 'vitest';
import { readBpmnGraph } from './bpmnGraph';
import { layoutGraph, overlaps } from './layout';
import { nodeTypeLabel } from './nodeLabels';

/** A fund transfer as a transaction: the debit is undone by a compensation activity, a cancel end hands the flow to the cancel boundary (#488). */
const TRANSFER = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t">
  <bpmn:process id="fund-transfer" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:transaction id="transfer" name="Move the money">
      <bpmn:startEvent id="transfer-start"/>
      <bpmn:serviceTask id="debit-source" name="Debit the source" horus:handler="core-banking"/>
      <bpmn:boundaryEvent id="debit-undo" attachedToRef="debit-source" cancelActivity="false"><bpmn:compensateEventDefinition/></bpmn:boundaryEvent>
      <bpmn:serviceTask id="credit-back" name="Credit it back" isForCompensation="true" horus:handler="credit-back"/>
      <bpmn:association id="link" associationDirection="One" sourceRef="debit-undo" targetRef="credit-back"/>
      <bpmn:endEvent id="transfer-cancel"><bpmn:cancelEventDefinition/></bpmn:endEvent>
      <bpmn:sequenceFlow id="t1" sourceRef="transfer-start" targetRef="debit-source"/>
      <bpmn:sequenceFlow id="t2" sourceRef="debit-source" targetRef="transfer-cancel"/>
    </bpmn:transaction>
    <bpmn:boundaryEvent id="cancelled" attachedToRef="transfer"><bpmn:cancelEventDefinition/></bpmn:boundaryEvent>
    <bpmn:endEvent id="undo-all"><bpmn:compensateEventDefinition/></bpmn:endEvent>
    <bpmn:endEvent id="end"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="transfer"/>
    <bpmn:sequenceFlow id="f2" sourceRef="transfer" targetRef="end"/>
    <bpmn:sequenceFlow id="f3" sourceRef="cancelled" targetRef="undo-all"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('transaction, cancel and compensation in the diagram', () => {
  it('CKP_COMPENSATION_the_graph_reads_the_standard_elements_and_the_association_line', () => {
    const graph = readBpmnGraph(TRANSFER);

    expect(graph.nodes.find((n) => n.id === 'transfer')?.type).toBe('transaction');
    expect(graph.nodes.find((n) => n.id === 'debit-undo')).toMatchObject({ type: 'boundaryEvent:compensate', attachedTo: 'debit-source' });
    expect(graph.nodes.find((n) => n.id === 'credit-back')?.type).toBe('serviceTask:compensation');
    expect(graph.nodes.find((n) => n.id === 'cancelled')).toMatchObject({ type: 'boundaryEvent:cancel', attachedTo: 'transfer' });
    expect(graph.nodes.find((n) => n.id === 'transfer-cancel')?.type).toBe('endEvent:cancel');
    expect(graph.nodes.find((n) => n.id === 'undo-all')?.type).toBe('endEvent:compensate');
    expect(graph.edges.find((e) => e.isAssociation === true)).toMatchObject({ source: 'debit-undo', target: 'credit-back' });
  });

  it('CKP_COMPENSATION_the_undo_activity_has_a_place_of_its_own_and_the_dotted_line_runs_from_the_boundary_to_it', () => {
    const layout = layoutGraph(readBpmnGraph(TRANSFER), (type) => nodeTypeLabel(type, (key) => key), { open: new Set(['transfer']) });
    const undo = layout.nodes.find((n) => n.id === 'credit-back');
    const debit = layout.nodes.find((n) => n.id === 'debit-source');
    const line = layout.edges.find((e) => e.isAssociation === true);

    expect(undo).toBeDefined();
    expect(debit).toBeDefined();
    if (undo === undefined || debit === undefined) return;
    expect(overlaps(undo, debit)).toBe(false);
    expect(line?.points.length ?? 0).toBeGreaterThan(1);
  });

  it('CKP_COMPENSATION_each_new_node_is_named_in_words', () => {
    const words = (type: string) => nodeTypeLabel(type, (key) => key);

    expect(words('boundaryEvent:compensate')).toBe('node.boundaryCompensate');
    expect(words('boundaryEvent:cancel')).toBe('node.boundaryCancel');
    expect(words('endEvent:compensate')).toBe('node.compensationEnd');
    expect(words('endEvent:cancel')).toBe('node.cancelEnd');
    expect(words('serviceTask:compensation')).toBe('node.undoStep');
  });
});
