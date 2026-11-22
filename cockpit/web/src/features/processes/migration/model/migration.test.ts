import { describe, expect, it } from 'vitest';
import type { MigrationDryRun } from '../../../../api/processes/schemas';
import { checkIsCurrent, nextStep, openChoices, planInstructions, reasonKey, type NextStepInput } from './migration';

const NOW = Date.parse('2026-11-21T10:00:00Z');
const HASH = 'a'.repeat(64);

const dry = (over: Partial<MigrationDryRun> = {}): MigrationDryRun => ({
  migrationId: 'm', planHash: HASH, definitionKey: 'k', sourceVersion: 2, targetVersion: 3, valid: true, checkedAt: '2026-11-21T09:50:00Z', validUntil: '2026-11-21T10:50:00Z',
  approval: { mode: 'four-eyes', secondPersonRequired: true }, instances: { running: 52, movable: 50, blocked: 2 }, nodes: [], conflicts: [], movable: { count: 50, instanceIds: [], truncated: false },
  impact: { mapped: 0, cancelled: 0, inFlight: 0, unmapped: 0 }, ...over,
});

const input = (over: Partial<NextStepInput> = {}): NextStepInput => ({
  plan: { status: 'PLANNED', planHash: HASH }, execution: undefined, dryRun: undefined, checking: false, now: NOW, userId: 'lan', canManage: true, seenLock: undefined, ...over,
});

describe('CKP-057 the one next action', () => {
  it('CKP_057_check_comes_first_then_propose_only_on_a_current_valid_check', () => {
    expect(nextStep(input())).toEqual({ kind: 'check', because: 'first' });
    expect(nextStep(input({ dryRun: dry() }))).toEqual({ kind: 'propose', movable: 50 });
    expect(nextStep(input({ dryRun: dry({ validUntil: '2026-11-21T09:59:59Z' }) }))).toEqual({ kind: 'check', because: 'expired' });
    expect(nextStep(input({ dryRun: dry({ planHash: 'b'.repeat(64) }) }))).toEqual({ kind: 'check', because: 'changed' });
    expect(nextStep(input({ dryRun: dry({ valid: false }) }))).toEqual({ kind: 'cannot-propose', why: 'conflicts' });
    expect(nextStep(input({ dryRun: dry({ instances: { running: 0, movable: 0, blocked: 0 } }) }))).toEqual({ kind: 'cannot-propose', why: 'empty' });
    expect(nextStep(input({ plan: { status: 'REJECTED', planHash: HASH } }))).toEqual({ kind: 'check', because: 'rejected' });
  });

  it('CKP_057_the_proposer_is_never_offered_approve', () => {
    const execution = { status: 'PROPOSED' as const, proposedBy: 'lan', lockVersion: 1 };
    const plan = { status: 'PROPOSED' as const, planHash: HASH };
    expect(nextStep(input({ plan, execution, dryRun: dry(), userId: 'lan', seenLock: 1 }))).toEqual({ kind: 'cannot-approve', why: 'self' });
    expect(nextStep(input({ plan, execution, dryRun: dry(), userId: 'minh', seenLock: 1 }))).toEqual({ kind: 'approve', movable: 50 });
    expect(nextStep(input({ plan, execution, dryRun: undefined, userId: 'minh', seenLock: 1 }))).toEqual({ kind: 'cannot-approve', why: 'checking' });
    expect(nextStep(input({ plan, execution: { ...execution, lockVersion: 2 }, dryRun: dry(), userId: 'minh', seenLock: 1 }))).toEqual({ kind: 'cannot-approve', why: 'changed' });
    expect(nextStep(input({ plan, execution, dryRun: dry({ valid: false }), userId: 'minh', seenLock: 1 }))).toEqual({ kind: 'cannot-approve', why: 'conflicts' });
    expect(nextStep(input({ plan, execution, userId: 'minh', canManage: false }))).toEqual({ kind: 'read-only' });
  });

  it('CKP_058_a_run_in_progress_or_finished_offers_no_action', () => {
    const execution = (status: 'RUNNING' | 'COMPLETED_WITH_FAILURES') => ({ status, proposedBy: 'lan', lockVersion: 2 });
    expect(nextStep(input({ execution: execution('RUNNING') }))).toEqual({ kind: 'watch' });
    expect(nextStep(input({ execution: execution('COMPLETED_WITH_FAILURES') }))).toEqual({ kind: 'done' });
  });

  it('CKP_057_a_check_is_good_only_for_the_same_plan_until_it_expires', () => {
    expect(checkIsCurrent(dry(), HASH, NOW)).toBe(true);
    expect(checkIsCurrent(dry(), 'c'.repeat(64), NOW)).toBe(false);
    expect(checkIsCurrent(undefined, HASH, NOW)).toBe(false);
  });
});

describe('CKP-027 the instructions of a plan', () => {
  it('CKP_027_choices_become_mappings_and_cancellations_with_their_reason', () => {
    const choices = [
      { step: 'approve', choice: 'move:senior-approval', reason: '' },
      { step: 'cooling', choice: 'stop:open-account', reason: '  The wait was retired ' },
      { step: 'other', choice: '', reason: '' },
    ];
    expect(planInstructions(choices)).toEqual({
      mappings: [{ from: 'approve', to: 'senior-approval' }],
      cancellations: [{ step: 'cooling', moveTo: 'open-account', reason: 'The wait was retired' }],
    });
    expect(openChoices(choices)).toBe(1);
    expect(openChoices([{ step: 'x', choice: 'stop:y', reason: ' ' }])).toBe(1);
  });

  it('CKP_058_a_reason_code_the_screen_does_not_know_reads_as_a_plain_sentence', () => {
    expect(reasonKey('LOCK_CONFLICT')).toBe('migration.why.LOCK_CONFLICT');
    expect(reasonKey('SOMETHING_NEW')).toBe('migration.why.other');
  });
});
