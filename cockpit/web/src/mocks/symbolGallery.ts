/*
 * One of every BPMN symbol the cockpit draws, as a definition with a stored layout (#209): every event definition at
 * every position it is valid in, interrupting and non-interrupting boundaries, the gateway kinds, the task types, loop
 * and multi-instance markers, compensation, and the sub-process kinds. The steps hang in rows with no flows between
 * them except two (a conditional and a default one), so the picture is the reference for what each symbol looks like.
 */

import type { DemoDefinition } from './processDemo';

interface Cell { id: string; name: string; xml: string; kind: 'event' | 'gateway' | 'task'; extra?: string }

type Definition = 'message' | 'timer' | 'conditional' | 'signal' | 'escalation' | 'error' | 'compensate' | 'cancel' | 'terminate' | 'link';

const definitionXml = (kind: Definition, id: string) => {
  if (kind === 'timer') return '<bpmn:timerEventDefinition><bpmn:timeDuration>P1D</bpmn:timeDuration></bpmn:timerEventDefinition>';
  if (kind === 'conditional') return '<bpmn:conditionalEventDefinition><bpmn:condition>x &gt; 1</bpmn:condition></bpmn:conditionalEventDefinition>';
  if (kind === 'link') return `<bpmn:linkEventDefinition name="${id}"/>`;
  return `<bpmn:${kind}EventDefinition/>`;
};

const event = (local: string, id: string, name: string, kinds: readonly Definition[], attrs = ''): Cell => ({
  id, name, kind: 'event',
  xml: `<bpmn:${local} id="${id}" name="${name}"${attrs}>${kinds.map((kind) => definitionXml(kind, id)).join('')}</bpmn:${local}>`,
});

const rows: Cell[][] = [];

rows.push([
  event('startEvent', 's-none', 'Start', []),
  ...(['message', 'timer', 'conditional', 'signal'] as const).map((k) => event('startEvent', `s-${k}`, `Start ${k}`, [k])),
  event('startEvent', 's-multiple', 'Start multiple', ['message', 'timer']),
  event('startEvent', 's-parallel', 'Start parallel', ['message', 'timer'], ' parallelMultiple="true"'),
]);
rows.push([
  event('intermediateCatchEvent', 'c-none', 'Catch', []),
  ...(['message', 'timer', 'conditional', 'signal'] as const).map((k) => event('intermediateCatchEvent', `c-${k}`, `Catch ${k}`, [k])),
  event('intermediateCatchEvent', 'c-link', 'Catch link', ['link']),
  event('intermediateCatchEvent', 'c-multiple', 'Catch multiple', ['message', 'timer']),
  event('intermediateCatchEvent', 'c-parallel', 'Catch parallel', ['message', 'timer'], ' parallelMultiple="true"'),
]);
rows.push([
  event('intermediateThrowEvent', 't-none', 'Throw', []),
  ...(['message', 'escalation', 'signal', 'compensate'] as const).map((k) => event('intermediateThrowEvent', `t-${k}`, `Throw ${k}`, [k])),
  event('intermediateThrowEvent', 't-link', 'Throw link', ['link']),
  event('intermediateThrowEvent', 't-multiple', 'Throw multiple', ['message', 'signal']),
]);
rows.push([
  event('endEvent', 'e-none', 'End', []),
  ...(['message', 'escalation', 'error', 'cancel', 'compensate', 'signal', 'terminate'] as const).map((k) => event('endEvent', `e-${k}`, `End ${k}`, [k])),
  event('endEvent', 'e-multiple', 'End multiple', ['message', 'signal']),
]);

/** A host task with a boundary event on its foot. */
const hosted = (id: string, name: string, boundary: string, kinds: readonly Definition[], attrs = '', hostLocal = 'task'): Cell => ({
  id, name, kind: 'task',
  xml: `<bpmn:${hostLocal} id="${id}" name="${name}"/>`,
  extra: `<bpmn:boundaryEvent id="${boundary}" name="${name.replace('On ', '')}" attachedToRef="${id}"${attrs}>${kinds.map((kind) => definitionXml(kind, boundary)).join('')}</bpmn:boundaryEvent>`,
});
rows.push([
  hosted('h-message', 'On message', 'b-message', ['message']),
  hosted('h-timer', 'On timer', 'b-timer', ['timer'], ' cancelActivity="false"'),
  hosted('h-error', 'On error', 'b-error', ['error']),
  hosted('h-escalation', 'On escalation', 'b-escalation', ['escalation'], ' cancelActivity="false"'),
  hosted('h-signal', 'On signal', 'b-signal', ['signal']),
  hosted('h-conditional', 'On condition', 'b-conditional', ['conditional'], ' cancelActivity="false"'),
  hosted('h-compensate', 'On undo', 'b-compensate', ['compensate']),
  hosted('h-multiple', 'On several', 'b-multiple', ['message', 'timer']),
  hosted('h-parallel', 'On all', 'b-parallel', ['message', 'timer'], ' parallelMultiple="true"'),
]);

const gate = (local: string, id: string, name: string, attrs = ''): Cell => ({ id, name, kind: 'gateway', xml: `<bpmn:${local} id="${id}" name="${name}"${attrs}/>` });
rows.push([
  gate('exclusiveGateway', 'g-exclusive', 'Exclusive'),
  gate('parallelGateway', 'g-parallel', 'Parallel'),
  gate('inclusiveGateway', 'g-inclusive', 'Inclusive'),
  gate('complexGateway', 'g-complex', 'Complex'),
  gate('eventBasedGateway', 'g-event', 'Event first'),
  gate('eventBasedGateway', 'g-event-all', 'Event all', ' eventGatewayType="Parallel"'),
  gate('eventBasedGateway', 'g-event-start', 'Event start', ' instantiate="true"'),
  gate('eventBasedGateway', 'g-event-start-all', 'Event start all', ' instantiate="true" eventGatewayType="Parallel"'),
]);

