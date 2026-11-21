import { describe, expect, it } from 'vitest';
import { availableAreas } from '../../../app/navigation';
import { formatRoute, parseRoute } from '../../../app/route';
import { meAn, meLan, meSau, metaRulesOnly, metaWithProcesses } from '../../../mocks/fixtures';
import { handOverCommand, handOverProblems, overdueParts, parseAssignee, queryOf, readableName, refusalOf, slaOf, viewsFor } from './queue';

const problem = (code: string) => ({ kind: 'problem' as const, status: 409, code, correlationId: undefined, title: undefined, detail: undefined });

describe('CKP-046 task queue model', () => {
  it('CKP_046_each_tab_maps_one_to_one_to_a_task_query', () => {
    expect(queryOf('mine')).toEqual({ assignment: ['mine'] });
    expect(queryOf('candidate')).toEqual({ assignment: ['candidate'], status: ['OPEN'] });
    expect(queryOf('all')).toEqual({ assignment: ['any'] });
  });

  it('CKP_046_roles_and_groups_read_in_words_and_users_by_id', () => {
    expect(parseAssignee('role:credit-officer')).toEqual({ kind: 'role', name: 'credit-officer' });
    expect(parseAssignee('group:loans-operations')).toEqual({ kind: 'group', name: 'loans-operations' });
    expect(parseAssignee('user:hoa')).toEqual({ kind: 'user', id: 'hoa' });
    expect(readableName('credit-officer')).toBe('Credit officer');
    expect(readableName('COMPLIANCE_REVIEWER')).toBe('Compliance reviewer');
    expect(readableName('ROLE_OPERATIONS_SUPERVISOR')).toBe('Operations supervisor');
  });

  it('CKP_046_overdue_comes_from_the_engine_never_from_the_browser_clock', () => {
    const task = { id: 'T1', dueAt: '2000-01-01T00:00:00Z', slaState: undefined };
    // Due long ago by the browser's clock, but the engine did not list it as breached: on time.
    expect(slaOf(task, new Set())).toBe('ON_TIME');
    expect(slaOf(task, new Set(['T1']))).toBe('BREACHED');
    expect(slaOf({ ...task, dueAt: null }, new Set(['T1']))).toBe('NONE');
    expect(slaOf({ ...task, slaState: 'DUE_SOON' }, new Set(['T1']))).toBe('DUE_SOON');
    expect(overdueParts('PT1H30M')).toEqual({ days: 0, hours: 1, minutes: 30 });
    expect(overdueParts('P2DT3H')).toEqual({ days: 2, hours: 3, minutes: 0 });
    expect(overdueParts('soon')).toBeUndefined();
  });

  it('CKP_046_the_queue_route_keeps_the_tab_and_task_and_ignores_anything_else', () => {
    expect(parseRoute('#/tasks')).toEqual({ name: 'tasks' });
    expect(parseRoute('#/tasks?view=all&task=01JD5Q2CRED1TREV1EW0000T02')).toEqual({ name: 'tasks', view: 'all', taskId: '01JD5Q2CRED1TREV1EW0000T02' });
    expect(parseRoute('#/tasks?view=other&task=<script>')).toEqual({ name: 'tasks' });
    expect(formatRoute({ name: 'tasks', view: 'candidate', taskId: '01JD5Q2CRED1TREV1EW0000T02' })).toBe('#/tasks?view=candidate&task=01JD5Q2CRED1TREV1EW0000T02');
    expect(parseRoute('#/tasks/01JD5Q2CRED1TREV1EW0000T02')).toEqual({ name: 'task', taskId: '01JD5Q2CRED1TREV1EW0000T02' });
  });
});

describe('CKP-048 hand-over model', () => {
  it('CKP_048_tasks_show_only_with_a_task_permission_and_all_tasks_only_with_manage', () => {
    expect(availableAreas(metaWithProcesses, meAn)).toContain('tasks');
    expect(availableAreas(metaRulesOnly, meAn)).not.toContain('tasks');
    expect(availableAreas(metaWithProcesses, meLan)).not.toContain('tasks');
    const can = (me: typeof meAn) => (permission: string) => me.permissions.includes(permission);
    expect(viewsFor(can(meAn))).toEqual(['mine', 'candidate']);
    expect(viewsFor(can(meSau))).toEqual(['mine', 'candidate', 'all']);
  });

  it('CKP_048_an_open_task_is_reassigned_and_a_claimed_one_delegated_when_the_engine_allows', () => {
    expect(handOverCommand({ status: 'OPEN', allowedActions: ['reassign'] })).toBe('reassign');
    expect(handOverCommand({ status: 'CLAIMED', allowedActions: ['delegate'] })).toBe('delegate');
    expect(handOverCommand({ status: 'OPEN', allowedActions: ['claim'] })).toBeUndefined();
    expect(handOverProblems('', ' ')).toEqual(['assignee-missing', 'reason-missing']);
    expect(handOverProblems('hoa', 'Covering leave')).toEqual([]);
  });

  it('CKP_048_refusals_map_from_the_problem_code', () => {
    expect(refusalOf(problem('ASSIGNEE_NOT_ALLOWED'))).toBe('assignee-not-allowed');
    expect(refusalOf(problem('INVALID_STATE'))).toBe('claimed-first');
    expect(refusalOf(problem('VERSION_CONFLICT'))).toBe('changed');
    expect(refusalOf({ kind: 'unreachable' })).toBe('other');
  });
});
