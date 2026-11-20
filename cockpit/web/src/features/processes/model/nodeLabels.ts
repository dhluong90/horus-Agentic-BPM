import type { MessageKey } from '../../../i18n/en';
import { isKnownNodeType, type KnownNodeType } from './bpmnGraph';

const KEYS: Record<KnownNodeType, MessageKey> = {
  startEvent: 'node.startEvent',
  endEvent: 'node.endEvent',
  userTask: 'node.userTask',
  serviceTask: 'node.serviceTask',
  'serviceTask:agentTask': 'node.agentTask',
  businessRuleTask: 'node.businessRuleTask',
  exclusiveGateway: 'node.exclusiveGateway',
  parallelGateway: 'node.parallelGateway',
  inclusiveGateway: 'node.inclusiveGateway',
  'intermediateCatchEvent:timer': 'node.timer',
  callActivity: 'node.callActivity',
  transaction: 'node.transaction',
  'intermediateThrowEvent:compensate': 'node.compensate',
};

/** The type of a node in words; a type this cockpit does not know is named by its BPMN type key, as the engine sent it. */
export function nodeTypeLabel(type: string, t: (key: MessageKey) => string): string {
  return isKnownNodeType(type) ? t(KEYS[type]) : type;
}
