import type { DefinitionVersion, GraphDiff, GraphDiffChange } from '../../../api/processes/schemas';

/*
 * What the approval of a process change shows and allows (CKP-024). The engine computes the diff (getDefinitionDiff)
 * and decides every approval; this module only reads its answer: which steps and links to mark on each diagram, how
 * a changed value's path reads, and whether Approve may be offered to this user for the content on screen.
 */

/** How a step or link differs between the two versions. */
export type Mark = 'added' | 'changed' | 'removed';

/** The two diagrams of a change: the version proposed, and the version in effect it would replace. */
export type Side = 'target' | 'base';

/** The marks drawn on one diagram, by step id and by link (`from>to`). */
export interface DiagramMarks {
  nodes: ReadonlyMap<string, Mark>;
  edges: ReadonlyMap<string, Mark>;
}

export const edgeKey = (from: string, to: string): string => `${from}>${to}`;

/**
 * The marks of one side. The proposed version carries what was added and changed; the version in effect carries
 * what was removed and changed, so each diagram marks only steps it draws.
 */
export function marksFor(diff: GraphDiff, side: Side): DiagramMarks {
  const nodes = new Map<string, Mark>();
  const edges = new Map<string, Mark>();
  for (const change of diff.nodesChanged) nodes.set(change.id, 'changed');
  if (side === 'target') {
    for (const node of diff.nodesAdded) nodes.set(node.id, 'added');
    for (const edge of diff.edgesAdded) edges.set(edgeKey(edge.from, edge.to), 'added');
  } else {
    for (const node of diff.nodesRemoved) nodes.set(node.id, 'removed');
    for (const edge of diff.edgesRemoved) edges.set(edgeKey(edge.from, edge.to), 'removed');
  }
  return { nodes, edges };
}

/** Where a selected entry of the change list is drawn: a removed step only exists in the version in effect. */
export function sideOf(mark: Mark): Side {
  return mark === 'removed' ? 'base' : 'target';
}

/** One changed step and every value that changed in it, in the engine's order (by id, then path). */
export interface ChangedStep {
  id: string;
  name: string | null | undefined;
  type: string | null | undefined;
  values: readonly Pick<GraphDiffChange, 'path' | 'from' | 'to'>[];
}

export function changedSteps(diff: GraphDiff): ChangedStep[] {
  const steps = new Map<string, { id: string; name: string | null | undefined; type: string | null | undefined; values: Pick<GraphDiffChange, 'path' | 'from' | 'to'>[] }>();
  for (const change of diff.nodesChanged) {
    const step = steps.get(change.id) ?? { id: change.id, name: change.name, type: change.type, values: [] };
    step.values.push({ path: change.path, from: change.from, to: change.to });
    steps.set(change.id, step);
  }
  return [...steps.values()];
}

/** The number of entries the change list shows, by kind. */
export function counts(diff: GraphDiff): Record<Mark, number> {
  return {
    added: diff.nodesAdded.length + diff.edgesAdded.length,
    changed: changedSteps(diff).length,
    removed: diff.nodesRemoved.length + diff.edgesRemoved.length,
  };
}

/** One segment of a JSON Pointer: a member name, or the position in a list (from 0). */
export type PathPart = { kind: 'name'; name: string } | { kind: 'index'; index: number };