const task = (local: string, id: string, name: string, attrs = '', inner = ''): Cell => ({ id, name, kind: 'task', xml: `<bpmn:${local} id="${id}" name="${name}"${attrs}>${inner}</bpmn:${local}>` });
rows.push([
  task('task', 'k-task', 'Task'),
  task('userTask', 'k-user', 'User task'),
  task('serviceTask', 'k-service', 'Service task'),
  task('sendTask', 'k-send', 'Send task'),
  task('receiveTask', 'k-receive', 'Receive task'),
  task('manualTask', 'k-manual', 'Manual task'),
  task('scriptTask', 'k-script', 'Script task'),
  task('businessRuleTask', 'k-rule', 'Rule task'),
  task('serviceTask', 'k-agent', 'Agent task', ' horus:type="agentTask" horus:agent="gallery"'),
]);
rows.push([
  task('callActivity', 'm-call', 'Call activity', ' calledElement="other"'),
  task('userTask', 'm-loop', 'Standard loop', '', '<bpmn:standardLoopCharacteristics/>'),
  task('serviceTask', 'm-parallel', 'Parallel instances', '', '<bpmn:multiInstanceLoopCharacteristics/>'),
  task('scriptTask', 'm-sequential', 'Sequential instances', '', '<bpmn:multiInstanceLoopCharacteristics isSequential="true"/>'),
  task('serviceTask', 'm-undo', 'Undo service', ' isForCompensation="true"'),
  task('userTask', 'm-undo-user', 'Undo user task', ' isForCompensation="true"'),
  task('userTask', 'm-undo-loop', 'Undo and loop', ' isForCompensation="true"', '<bpmn:standardLoopCharacteristics/>'),
  task('callActivity', 'm-call-loop', 'Call and instances', ' calledElement="other"', '<bpmn:multiInstanceLoopCharacteristics/>'),
]);

const inner = (id: string) => `<bpmn:startEvent id="${id}-in" name="Begin"/>`;
const container = (local: string, id: string, name: string, attrs = '', body = inner(id)): Cell => ({ id, name, kind: 'task', xml: `<bpmn:${local} id="${id}" name="${name}"${attrs}>${body}</bpmn:${local}>` });
rows.push([
  container('subProcess', 'p-embedded', 'Embedded'),
  container('subProcess', 'p-event', 'Event interrupting', ' triggeredByEvent="true"', '<bpmn:startEvent id="p-event-in" name="Begin"><bpmn:errorEventDefinition/></bpmn:startEvent>'),
  container('subProcess', 'p-event-soft', 'Event non-interrupting', ' triggeredByEvent="true"', '<bpmn:startEvent id="p-event-soft-in" name="Begin" isInterrupting="false"><bpmn:messageEventDefinition/></bpmn:startEvent>'),
  container('transaction', 'p-transaction', 'Transaction'),
  container('adHocSubProcess', 'p-adhoc', 'Ad hoc', ' ordering="Parallel"'),
  container('adHocSubProcess', 'p-adhoc-seq', 'Ad hoc in order', ' ordering="Sequential"'),
  container('subProcess', 'p-loop', 'Embedded, repeated', '', `${inner('p-loop')}<bpmn:multiInstanceLoopCharacteristics isSequential="true"/>`),
]);

const PITCH_X = 150;
const ROW_Y = [20, 120, 220, 320, 420, 575, 690, 820, 950];
const boxFor = (cell: Cell, x: number, y: number) => cell.kind === 'task'
  ? { x, y, width: 116, height: 80 }
  : cell.kind === 'gateway' ? { x: x + 33, y, width: 50, height: 50 } : { x: x + 40, y, width: 36, height: 36 };

const cells = rows.flat();
const nodes: Record<string, { x: number; y: number; width: number; height: number }> = {};
for (const [r, row] of rows.entries()) {
  for (const [c, cell] of row.entries()) nodes[cell.id] = boxFor(cell, 20 + c * PITCH_X, ROW_Y[r] ?? 0);
}

export const SYMBOL_GALLERY_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="Definitions_gallery" targetNamespace="urn:horus-bpm:demo">
  <bpmn:process id="symbol-gallery" name="BPMN symbols" isExecutable="false">
${cells.map((cell) => `    ${cell.xml}${cell.extra === undefined ? '' : `\n    ${cell.extra}`}`).join('\n')}
    <bpmn:sequenceFlow id="flow-if" sourceRef="k-task" targetRef="k-user" name="Large"><bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">amount &gt; 1000</bpmn:conditionExpression></bpmn:sequenceFlow>
    <bpmn:exclusiveGateway id="g-fork" name="Fork" default="flow-else"/>
    <bpmn:sequenceFlow id="flow-else" sourceRef="g-fork" targetRef="k-agent" name="Otherwise"/>
  </bpmn:process>
</bpmn:definitions>`;

export const SYMBOL_GALLERY_LAYOUT = {
  schemaVersion: 1,
  nodes: { ...nodes, 'g-fork': { x: 20 + 9 * PITCH_X + 33, y: (ROW_Y[6] ?? 0) + 15, width: 50, height: 50 } },
} as const;

export const SYMBOL_GALLERY: DemoDefinition = {
  key: 'symbol-gallery', name: 'BPMN symbols', version: 1, status: 'ACTIVE', effectiveVersion: 1, nodeCount: cells.length, lastChangedAt: '2026-11-28T09:00:00Z', bpmn: SYMBOL_GALLERY_BPMN,
};
