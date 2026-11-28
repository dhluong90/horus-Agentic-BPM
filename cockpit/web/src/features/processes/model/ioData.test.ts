import { describe, expect, it } from 'vitest';
import { readBpmnGraph } from './bpmnGraph';
import { layoutGraph } from './layout';
import { placeCaptions, captionBox } from './captions';
import type { PlacedEdge, PlacedNode } from './layout';

const BPMN = `<?xml version="1.0"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="urn:t">
  <bpmn:process id="trip" isExecutable="true">
    <bpmn:ioSpecification>
      <bpmn:dataInput id="in-request" name="Client request"/>
      <bpmn:dataInput id="in-nameless"/>
      <bpmn:dataOutput id="out-booking" name="Booking"/>
    </bpmn:ioSpecification>
    <bpmn:startEvent id="start"/>
    <bpmn:endEvent id="end"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('TR_UI_209_data_inputs_and_outputs', () => {
  it('reads the inputs and outputs a process declares, and draws each as a symbol of its own kind', () => {
    const graph = readBpmnGraph(BPMN);
    expect(graph.data?.items.map((i) => [i.id, i.kind, i.name])).toEqual([
      ['in-request', 'dataInput', 'Client request'], ['in-nameless', 'dataInput', undefined], ['out-booking', 'dataOutput', 'Booking'],
    ]);
    const placed = layoutGraph(graph, () => '').data?.items ?? [];
    expect(placed).toHaveLength(3);
    // A symbol with no name has no caption lines, so its kind is written under it and no id ever is.
    expect(placed.find((i) => i.id === 'in-nameless')?.lines).toEqual([]);
  });
});

const node = (id: string, shape: PlacedNode['shape'], x: number, y: number, lines: string[]): PlacedNode => ({ id, type: 'endEvent', name: id, shape, x, y, width: 48, height: 48, lines, typeLines: [] });

describe('TR_UI_209_names_keep_clear_of_flows', () => {
  it('leaves a name where it is when no flow runs through it', () => {
    const [end] = placeCaptions([node('end', 'event', 0, 0, ['Done'])], []);
    expect(end?.captionAt).toBeUndefined();
  });
  it('moves a name off a flow that runs through its usual place', () => {
    const end = node('end', 'event', 0, 0, ['Repair completed']);
    const flow = { id: 'f', source: 'a', target: 'end', label: undefined, isDefault: false, points: [{ x: 24, y: 120 }, { x: 24, y: 60 }], labelAt: undefined } as PlacedEdge;
    const [moved] = placeCaptions([end], [flow]);
    expect(moved?.captionAt).toBeDefined();
    const box = captionBox(moved ?? end);
    expect(box.x < 24 && box.x + box.width > 24 && box.y < 120 && box.y + box.height > 60).toBe(false);
  });
});
