import { describe, expect, it } from 'vitest';
import { drawingBounds, drawnBoxes, FIT_PADDING } from './bounds';
import { readBpmnGraph } from './bpmnGraph';
import { layoutGraph } from './layout';

const BPMN = `<?xml version="1.0"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="urn:t">
  <bpmn:process id="p" isExecutable="true">
    <bpmn:startEvent id="start" name="Application received by the branch"/>
    <bpmn:userTask id="capture" name="Capture"/>
    <bpmn:boundaryEvent id="timer" attachedToRef="capture"><bpmn:timerEventDefinition/></bpmn:boundaryEvent>
    <bpmn:endEvent id="end"/>
    <bpmn:dataObject id="profile" name="Applicant profile with a long name"/>
    <bpmn:dataObjectReference id="profile-ref" dataObjectRef="profile"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="capture"/>
    <bpmn:sequenceFlow id="f2" sourceRef="capture" targetRef="end"/>
  </bpmn:process>
</bpmn:definitions>`;

describe('the Fit bounds hold everything drawn', () => {
  const layout = layoutGraph(readBpmnGraph(BPMN));

  it('CKP_069_every_drawn_box_lies_inside_the_bounds_with_the_padding_to_spare', () => {
    const bounds = drawingBounds(layout);
    for (const box of drawnBoxes(layout)) {
      expect(box.x).toBeGreaterThanOrEqual(bounds.x + FIT_PADDING - 1);
      expect(box.y).toBeGreaterThanOrEqual(bounds.y + FIT_PADDING - 1);
      expect(box.x + box.width).toBeLessThanOrEqual(bounds.x + bounds.width - FIT_PADDING + 1);
      expect(box.y + box.height).toBeLessThanOrEqual(bounds.y + bounds.height - FIT_PADDING + 1);
    }
  });

  it('CKP_069_a_badge_that_hangs_above_a_step_grows_the_bounds', () => {
    const top = layout.nodes.reduce((least, node) => Math.min(least, node.y), Number.POSITIVE_INFINITY);
    const plain = drawingBounds(layout);
    const badged = drawingBounds(layout, undefined, [{ x: 0, y: top - 60, width: 150, height: 40 }]);
    expect(badged.y).toBeLessThan(plain.y);
  });

  it('CKP_069_a_boundary_event_sits_at_the_right_of_its_step_clear_of_the_type_label', () => {
    const host = layout.nodes.find((node) => node.id === 'capture');
    const timer = layout.nodes.find((node) => node.id === 'timer');
    if (host === undefined || timer === undefined) throw new Error('nodes missing');
    expect(timer.x + timer.width / 2).toBeGreaterThan(host.x + host.width / 2);
  });
});
