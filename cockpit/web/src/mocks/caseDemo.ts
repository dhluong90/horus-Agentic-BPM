import { INSTANCE_ID, REVIEW_TASK_ID } from './processDemo';

/*
 * Demo cases of the mock Process Engine, in the shapes of getInstance, listInstances and getInstanceActivities
 * (PRE-027): a loan case waiting for a compliance reviewer after its agent answer was refused, a loan case stopped by
 * an incident, a loan case already booked, and an onboarding case waiting for a credit officer after its document
 * check took two attempts. Durations and attempts are the engine's figures, written here as it would send them.
 */

export const INCIDENT_INSTANCE_ID = '01JD4I5NC1DENT00000000IN05';
export const BOOKED_INSTANCE_ID = '01JD4I3B00KED000000000BK03';
export const KYC_INSTANCE_ID = '01JD4I7KYC0000000000000KY7';
export const UNDONE_INSTANCE_ID = '01JD4U9NDONE00000000000U09';
export const STUCK_UNDO_INSTANCE_ID = '01JD4S7UCKUNDO000000000S10';
/** The incident the stuck undo raised; the incident queue lists it (CKP-008) and the timeline links to it (CKP-049). */
export const STUCK_UNDO_INCIDENT_ID = '01JD4N0INCIDENT000000UND10';

export interface DemoCase {
  instance: Record<string, unknown> & { id: string; definitionKey: string; startedAt: string; status: string };
  activities: {
    instanceId: string;
    startedAt: string;
    endedAt: string | null;
    nodes: Record<string, unknown>[];
    entries: Record<string, unknown>[];
    compensations?: Record<string, unknown>[];
    hasMore: boolean;
  };
}

interface Step {
  nodeId: string;
  state: 'ACTIVE' | 'COMPLETED' | 'INCIDENT';
  enteredAt: string;
  completedAt?: string;
  durationMs?: number;
  attempts?: number;
  actor?: string;
  incident?: { code: string; openedAt: string };
}

const node = (step: Step) => ({
  nodeId: step.nodeId, state: step.state, enteredAt: step.enteredAt, completedAt: step.completedAt ?? null,
  durationMs: step.durationMs ?? null, attempts: step.attempts ?? 1, actor: step.actor ?? null, incident: step.incident ?? null,
});

let entrySeq = 0;
const entry = (type: string, occurredAt: string, nodeId: string | null = null, actor: string | null = null, resultVariable?: string) => {
  entrySeq += 1;
  return { id: `01JD4H${String(entrySeq).padStart(20, '0')}`, type, nodeId, actor, occurredAt, ...(resultVariable !== undefined ? { resultVariable } : {}) };
};

type UndoFields = { scope?: string; compensates?: string; handler?: string; attempt?: number; errorCode?: string; cause?: string };

/** An entry of an undo, as the engine marks it: `kind` COMPENSATION, the saga scope, and what each undo step undoes (PRE-203). */
const undo = (type: string, occurredAt: string, fields: UndoFields = {}) => ({
  ...entry(type, occurredAt, null, 'engine:loans-7f9c'), kind: 'COMPENSATION', scopeTokenId: 'DISBURSE-SCOPE', scope: 'disburse', ...fields,
});

const scope = (id: string, nodeId: string) => ({ id: `${id.slice(0, 20)}SCOPE0`, kind: 'SCOPE', scopeTokenId: null, nodeId, status: 'WAITING_SCOPE' });
const flowToken = (id: string, suffix: string, nodeId: string, status: string) => ({ id: `${id.slice(0, 20)}FLOW${suffix}`, kind: 'FLOW', scopeTokenId: `${id.slice(0, 20)}SCOPE0`, nodeId, status });

