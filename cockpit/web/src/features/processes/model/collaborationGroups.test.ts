import { describe, expect, it } from 'vitest';
import { readBpmnGraph } from './bpmnGraph';
import { layoutGraph } from './layout';

const XML = `<?xml version="1.0"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d" targetNamespace="urn:t">
  <bpmn:category id="cat"><bpmn:categoryValue id="val" value="Fraud checks"/></bpmn:category>
  <bpmn:collaboration id="c">
    <bpmn:participant id="pool" name="Bank" processRef="p"/>
    <bpmn:group id="across-pools" categoryValueRef="val"/>
  </bpmn:collaboration>
  <bpmn:process id="p" isExecutable="true"><bpmn:startEvent id="s"/><bpmn:endEvent id="e"/><bpmn:sequenceFlow id="f" sourceRef="s" targetRef="e"/></bpmn:process>
</bpmn:definitions>`;

describe('groups of a collaboration (#209)', () => {
  it('CKP_209_a_group_declared_in_the_collaboration_is_read_with_its_category_label', () => {
    expect(readBpmnGraph(XML).groups).toEqual([{ id: 'across-pools', label: 'Fraud checks' }]);
  });
});

describe('captions of unnamed symbols (#209)', () => {
  it('CKP_209_an_unnamed_event_has_no_caption_instead_of_its_id', () => {
    const layout = layoutGraph(readBpmnGraph(XML));
    expect(layout.nodes.find((node) => node.id === 's')?.lines).toEqual([]);
  });
});
