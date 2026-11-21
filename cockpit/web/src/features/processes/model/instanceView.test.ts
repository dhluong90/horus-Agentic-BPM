import { describe, expect, it } from 'vitest';
import type { InstanceActivities, ProcessInstance } from '../../../api/processes/schemas';
import { assigneeWords, durationParts, entryKey, firstStep, incidentKey, nodeMarks } from './instanceView';

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
});