function loanWaiting(startedBy: string): DemoCase {
  const id = INSTANCE_ID;
  return {
    instance: {
      id, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: 'APP-1042', status: 'RUNNING', variables: {},
      tokens: [scope(id, 'loan-origination'), flowToken(id, '01', 'assess-documents', 'WAITING_USER')],
      tasks: [{ id: REVIEW_TASK_ID, nodeId: 'assess-documents', name: 'Assess documents', assignee: 'role:COMPLIANCE_REVIEWER', status: 'OPEN', lockVersion: 0, claimedBy: null }],
      lockVersion: 4, startedBy, startedAt: '2026-11-20T02:58:00Z', endedAt: null,
    },
    activities: {
      instanceId: id, startedAt: '2026-11-20T02:58:00Z', endedAt: null, hasMore: false,
      nodes: [
        node({ nodeId: 'received', state: 'COMPLETED', enteredAt: '2026-11-20T02:58:00Z', completedAt: '2026-11-20T02:58:00Z', durationMs: 0, actor: startedBy }),
        node({ nodeId: 'capture', state: 'COMPLETED', enteredAt: '2026-11-20T02:58:00Z', completedAt: '2026-11-20T03:01:10Z', durationMs: 190_000, actor: startedBy }),
        node({ nodeId: 'assess-documents', state: 'ACTIVE', enteredAt: '2026-11-20T03:01:10Z', attempts: 2, actor: 'engine:loans-7f9c' }),
      ],
      entries: [
        entry('INSTANCE_STARTED', '2026-11-20T02:58:00Z', null, startedBy),
        entry('NODE_ENTERED', '2026-11-20T02:58:00Z', 'received'),
        entry('NODE_COMPLETED', '2026-11-20T02:58:00Z', 'received'),
        entry('NODE_ENTERED', '2026-11-20T02:58:00Z', 'capture'),
        entry('TASK_CLAIMED', '2026-11-20T02:58:40Z', 'capture', startedBy),
        entry('TASK_COMPLETED', '2026-11-20T03:01:10Z', 'capture', startedBy),
        entry('NODE_COMPLETED', '2026-11-20T03:01:10Z', 'capture', startedBy),
        entry('NODE_ENTERED', '2026-11-20T03:01:10Z', 'assess-documents'),
        entry('ASYNC_STEP_SCHEDULED', '2026-11-20T03:01:10Z', 'assess-documents'),
        entry('ASYNC_ATTEMPT_FAILED', '2026-11-20T03:01:40Z', 'assess-documents', 'engine:loans-7f9c'),
        entry('ASYNC_STEP_SCHEDULED', '2026-11-20T03:01:45Z', 'assess-documents'),
        entry('ASYNC_RESULT_REFUSED', '2026-11-20T03:02:00Z', 'assess-documents', 'engine:loans-7f9c'),
        entry('STEP_ROUTED_TO_REVIEW', '2026-11-20T03:02:00Z', 'assess-documents', 'engine:loans-7f9c'),
      ],
    },
  };
}

function loanIncident(): DemoCase {
  const id = INCIDENT_INSTANCE_ID;
  return {
    instance: {
      id, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: 'APP-1045', status: 'RUNNING', variables: {},
      tokens: [scope(id, 'loan-origination'), flowToken(id, '01', 'assess-documents', 'WAITING_ASYNC')],
      tasks: [], lockVersion: 6, startedBy: 'minh', startedAt: '2026-11-20T01:40:00Z', endedAt: null,
    },
    activities: {
      instanceId: id, startedAt: '2026-11-20T01:40:00Z', endedAt: null, hasMore: false,
      nodes: [
        node({ nodeId: 'received', state: 'COMPLETED', enteredAt: '2026-11-20T01:40:00Z', completedAt: '2026-11-20T01:40:00Z', durationMs: 0, actor: 'minh' }),
        node({ nodeId: 'capture', state: 'COMPLETED', enteredAt: '2026-11-20T01:40:00Z', completedAt: '2026-11-20T01:52:30Z', durationMs: 750_000, actor: 'minh' }),
        node({ nodeId: 'assess-documents', state: 'INCIDENT', enteredAt: '2026-11-20T01:52:30Z', attempts: 3, actor: 'engine:loans-7f9c', incident: { code: 'AGENT_TIMEOUT', openedAt: '2026-11-20T01:54:10Z' } }),
      ],
      entries: [
        entry('INSTANCE_STARTED', '2026-11-20T01:40:00Z', null, 'minh'),
        entry('NODE_ENTERED', '2026-11-20T01:52:30Z', 'assess-documents'),
        entry('ASYNC_ATTEMPT_FAILED', '2026-11-20T01:53:00Z', 'assess-documents', 'engine:loans-7f9c'),
        entry('ASYNC_ATTEMPT_FAILED', '2026-11-20T01:53:35Z', 'assess-documents', 'engine:loans-7f9c'),
        entry('ASYNC_ATTEMPT_FAILED', '2026-11-20T01:54:10Z', 'assess-documents', 'engine:loans-7f9c'),
        entry('INCIDENT_RAISED', '2026-11-20T01:54:10Z', 'assess-documents', 'engine:loans-7f9c'),
      ],
    },
  };
}

