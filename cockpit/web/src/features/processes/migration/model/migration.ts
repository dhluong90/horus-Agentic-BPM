import type { MigrationDryRun, MigrationExecution, MigrationPlan, MigrationStepCancellation, MigrationStepMapping } from '../../../../api/processes/schemas';
import type { DiagramGraph } from '../../model/bpmnGraph';
import type { DiagramMarks, Mark } from '../../model/graphDiff';

/*
 * What the migration screens decide from the engine's answers (CKP-027, CKP-056 to CKP-058). The page shows what the
 * engine reports and never computes it: counts, conflicts and statuses are the engine's. What lives here is which
 * step is drawn as what, and which single action the screen offers next, with the reason when it offers none.
 */

/** How the plan treats one step of the old version: moved to a renamed step, stopped and continued, or not covered. */
export type StepState = 'mapped' | 'cancelled' | 'unmapped';

export interface StepRow {
  /** The step in the version the cases are on now. */
  id: string;
  name: string;
  state: StepState;
  /** The step in the new version where the work goes, or where the case continues; none when unmapped. */
  to: string | undefined;
  toName: string | undefined;
  /** Why the work at a removed step is cancelled, as the plan says. */
  reason: string | undefined;
  /** Cases waiting at the step, from the engine's dry run; undefined before the plan is checked. */
  cases: number | undefined;
  /** Cases at the step that stay on the old version because a call is still running (a cancelled step). */
  inFlight: number;
}

const nameOf = (graph: DiagramGraph, id: string): string => graph.nodes.find((node) => node.id === id)?.name ?? id;

/**
 * The steps a plan has to say something about, in the order of the old diagram: every step the new version no
 * longer has under the same id, whether the plan maps it, cancels it or says nothing; plus any step the engine's
 * dry run reports as not covered (for example one whose type changed). Steps that keep their id and type are not
 * listed: they move as they are.
 */
export function stepRows(
  plan: { mappings: readonly MigrationStepMapping[]; cancellations: readonly MigrationStepCancellation[] },
  source: DiagramGraph,
  target: DiagramGraph,
  dryRun: MigrationDryRun | undefined,
): StepRow[] {
  const targetIds = new Set(target.nodes.map((node) => node.id));
  const mapped = new Map(plan.mappings.map((item) => [item.from, item.to]));
  const cancelled = new Map(plan.cancellations.map((item) => [item.step, item]));
  const reported = new Set((dryRun?.nodes ?? []).filter((node) => node.mapping === 'STEP_NOT_IN_TARGET' || node.mapping === 'STEP_TYPE_CHANGED').map((node) => node.nodeId));
  const rows: StepRow[] = [];
  for (const node of source.nodes) {
    const listed = !targetIds.has(node.id) || mapped.has(node.id) || cancelled.has(node.id) || reported.has(node.id);
    if (!listed) continue;
    const move = mapped.get(node.id);
    const stop = cancelled.get(node.id);
    const live = dryRun?.nodes.find((item) => item.nodeId === node.id);
    const conflict = (code: string) => dryRun?.conflicts.find((item) => item.nodeId === node.id && item.code === code)?.instances.count ?? 0;
    const to = move ?? stop?.moveTo;
    const state: StepState = move !== undefined ? 'mapped' : stop !== undefined ? 'cancelled' : 'unmapped';
    const cases = dryRun === undefined ? undefined : state === 'unmapped' ? Math.max(conflict('STEP_NOT_IN_TARGET'), conflict('STEP_TYPE_CHANGED'), live?.liveTokens ?? 0) : live?.liveTokens ?? 0;
    rows.push({
      id: node.id, name: node.name ?? node.id, state, to, toName: to !== undefined ? nameOf(target, to) : undefined,
      reason: stop?.reason, cases, inFlight: conflict('STEP_IN_FLIGHT'),
    });
  }
  return rows;
}

/** The marks of the two diagrams: a step the plan carries over is "changed", one it does not cover is "removed". */
export function diagramMarks(rows: readonly StepRow[]): { source: DiagramMarks; target: DiagramMarks } {
  const source = new Map<string, Mark>();
  const target = new Map<string, Mark>();
  for (const row of rows) {
    source.set(row.id, row.state === 'unmapped' ? 'removed' : 'changed');
    if (row.to !== undefined) target.set(row.to, 'changed');
  }
  return { source: { nodes: source, edges: new Map() }, target: { nodes: target, edges: new Map() } };
}

/** What the screen offers next, always one action; `why` is the one line shown beside a disabled one. */
export type NextStep =
  | { kind: 'check'; because: 'first' | 'expired' | 'changed' | 'rejected' }
  | { kind: 'propose'; movable: number }
  | { kind: 'cannot-propose'; why: 'conflicts' | 'empty' }
  | { kind: 'approve'; movable: number | undefined }
  | { kind: 'cannot-approve'; why: 'self' | 'checking' | 'conflicts' | 'changed' }
  | { kind: 'watch' }
  | { kind: 'done' }
  | { kind: 'read-only' };

