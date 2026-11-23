import { describe, expect, it } from 'vitest';
import { readBpmnGraph, flowOrder } from './bpmnGraph';
import { layoutGraph } from './layout';
import { entryKey } from './instanceView';
import { nodeTypeLabel } from './nodeLabels';

/** A loan application with a reminder after a day (timer, beside the main flow) and a withdrawal (message, interrupting). */
const LOAN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t">
  <bpmn:message id="M1" name="applicant-withdrew"/>
  <bpmn:process id="loan-application" isExecutable="true">
    <bpmn:startEvent id="received"/>
    <bpmn:userTask id="assess" name="Assess the application"/>
    <bpmn:endEvent id="decided"/>
    <bpmn:sequenceFlow id="f1" sourceRef="received" targetRef="assess"/>
    <bpmn:sequenceFlow id="f2" sourceRef="assess" targetRef="decided"/>
    <bpmn:subProcess id="reminder" name="Remind the underwriter" triggeredByEvent="true">
      <bpmn:startEvent id="after-a-day" isInterrupting="false"><bpmn:timerEventDefinition><bpmn:timeDuration>PT24H</bpmn:timeDuration></bpmn:timerEventDefinition></bpmn:startEvent>
      <bpmn:serviceTask id="remind" name="Send the reminder"/>
      <bpmn:endEvent id="reminded"/>
      <bpmn:sequenceFlow id="r1" sourceRef="after-a-day" targetRef="remind"/>
      <bpmn:sequenceFlow id="r2" sourceRef="remind" targetRef="reminded"/>
    </bpmn:subProcess>
    <bpmn:subProcess id="withdrawal" name="Applicant withdraws" triggeredByEvent="true">
      <bpmn:startEvent id="withdrawn"><bpmn:messageEventDefinition messageRef="M1"/></bpmn:startEvent>
      <bpmn:serviceTask id="close-file" name="Close the file"/>
      <bpmn:endEvent id="closed"/>
      <bpmn:sequenceFlow id="w1" sourceRef="withdrawn" targetRef="close-file"/>
      <bpmn:sequenceFlow id="w2" sourceRef="close-file" targetRef="closed"/>
    </bpmn:subProcess>
  </bpmn:process>
</bpmn:definitions>`;

describe('event sub-processes in the diagram and the timeline', () => {
  it('CKP_ESP_the_graph_knows_what_starts_an_event_sub_process_and_whether_it_interrupts', () => {
    const graph = readBpmnGraph(LOAN);

    expect(graph.nodes.find((n) => n.id === 'reminder')).toMatchObject({ type: 'subProcess:event', trigger: 'timer', interrupting: false });
    expect(graph.nodes.find((n) => n.id === 'withdrawal')).toMatchObject({ type: 'subProcess:event', trigger: 'message', interrupting: true });
    expect(graph.nodes.find((n) => n.id === 'after-a-day')).toMatchObject({ type: 'startEvent:timer', parentId: 'reminder', interrupting: false });
    expect(graph.nodes.find((n) => n.id === 'remind')?.parentId).toBe('reminder');
    // Its steps follow it in the timeline's order, nested under it.
    expect(flowOrder(graph).map((n) => n.id)).toEqual(expect.arrayContaining(['reminder', 'after-a-day', 'remind', 'withdrawal', 'close-file']));
  });

  it('CKP_ESP_an_open_event_sub_process_is_a_frame_around_its_steps_and_a_closed_one_a_box_with_a_count', () => {
    const graph = readBpmnGraph(LOAN);
    const closed = layoutGraph(graph);
    const open = layoutGraph(graph, (type) => type, { open: new Set(['reminder']) });

    expect(closed.nodes.find((n) => n.id === 'reminder')?.container).toEqual({ steps: 1, open: false });
    const frame = open.nodes.find((n) => n.id === 'reminder');
    const step = open.nodes.find((n) => n.id === 'remind');
    if (!frame || !step) throw new Error('nodes missing');
    expect(frame.container?.open).toBe(true);
    expect(step.x).toBeGreaterThan(frame.x);
    expect(step.x + step.width).toBeLessThan(frame.x + frame.width);
  });

  it('CKP_ESP_the_new_nodes_and_the_timeline_entry_have_words_of_their_own', () => {
    const t = (key: string) => key;
    expect(nodeTypeLabel('subProcess:event', t)).toBe('node.eventSubProcess');
    expect(nodeTypeLabel('startEvent:timer', t)).toBe('node.timerStart');
    expect(entryKey('EVENT_SUBPROCESS_STARTED')).toBe('instance.entry.EVENT_SUBPROCESS_STARTED');
  });
});