function loanBooked(): DemoCase {
  const id = BOOKED_INSTANCE_ID;
  const steps: Step[] = [
    { nodeId: 'received', state: 'COMPLETED', enteredAt: '2026-11-18T08:00:00Z', completedAt: '2026-11-18T08:00:00Z', durationMs: 0, actor: 'lan' },
    { nodeId: 'capture', state: 'COMPLETED', enteredAt: '2026-11-18T08:00:00Z', completedAt: '2026-11-18T08:06:00Z', durationMs: 360_000, actor: 'lan' },
    { nodeId: 'assess-documents', state: 'COMPLETED', enteredAt: '2026-11-18T08:06:00Z', completedAt: '2026-11-18T08:06:20Z', durationMs: 20_000, actor: 'engine:loans-7f9c' },
    { nodeId: 'credit-decision', state: 'COMPLETED', enteredAt: '2026-11-18T08:06:20Z', completedAt: '2026-11-18T08:06:20Z', durationMs: 0 },
    { nodeId: 'approved', state: 'COMPLETED', enteredAt: '2026-11-18T08:06:20Z', completedAt: '2026-11-18T08:06:20Z', durationMs: 0 },
    { nodeId: 'book-loan', state: 'COMPLETED', enteredAt: '2026-11-18T08:06:20Z', completedAt: '2026-11-18T08:06:21Z', durationMs: 1_000, actor: 'svc-loans' },
    { nodeId: 'compute-offer', state: 'COMPLETED', enteredAt: '2026-11-18T08:06:21Z', completedAt: '2026-11-18T08:06:21Z', durationMs: 0 },
    { nodeId: 'send-offer', state: 'COMPLETED', enteredAt: '2026-11-18T08:06:21Z', completedAt: '2026-11-18T08:06:22Z', durationMs: 1_000, actor: 'svc-loans' },
    { nodeId: 'booked', state: 'COMPLETED', enteredAt: '2026-11-18T08:06:22Z', completedAt: '2026-11-18T08:06:22Z', durationMs: 0 },
  ];
  return {
    instance: {
      id, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: 'APP-1038', status: 'COMPLETED', variables: {},
      tokens: [], tasks: [], lockVersion: 9, startedBy: 'lan', startedAt: '2026-11-18T08:00:00Z', endedAt: '2026-11-18T08:06:22Z',
    },
    activities: {
      instanceId: id, startedAt: '2026-11-18T08:00:00Z', endedAt: '2026-11-18T08:06:22Z', hasMore: false, nodes: steps.map(node),
      entries: [
        entry('INSTANCE_STARTED', '2026-11-18T08:00:00Z', null, 'lan'),
        entry('DECISION_EVALUATED', '2026-11-18T08:06:20Z', 'credit-decision'),
        entry('NODE_COMPLETED', '2026-11-18T08:06:21Z', 'book-loan', 'svc-loans'),
        entry('NODE_COMPLETED', '2026-11-18T08:06:21Z', 'compute-offer', null, 'offerTerms'),
        entry('INSTANCE_ENDED', '2026-11-18T08:06:22Z'),
      ],
    },
  };
}

