import { describe, expect, it } from 'vitest';
import type { InstanceActivities, ProcessInstance } from '../../../api/processes/schemas';
import { assigneeWords, scopeState, undoEntryType, wordsOf, durationParts, entryKey, firstStep, incidentKey, nodeMarks } from './instanceView';

const instance: ProcessInstance = {
  id: 'I7', definitionKey: 'kyc-onboarding', definitionVersion: 2, businessKey: 'KYC-2207', status: 'RUNNING', lockVersion: 1, startedBy: 'svc-kyc', startedAt: '2026-11-20T01:00:00Z',
  tokens: [
    { id: 's', kind: 'SCOPE', scopeTokenId: null, nodeId: 'kyc-onboarding', status: 'WAITING_SCOPE' },
    { id: 'a', kind: 'FLOW', scopeTokenId: 's', nodeId: 'approve', status: 'WAITING_USER' },
    { id: 'b', kind: 'FLOW', scopeTokenId: 's', nodeId: 'assess', status: 'WAITING_ASYNC' },
    { id: 'c', kind: 'FLOW', scopeTokenId: 's', nodeId: 'verify', status: 'COMPLETED' },
  ],
};
const activities: InstanceActivities = {
  instanceId: 'I7', startedAt: '2026-11-20T01:00:00Z', endedAt: null, hasMore: false, entries: [],
  nodes: [
    { nodeId: 'verify', state: 'COMPLETED', enteredAt: 'x', completedAt: 'y', durationMs: 190_000, attempts: 2, actor: 'svc-kyc', incident: null },
    { nodeId: 'assess', state: 'INCIDENT', enteredAt: 'x', completedAt: null, durationMs: null, attempts: 3, actor: null, incident: { code: 'AGENT_TIMEOUT', openedAt: 'z' } },
  ],
};

describe('instance view', () => {
  it('CKP_017_marks_put_an_incident_before_a_token_and_skip_scope_and_finished_tokens', () => {
    const marks = nodeMarks(instance, activities);
    expect(marks.get('assess')).toEqual({ kind: 'incident', code: 'AGENT_TIMEOUT' });
    expect(marks.get('approve')).toEqual({ kind: 'token', status: 'WAITING_USER', count: 1 });
    expect(marks.get('verify')).toEqual({ kind: 'done' });
    expect(marks.has('kyc-onboarding')).toBe(false);
    expect(firstStep(marks, ['verify', 'approve', 'assess'])).toBe('assess');
  });

  it('CKP_017_a_duration_reads_in_its_two_largest_whole_units', () => {
    expect(durationParts(190_000)).toEqual([{ unit: 'min', value: 3 }, { unit: 's', value: 10 }]);
    expect(durationParts(0)).toEqual([{ unit: 's', value: 0 }]);
    expect(durationParts(7_500_000)).toEqual([{ unit: 'h', value: 2 }, { unit: 'min', value: 5 }]);
    expect(durationParts(356_400_000)).toEqual([{ unit: 'd', value: 4 }, { unit: 'h', value: 3 }]);
  });

  it('CKP_017_codes_and_assignees_read_as_words', () => {
    expect(assigneeWords('role:CREDIT_OFFICER')).toBe('Credit officer');
    expect(assigneeWords('user:lan')).toBe('lan');
    expect(entryKey('NODE_COMPLETED')).toBe('instance.entry.NODE_COMPLETED');
    expect(entryKey('SOMETHING_NEW')).toBe('instance.entry.other');
    expect(incidentKey('NOT_KNOWN')).toBe('instance.incident.other');
  });

  it('CKP_033_an_undone_step_is_marked_unless_it_holds_an_incident', () => {
    const undone: InstanceActivities = {
      ...activities,
      entries: [
        { id: '1', type: 'COMPENSATION_STEP_COMPLETED', nodeId: null, actor: null, occurredAt: 'x', kind: 'COMPENSATION', compensates: 'verify' },
        { id: '2', type: 'COMPENSATION_STEP_COMPLETED', nodeId: null, actor: null, occurredAt: 'x', kind: 'COMPENSATION', compensates: 'assess' },
      ],
    };
    const marks = nodeMarks(instance, undone);
    expect(marks.get('verify')).toEqual({ kind: 'undone' });
    expect(marks.get('assess')?.kind).toBe('incident');
  });

  it('CKP_033_undo_entries_and_names_read_as_words', () => {
    const entry = { id: '1', type: 'COMPENSATION_STEP_SCHEDULED', nodeId: null, actor: null, occurredAt: 'x' };
    expect(undoEntryType({ ...entry, kind: 'COMPENSATION' })).toBe('COMPENSATION_STEP_SCHEDULED');
    expect(undoEntryType({ ...entry, kind: 'STEP' })).toBeUndefined();
    expect(undoEntryType({ ...entry, kind: 'COMPENSATION', type: 'COMPENSATION_SOMETHING_NEW' })).toBeUndefined();
    expect(wordsOf('closeAccount')).toBe('Close account');
    expect(wordsOf('loans.release-funds')).toBe('Release funds');
    expect(wordsOf('disburse')).toBe('Disburse');
  });

  it('CKP_049_a_state_the_cockpit_does_not_know_reads_as_in_progress_never_as_undone', () => {
    const base = { scopeTokenId: 's', scope: 'disburse', startedAt: 'x', endedAt: null, cause: null, failingStep: null, stepsUndone: 0, blockedStep: null, incidentId: null, incidentCode: null };
    expect(scopeState({ ...base, state: 'UNDONE' })).toBe('UNDONE');
    expect(scopeState({ ...base, state: 'NOT_FULLY_UNDONE' })).toBe('NOT_FULLY_UNDONE');
    expect(scopeState({ ...base, state: 'SOMETHING_NEW' })).toBe('IN_PROGRESS');
  });
});
