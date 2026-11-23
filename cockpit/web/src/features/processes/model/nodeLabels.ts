import type { MessageKey } from '../../../i18n/en';
import { isKnownNodeType, type KnownNodeType } from './bpmnGraph';

const KEYS: Record<KnownNodeType, MessageKey> = {
  startEvent: 'node.startEvent',
  endEvent: 'node.endEvent',
  userTask: 'node.userTask',
  serviceTask: 'node.serviceTask',
  'serviceTask:agentTask': 'node.agentTask',
  'serviceTask:compensation': 'node.undoStep',
  businessRuleTask: 'node.businessRuleTask',
  task: 'node.task',
  manualTask: 'node.manualTask',
  scriptTask: 'node.scriptTask',
  exclusiveGateway: 'node.exclusiveGateway',
  parallelGateway: 'node.parallelGateway',
  inclusiveGateway: 'node.inclusiveGateway',
  complexGateway: 'node.complexGateway',
  adHocSubProcess: 'node.adHocSubProcess',
  'intermediateCatchEvent:multiple': 'node.multiple',
  'intermediateCatchEvent:parallelMultiple': 'node.parallelMultiple',
  'startEvent:multiple': 'node.multiple',
  'startEvent:parallelMultiple': 'node.parallelMultiple',
  'boundaryEvent:multiple': 'node.multiple',
  'boundaryEvent:parallelMultiple': 'node.parallelMultiple',
  'intermediateCatchEvent:timer': 'node.timer',
  callActivity: 'node.callActivity',
  transaction: 'node.transaction',
  subProcess: 'node.subProcess',
  'subProcess:event': 'node.eventSubProcess',
  'startEvent:timer': 'node.timerStart',
  'startEvent:error': 'node.errorStart',
  'startEvent:escalation': 'node.escalationStart',
  'startEvent:compensate': 'node.compensationStart',
  'intermediateThrowEvent:compensate': 'node.compensate',
  'boundaryEvent:timer': 'node.boundaryTimer',
  'boundaryEvent:error': 'node.boundaryError',
  'boundaryEvent:escalation': 'node.boundaryEscalation',
  'boundaryEvent:compensate': 'node.boundaryCompensate',
  'boundaryEvent:cancel': 'node.boundaryCancel',
  'endEvent:compensate': 'node.compensationEnd',
  'endEvent:cancel': 'node.cancelEnd',
  'endEvent:error': 'node.errorEnd',
  'endEvent:escalation': 'node.escalationEnd',
  'endEvent:terminate': 'node.terminateEnd',
  'intermediateThrowEvent:escalation': 'node.escalationThrow',
  'intermediateThrowEvent:link': 'node.linkThrow',
  'intermediateCatchEvent:link': 'node.linkCatch',
  'startEvent:signal': 'node.signal',
  'startEvent:conditional': 'node.conditional',
  'endEvent:signal': 'node.signal',
  'intermediateCatchEvent:signal': 'node.signal',
  'intermediateThrowEvent:signal': 'node.signal',
  'intermediateCatchEvent:conditional': 'node.conditional',
  'startEvent:message': 'node.messageStart',
  'endEvent:message': 'node.messageEnd',
  'intermediateCatchEvent:message': 'node.messageCatch',
  'intermediateThrowEvent:message': 'node.messageThrow',
  receiveTask: 'node.receiveTask',
  sendTask: 'node.sendTask',
};

/** Event definitions a node of an unknown element type can still be named by: `boundaryEvent:timer` is a timer. */
const KIND_KEYS: Readonly<Record<string, MessageKey>> = {
  timer: 'node.timer',
  message: 'node.message',
  signal: 'node.signal',
  error: 'node.error',
  escalation: 'node.escalation',
  conditional: 'node.conditional',
  compensate: 'node.compensate',
  terminate: 'node.terminate',
  link: 'node.link',
  cancel: 'node.cancel',
};

/** The icon a node is drawn with: its own type, the timer icon for any timer, or the generic icon. */
export type NodeIconKind = KnownNodeType | 'generic';

export function nodeIconKind(type: string): NodeIconKind {
  if (isKnownNodeType(type)) return type;
  return type.split(':')[1] === 'timer' ? 'intermediateCatchEvent:timer' : 'generic';
}

/**
 * The type of a node in words, never the key the engine sent. A type this cockpit does not draw is named by its
 * event definition when it has a known one (`boundaryEvent:timer` reads "Timer"), otherwise "Other step".
 */
export function nodeTypeLabel(type: string, t: (key: MessageKey) => string): string {
  if (isKnownNodeType(type)) return t(KEYS[type]);
  const kind = type.split(':')[1];
  const key = kind !== undefined ? KIND_KEYS[kind] : undefined;
  return t(key ?? 'node.other');
}