function kycWaiting(): DemoCase {
  const id = KYC_INSTANCE_ID;
  return {
    instance: {
      id, definitionKey: 'kyc-onboarding', definitionVersion: 2, businessKey: 'KYC-2207', status: 'RUNNING', variables: {},
      tokens: [scope(id, 'kyc-onboarding'), flowToken(id, '01', 'approve', 'WAITING_USER')],
      tasks: [{ id: '01JD4T7APPR0VE00000000KY07', nodeId: 'approve', name: 'Approve high-risk customer', assignee: 'role:CREDIT_OFFICER', status: 'OPEN', lockVersion: 0, claimedBy: null }],
      lockVersion: 7, startedBy: 'svc-kyc', startedAt: '2026-11-20T01:00:00Z', endedAt: null,
    },
    activities: {
      instanceId: id, startedAt: '2026-11-20T01:00:00Z', endedAt: null, hasMore: false,
      nodes: [
        node({ nodeId: 'start', state: 'COMPLETED', enteredAt: '2026-11-20T01:00:00Z', completedAt: '2026-11-20T01:00:00Z', durationMs: 0, actor: 'svc-kyc' }),
        node({ nodeId: 'split', state: 'COMPLETED', enteredAt: '2026-11-20T01:00:00Z', completedAt: '2026-11-20T01:00:00Z', durationMs: 0 }),
        node({ nodeId: 'screen-sanctions', state: 'COMPLETED', enteredAt: '2026-11-20T01:00:00Z', completedAt: '2026-11-20T01:00:04Z', durationMs: 4_000, actor: 'svc-kyc' }),
        node({ nodeId: 'verify-docs', state: 'COMPLETED', enteredAt: '2026-11-20T01:00:00Z', completedAt: '2026-11-20T01:03:10Z', durationMs: 190_000, attempts: 2, actor: 'svc-kyc' }),
        node({ nodeId: 'join', state: 'COMPLETED', enteredAt: '2026-11-20T01:03:10Z', completedAt: '2026-11-20T01:03:10Z', durationMs: 0 }),
        node({ nodeId: 'risk-rating', state: 'COMPLETED', enteredAt: '2026-11-20T01:03:10Z', completedAt: '2026-11-20T01:03:10Z', durationMs: 0 }),
        node({ nodeId: 'high-risk', state: 'COMPLETED', enteredAt: '2026-11-20T01:03:10Z', completedAt: '2026-11-20T01:03:10Z', durationMs: 0 }),
        node({ nodeId: 'approve', state: 'ACTIVE', enteredAt: '2026-11-20T01:03:10Z' }),
      ],
      entries: [
        entry('INSTANCE_STARTED', '2026-11-20T01:00:00Z', null, 'svc-kyc'),
        entry('NODE_ENTERED', '2026-11-20T01:00:00Z', 'verify-docs'),
        entry('ASYNC_ATTEMPT_FAILED', '2026-11-20T01:01:30Z', 'verify-docs', 'svc-kyc'),
        entry('NODE_COMPLETED', '2026-11-20T01:03:10Z', 'verify-docs', 'svc-kyc'),
        entry('DECISION_EVALUATED', '2026-11-20T01:03:10Z', 'risk-rating'),
        entry('NODE_ENTERED', '2026-11-20T01:03:10Z', 'approve'),
      ],
    },
  };
}

const UNDO_STEPS = [
  { compensates: 'book-loan', handler: 'reverseBooking' },
  { compensates: 'credit-decision', handler: 'releaseCreditLine' },
] as const;

