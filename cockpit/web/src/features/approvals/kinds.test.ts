import { describe, expect, it } from 'vitest';
import { fromProcess, itemKey, reviewRoute } from './queue';

const HASH = 'ab'.repeat(32);
const base = { requestedBy: 'lan', requestedAt: '2027-03-01T02:00:00Z', lockVersion: 1, allowedActions: ['approve', 'reject'], deniedActions: {} };

/** #473, PD-139: each kind of proposal opens its own review and carries the hash the reviewer must send back. */
describe('Pending proposals of the Process Engine (#473)', () => {
  it('CKP_091_a_migration_plan_carries_its_plan_hash_and_opens_the_plan', () => {
    const item = fromProcess({ ...base, kind: 'MIGRATION_PLAN', id: 'MIG1', key: 'loan-origination', version: 2, sourceVersion: 1, definitionHash: null, contentHash: HASH, reason: 'Rename a step' }, 'minh');
    expect(item).toMatchObject({ type: 'migration', hash: HASH, sourceVersion: 1, reason: 'Rename a step', canApprove: true });
    expect(reviewRoute(item)).toEqual({ name: 'migration', migrationId: 'MIG1' });
  });

  it('CKP_091_a_calendar_change_carries_its_change_hash_and_the_proposer_may_not_approve', () => {
    const item = fromProcess({ ...base, kind: 'CALENDAR_CHANGE', id: 'CAL1', key: 'vn-banking-hours', version: 4, definitionHash: null, contentHash: HASH, allowedActions: ['reject'], deniedActions: { approve: 'SELF_APPROVAL_FORBIDDEN' } }, 'lan');
    expect(item).toMatchObject({ type: 'calendar', hash: HASH, canApprove: false, approveDenied: 'SELF_APPROVAL_FORBIDDEN', mine: true });
    expect(reviewRoute(item)).toEqual({ name: 'calendar-change', changeId: 'CAL1' });
  });

  it('CKP_091_a_definition_version_is_as_before_and_two_kinds_of_one_key_never_share_an_identity', () => {
    const definition = fromProcess({ ...base, key: 'loan-origination', version: 2, definitionHash: HASH }, 'minh');
    expect(definition).toMatchObject({ type: 'definition', hash: HASH });
    expect(reviewRoute(definition)).toEqual({ name: 'process', key: 'loan-origination', version: 2, lens: 'changes' });
    const plan = fromProcess({ ...base, kind: 'MIGRATION_PLAN', id: 'MIG1', key: 'loan-origination', version: 2, sourceVersion: 1, definitionHash: null, contentHash: HASH }, 'minh');
    expect(itemKey(plan)).not.toBe(itemKey(definition));
  });
});