export interface NextStepInput {
  plan: Pick<MigrationPlan, 'status' | 'planHash'>;
  execution: Pick<MigrationExecution, 'status' | 'proposedBy' | 'lockVersion'> | undefined;
  /** The dry run this screen holds, if any. */
  dryRun: MigrationDryRun | undefined;
  /** A dry run is being read now. */
  checking: boolean;
  now: number;
  userId: string;
  canManage: boolean;
  /** The proposal's lockVersion when the user first saw it; Approve names exactly this one. */
  seenLock: number | undefined;
}

/** A check is good until its `validUntil`; a later one, or one of another plan, is not a check of this plan. */
export function checkIsCurrent(dryRun: MigrationDryRun | undefined, planHash: string, now: number): boolean {
  return dryRun !== undefined && dryRun.planHash === planHash && Date.parse(dryRun.validUntil) > now;
}

export function nextStep(input: NextStepInput): NextStep {
  const { plan, execution, dryRun, checking, now, userId, canManage, seenLock } = input;
  const status = execution?.status ?? plan.status;
  if (status === 'APPROVED' || status === 'RUNNING') return { kind: 'watch' };
  if (status === 'COMPLETED' || status === 'COMPLETED_WITH_FAILURES') return { kind: 'done' };
  if (!canManage) return { kind: 'read-only' };
  if (status === 'PROPOSED') {
    if (execution?.proposedBy === userId) return { kind: 'cannot-approve', why: 'self' };
    if (seenLock !== undefined && execution !== undefined && execution.lockVersion !== seenLock) return { kind: 'cannot-approve', why: 'changed' };
    if (dryRun === undefined || checking) return { kind: 'cannot-approve', why: 'checking' };
    if (!dryRun.valid) return { kind: 'cannot-approve', why: 'conflicts' };
    return { kind: 'approve', movable: dryRun.instances.movable };
  }
  // PLANNED, or REJECTED and open to a new proposal.
  if (dryRun === undefined) return { kind: 'check', because: status === 'REJECTED' ? 'rejected' : 'first' };
  if (dryRun.planHash !== plan.planHash) return { kind: 'check', because: 'changed' };
  if (Date.parse(dryRun.validUntil) <= now) return { kind: 'check', because: 'expired' };
  if (!dryRun.valid) return { kind: 'cannot-propose', why: 'conflicts' };
  if (dryRun.instances.movable === 0) return { kind: 'cannot-propose', why: 'empty' };
  return { kind: 'propose', movable: dryRun.instances.movable };
}

/** Whether the run is over, with or without cases left behind. */
export const isFinished = (status: MigrationPlan['status']): boolean => status === 'COMPLETED' || status === 'COMPLETED_WITH_FAILURES';

/** How many cases did not move: failed and skipped ones, as the engine counts them. */
export const notMoved = (progress: MigrationExecution['progress']): number => progress.failed + progress.skipped;

/** The words for the reason a case did not move: the engine's code, never its detail text. */
export const OUTCOME_REASONS = {
  LOCK_CONFLICT: 'migration.why.LOCK_CONFLICT',
  STEP_NOT_IN_TARGET: 'migration.why.STEP_NOT_IN_TARGET',
  STEP_TYPE_CHANGED: 'migration.why.STEP_TYPE_CHANGED',
  NOT_RUNNING: 'migration.why.NOT_RUNNING',
  NOT_ON_SOURCE: 'migration.why.NOT_ON_SOURCE',
  INSTANCE_GONE: 'migration.why.INSTANCE_GONE',
  STEP_IN_FLIGHT: 'migration.why.STEP_IN_FLIGHT',
} as const;

export type ReasonCode = keyof typeof OUTCOME_REASONS;

export function reasonKey(code: string): (typeof OUTCOME_REASONS)[ReasonCode] | 'migration.why.other' {
  return code in OUTCOME_REASONS ? OUTCOME_REASONS[code as ReasonCode] : 'migration.why.other';
}

/**
 * The instructions of a new plan from what the operator chose for each step that is gone: `move:<step>` carries the
 * work to a renamed step, `stop:<step>` cancels it and continues at that step, with the operator's reason.
 */
export interface StepChoice {
  step: string;
  /** `move:<target step>`, `stop:<target step>` or empty while the operator has not chosen. */
  choice: string;
  reason: string;
}

export function planInstructions(choices: readonly StepChoice[]): { mappings: MigrationStepMapping[]; cancellations: MigrationStepCancellation[] } {
  const mappings: MigrationStepMapping[] = [];
  const cancellations: MigrationStepCancellation[] = [];
  for (const item of choices) {
    const [kind, ...rest] = item.choice.split(':');
    const to = rest.join(':');
    if (kind === 'move' && to !== '') mappings.push({ from: item.step, to });
    if (kind === 'stop' && to !== '') cancellations.push({ step: item.step, moveTo: to, reason: item.reason.trim() });
  }
  return { mappings, cancellations };
}

/** How many choices are still open: nothing chosen, or a stop without its reason. */
export function openChoices(choices: readonly StepChoice[]): number {
  return choices.filter((item) => item.choice === '' || (item.choice.startsWith('stop:') && item.reason.trim() === '')).length;
}