/** A loan whose offer could not be sent: the booking and the credit reservation are undone, newest first; the second after a retry. */
function loanUndone(): DemoCase {
  const id = UNDONE_INSTANCE_ID;
  return {
    instance: {
      id, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: 'APP-1047', status: 'FAILED', variables: {},
      tokens: [], tasks: [], lockVersion: 11, startedBy: 'lan', startedAt: '2026-11-19T09:00:00Z', endedAt: '2026-11-19T09:04:12Z',
    },
    activities: {
      instanceId: id, startedAt: '2026-11-19T09:00:00Z', endedAt: '2026-11-19T09:04:12Z', hasMore: false,
      nodes: [
        node({ nodeId: 'credit-decision', state: 'COMPLETED', enteredAt: '2026-11-19T09:02:10Z', completedAt: '2026-11-19T09:02:10Z', durationMs: 0 }),
        node({ nodeId: 'book-loan', state: 'COMPLETED', enteredAt: '2026-11-19T09:02:10Z', completedAt: '2026-11-19T09:02:12Z', durationMs: 2_000, actor: 'svc-loans' }),
        node({ nodeId: 'send-offer', state: 'ACTIVE', enteredAt: '2026-11-19T09:02:12Z', attempts: 3, actor: 'svc-loans' }),
      ],
      entries: [
        entry('INSTANCE_STARTED', '2026-11-19T09:00:00Z', null, 'lan'),
        entry('NODE_COMPLETED', '2026-11-19T09:02:12Z', 'book-loan', 'svc-loans'),
        entry('ASYNC_ATTEMPT_FAILED', '2026-11-19T09:03:00Z', 'send-offer', 'svc-loans'),
        undo('COMPENSATION_STARTED', '2026-11-19T09:03:01Z', { cause: 'MAX_ATTEMPTS_REACHED' }),
        undo('COMPENSATION_STEP_SCHEDULED', '2026-11-19T09:03:02Z', { ...UNDO_STEPS[0], attempt: 1 }),
        undo('COMPENSATION_STEP_COMPLETED', '2026-11-19T09:03:04Z', { ...UNDO_STEPS[0], attempt: 1 }),
        undo('COMPENSATION_STEP_SCHEDULED', '2026-11-19T09:03:05Z', { ...UNDO_STEPS[1], attempt: 1 }),
        undo('COMPENSATION_STEP_FAILED', '2026-11-19T09:03:35Z', { ...UNDO_STEPS[1], attempt: 1, errorCode: 'ADAPTER_TIMEOUT' }),
        undo('COMPENSATION_STEP_SCHEDULED', '2026-11-19T09:03:50Z', { ...UNDO_STEPS[1], attempt: 2 }),
        undo('COMPENSATION_STEP_COMPLETED', '2026-11-19T09:03:52Z', { ...UNDO_STEPS[1], attempt: 2 }),
        undo('COMPENSATED', '2026-11-19T09:03:52Z'),
        entry('INSTANCE_ENDED', '2026-11-19T09:04:12Z'),
      ],
      compensations: [
        { scopeTokenId: 'DISBURSE-SCOPE', scope: 'disburse', state: 'UNDONE', startedAt: '2026-11-19T09:03:01Z', endedAt: '2026-11-19T09:03:52Z', cause: 'MAX_ATTEMPTS_REACHED', failingStep: 'send-offer', stepsUndone: 2, blockedStep: null, incidentId: null, incidentCode: null },
      ],
    },
  };
}

