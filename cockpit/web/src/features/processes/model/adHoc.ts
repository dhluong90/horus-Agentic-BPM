import type { ProcessInstance } from '../../../api/processes/schemas';
import type { DiagramGraph, DiagramNode } from './bpmnGraph';

/*
 * The activities of an ad-hoc sub-process (#500) that the operator may start now. The engine decides again when the
 * action arrives; this only keeps the cockpit from offering what it will refuse: nothing once the sub-process has
 * ended, and with ordering Sequential nothing while one of its activities runs.
 */

/** Statuses of a token that no longer takes part in execution. */
const ENDED = new Set(['COMPLETED', 'CANCELLED', 'COMPENSATED', 'JOINED']);

export interface AdHocScope {
  scopeTokenId: string;
  ordering: 'parallel' | 'sequential';
  /** The activities that may start now. */
  enabled: DiagramNode[];
  /** How many of its activities run now. */
  running: number;
}

/** The open scope of an ad-hoc sub-process of the case, with the activities enabled in it; undefined when it is not open. */
export function adHocScope(graph: DiagramGraph, instance: ProcessInstance, adHocId: string): AdHocScope | undefined {
  const node = graph.nodes.find((item) => item.id === adHocId && item.type === 'adHocSubProcess');
  if (node === undefined || instance.status !== 'RUNNING') return undefined;
  const tokens = instance.tokens ?? [];
  const scope = tokens.find((token) => token.kind === 'SCOPE' && token.nodeId === adHocId && !ENDED.has(token.status));
  if (scope === undefined) return undefined;
  const running = tokens.filter((token) => token.scopeTokenId === scope.id && !ENDED.has(token.status)).length;
  const ordering = node.ordering ?? 'parallel';
  const inside = graph.nodes.filter((item) => item.parentId === adHocId);
  return { scopeTokenId: scope.id, ordering, enabled: ordering === 'sequential' && running > 0 ? [] : inside, running };
}
