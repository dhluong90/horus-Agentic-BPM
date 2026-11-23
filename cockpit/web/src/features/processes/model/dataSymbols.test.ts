import { describe, expect, it } from 'vitest';
import { readBpmnGraph } from './bpmnGraph';
import { layoutGraph, overlaps } from './layout';

const BPMN = `<?xml version="1.0"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="urn:t">
  <bpmn:dataStore id="ledger" name="Ledger notes"/>
  <bpmn:process id="loan-data" isExecutable="true">
    <bpmn:startEvent id="start"/>
    <bpmn:serviceTask id="score" name="Score the applicant">
      <bpmn:dataInputAssociation id="a-in"><bpmn:sourceRef>profile-ref</bpmn:sourceRef><bpmn:targetRef>in-profile</bpmn:targetRef></bpmn:dataInputAssociation>
      <bpmn:dataOutputAssociation id="a-out"><bpmn:sourceRef>out-score</bpmn:sourceRef><bpmn:targetRef>score-ref</bpmn:targetRef></bpmn:dataOutputAssociation>
    </bpmn:serviceTask>
    <bpmn:endEvent id="end"/>
    <bpmn:dataObject id="profile" name="Applicant profile"/>
    <bpmn:dataObject id="creditScore" name="Credit score"/>
    <bpmn:dataObject id="lonely" name="Unused object"/>
    <bpmn:dataObjectReference id="profile-ref" dataObjectRef="profile"/>
    <bpmn:dataObjectReference id="score-ref" name="Credit score" dataObjectRef="creditScore"/>
    <bpmn:dataStoreReference id="notes-ref" name="Ledger notes" dataStoreRef="ledger"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="score"/>
    <bpmn:sequenceFlow id="f2" sourceRef="score" targetRef="end"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('#499 data symbols of a definition', () => {
  it('CKP_499_the_graph_reads_references_unreferenced_objects_stores_and_the_direction_of_each_association', () => {
    const graph = readBpmnGraph(BPMN);
    // Data are not steps.
    expect(graph.nodes.map((n) => n.id)).toEqual(['start', 'score', 'end']);
    expect(graph.data?.items).toEqual([
      { id: 'profile-ref', kind: 'dataObject', name: 'Applicant profile' },
      { id: 'score-ref', kind: 'dataObject', name: 'Credit score' },
      { id: 'notes-ref', kind: 'dataStore', name: 'Ledger notes' },
      { id: 'lonely', kind: 'dataObject', name: 'Unused object' },
    ]);
    expect(graph.data?.links.map((l) => [l.activity, l.item, l.direction])).toEqual([['score', 'profile-ref', 'read'], ['score', 'score-ref', 'write']]);
  });

  it('CKP_499_symbols_sit_in_a_band_under_the_steps_without_touching_each_other_and_arrows_point_the_way_the_data_goes', () => {
    const layout = layoutGraph(readBpmnGraph(BPMN));
    const data = layout.data;
    expect(data?.items).toHaveLength(4);
    const lowestStep = Math.max(...layout.nodes.map((n) => n.y + n.height));
    for (const item of data?.items ?? []) {
      expect(item.y).toBeGreaterThan(lowestStep);
      expect(item.x).toBeGreaterThanOrEqual(0);
      expect(item.x + item.width).toBeLessThanOrEqual(layout.width);
      expect(item.y + item.height).toBeLessThanOrEqual(layout.height);
    }
    const items = data?.items ?? [];
    for (const [i, a] of items.entries()) for (const b of items.slice(i + 1)) expect(overlaps(a, b)).toBe(false);
    const score = layout.nodes.find((n) => n.id === 'score');
    const read = data?.links.find((l) => l.direction === 'read');
    const write = data?.links.find((l) => l.direction === 'write');
    // A write leaves the step and ends on the symbol; a read starts at the symbol and ends on the step.
    expect(write?.points[0]?.y).toBe((score?.y ?? 0) + (score?.height ?? 0));
    expect(write?.points.at(-1)?.y).toBe(items.find((i) => i.id === 'score-ref')?.y);
    expect(read?.points[0]?.y).toBe(items.find((i) => i.id === 'profile-ref')?.y);
    expect(read?.points.at(-1)?.y).toBe((score?.y ?? 0) + (score?.height ?? 0));
  });

  it('CKP_499_a_process_without_data_has_no_band', () => {
    const plain = BPMN.replace(/<bpmn:data[^>]*\/>/g, '').replace(/<bpmn:data(Input|Output)Association[\s\S]*?<\/bpmn:data(Input|Output)Association>/g, '');
    const layout = layoutGraph(readBpmnGraph(plain));
    expect(layout.data).toBeUndefined();
  });
});