/** The same failure where the second undo step failed on every attempt: the scope is not fully undone and its incident is open. */
function loanUndoStuck(): DemoCase {
  const id = STUCK_UNDO_INSTANCE_ID;
  return {
    instance: {
      id, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: 'APP-1049', status: 'COMPENSATING', variables: {},
      tokens: [scope(id, 'loan-origination')], tasks: [], lockVersion: 13, startedBy: 'minh', startedAt: '2026-11-19T10:00:00Z', endedAt: null,
    },
    activities: {
      instanceId: id, startedAt: '2026-11-19T10:00:00Z', endedAt: null, hasMore: false,
      nodes: [
        node({ nodeId: 'credit-decision', state: 'INCIDENT', enteredAt: '2026-11-19T10:02:10Z', attempts: 3, actor: 'engine:loans-7f9c', incident: { code: 'COMPENSATION_FAILED', openedAt: '2026-11-19T10:05:00Z' } }),
        node({ nodeId: 'book-loan', state: 'COMPLETED', enteredAt: '2026-11-19T10:02:10Z', completedAt: '2026-11-19T10:02:12Z', durationMs: 2_000, actor: 'svc-loans' }),
        node({ nodeId: 'send-offer', state: 'ACTIVE', enteredAt: '2026-11-19T10:02:12Z', attempts: 3, actor: 'svc-loans' }),
      ],
      entries: [
        entry('INSTANCE_STARTED', '2026-11-19T10:00:00Z', null, 'minh'),
        entry('NODE_COMPLETED', '2026-11-19T10:02:12Z', 'book-loan', 'svc-loans'),
        entry('ASYNC_ATTEMPT_FAILED', '2026-11-19T10:03:00Z', 'send-offer', 'svc-loans'),
        undo('COMPENSATION_STARTED', '2026-11-19T10:03:01Z', { cause: 'MAX_ATTEMPTS_REACHED' }),
        undo('COMPENSATION_STEP_SCHEDULED', '2026-11-19T10:03:02Z', { ...UNDO_STEPS[0], attempt: 1 }),
        undo('COMPENSATION_STEP_COMPLETED', '2026-11-19T10:03:04Z', { ...UNDO_STEPS[0], attempt: 1 }),
        undo('COMPENSATION_STEP_SCHEDULED', '2026-11-19T10:03:05Z', { ...UNDO_STEPS[1], attempt: 1 }),
        undo('COMPENSATION_STEP_FAILED', '2026-11-19T10:03:35Z', { ...UNDO_STEPS[1], attempt: 1, errorCode: 'ADAPTER_TIMEOUT' }),
        undo('COMPENSATION_STEP_SCHEDULED', '2026-11-19T10:04:05Z', { ...UNDO_STEPS[1], attempt: 2 }),
        undo('COMPENSATION_STEP_FAILED', '2026-11-19T10:04:35Z', { ...UNDO_STEPS[1], attempt: 2, errorCode: 'ADAPTER_TIMEOUT' }),
        undo('COMPENSATION_STEP_SCHEDULED', '2026-11-19T10:04:50Z', { ...UNDO_STEPS[1], attempt: 3 }),
        undo('COMPENSATION_STEP_FAILED', '2026-11-19T10:05:00Z', { ...UNDO_STEPS[1], attempt: 3, errorCode: 'ADAPTER_TIMEOUT' }),
        entry('INCIDENT_RAISED', '2026-11-19T10:05:00Z', 'credit-decision', 'engine:loans-7f9c'),
      ],
      compensations: [
        { scopeTokenId: 'DISBURSE-SCOPE', scope: 'disburse', state: 'NOT_FULLY_UNDONE', startedAt: '2026-11-19T10:03:01Z', endedAt: null, cause: 'MAX_ATTEMPTS_REACHED', failingStep: 'send-offer', stepsUndone: 1, blockedStep: 'credit-decision', incidentId: STUCK_UNDO_INCIDENT_ID, incidentCode: 'COMPENSATION_FAILED' },
      ],
    },
  };
}

/** Every demo case, newest first as listInstances orders them. Task names follow the tenant's language. */
export function demoCases(startedBy: string, taskNames: { review: string; approve: string }): DemoCase[] {
  entrySeq = 0;
  const cases = [loanWaiting(startedBy), loanIncident(), kycWaiting(), loanBooked(), loanUndone(), loanUndoStuck()];
  for (const item of cases) {
    const tasks = item.instance['tasks'] as { nodeId: string; name: string }[];
    for (const task of tasks) task.name = task.nodeId === 'approve' ? taskNames.approve : taskNames.review;
  }
  return cases.sort((a, b) => b.instance.startedAt.localeCompare(a.instance.startedAt));
}