/** The segments of a JSON Pointer (RFC 6901): `/branches/0/when` is branches, position 0, when. */
export function pathParts(pointer: string): PathPart[] {
  if (pointer === '' || pointer === '/') return [];
  return pointer
    .replace(/^\//, '')
    .split('/')
    .map((raw) => raw.replace(/~1/g, '/').replace(/~0/g, '~'))
    .map((segment): PathPart => (/^(0|[1-9]\d*)$/.test(segment) ? { kind: 'index', index: Number(segment) } : { kind: 'name', name: segment }));
}

/** A member name in words, for a name the catalogue does not know: `escalateTo` reads "Escalate to". */
export function humanise(name: string): string {
  const words = name
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .trim()
    .toLowerCase();
  return words === '' ? name : words.charAt(0).toUpperCase() + words.slice(1);
}

/** How a link continues the flow: the plain next step, a parallel branch, the default, or the n-th condition (from 1). */
export type LinkKind = { kind: 'plain' } | { kind: 'default' } | { kind: 'condition'; number: number };

export function linkKind(kind: string): LinkKind {
  if (kind === 'default') return { kind: 'default' };
  const branch = /^branch\[(\d+)\]$/.exec(kind);
  if (branch) return { kind: 'condition', number: Number(branch[1]) + 1 };
  return { kind: 'plain' };
}

/**
 * Why Approve is not offered for the content on screen, or undefined when it is:
 * - `waiting`: the version is not waiting for approval, or the user may not approve here;
 * - `self-requested`, `self-created`: four eyes (the engine refuses them anyway, 403 SELF_APPROVAL_FORBIDDEN);
 * - `checking`: the change list is loading;
 * - `unavailable`: the change list could not be read, so there is nothing reviewed to approve;
 * - `truncated`: the change list was cut off, so it carries no hash that may be approved (PD-139);
 * - `changed`: the content moved on since the user first saw it; they confirm they checked it again.
 */
export type ApprovalBlock = 'waiting' | 'self-requested' | 'self-created' | 'checking' | 'unavailable' | 'truncated' | 'changed';

/** The state of the change list: loaded, the first version (nothing in effect to compare), loading, or failed. */
export type DiffState = { status: 'ready'; diff: GraphDiff } | { status: 'first-version' } | { status: 'loading' } | { status: 'error' };

/**
 * The hash an approval names: the diff's approvableHash, or for a first version (nothing in effect to compare) the
 * version's own definitionHash. Undefined while loading, after an error, or when the list was cut off.
 */
export function reviewedHash(state: DiffState, version: DefinitionVersion): string | undefined {
  if (state.status === 'ready') return state.diff.approvableHash ?? undefined;
  if (state.status === 'first-version') return version.definitionHash;
  return undefined;
}

export function approvalBlock(input: {
  version: DefinitionVersion;
  state: DiffState;
  userId: string;
  canApprove: boolean;
  /** The hash of the content when the user first saw it on this page. */
  seenHash: string | undefined;
}): ApprovalBlock | undefined {
  const { version, state, userId, canApprove, seenHash } = input;
  if (version.status !== 'PENDING_APPROVAL' || !canApprove) return 'waiting';
  // Four eyes first: the requester reads why, whatever the state of the list.
  if (version.requestedBy === userId) return 'self-requested';
  if (version.createdBy === userId) return 'self-created';
  if (state.status === 'loading') return 'checking';
  if (state.status === 'error') return 'unavailable';
  if (state.status === 'ready' && (state.diff.truncated || state.diff.approvableHash === null)) return 'truncated';
  const hash = reviewedHash(state, version);
  // What is on screen must be the version as stored now, and the content the user first saw.
  if (hash === undefined || hash !== version.definitionHash || (seenHash !== undefined && hash !== seenHash)) return 'changed';
  return undefined;
}


/** The diagram's node type key for a step type of the canonical form, so both read with the same words. */
const CANONICAL_TYPES: Readonly<Record<string, string>> = {
  start: 'startEvent',
  end: 'endEvent',
  agentTask: 'serviceTask:agentTask',
  decision: 'businessRuleTask',
  timer: 'intermediateCatchEvent:timer',
  callProcess: 'callActivity',
  saga: 'transaction',
  throwCompensation: 'intermediateThrowEvent:compensate',
};

export function diagramType(canonical: string | null | undefined): string {
  if (canonical === null || canonical === undefined) return 'other';
  return CANONICAL_TYPES[canonical] ?? canonical;
}
