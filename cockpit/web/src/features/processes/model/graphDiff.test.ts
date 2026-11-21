import { describe, expect, it } from 'vitest';
import { graphDiff, type DefinitionVersion } from '../../../api/processes/schemas';
import { kycDiff, KYC_V2_HASH, KYC_V3_HASH } from '../../../mocks/processDemo';
import { approvalBlock, changedSteps, counts, diagramType, edgeKey, humanise, linkKind, marksFor, pathParts, reviewedHash, sideOf, type DiffState } from './graphDiff';

const diff = graphDiff.parse(kycDiff('en'));
const truncated = graphDiff.parse(kycDiff('en', true));

const pending: DefinitionVersion = {
  id: 'v3', key: 'kyc-onboarding', version: 3, status: 'PENDING_APPROVAL', name: 'Customer onboarding (KYC)', definitionHash: KYC_V3_HASH,
  nodeCount: 11, createdBy: 'lan', createdAt: '2026-11-20T08:40:00Z', effectiveFrom: '2027-05-31T17:00:00Z', lockVersion: 2, requestedBy: 'lan',
};
const ready: DiffState = { status: 'ready', diff };
const block = (overrides: Partial<Parameters<typeof approvalBlock>[0]> = {}) =>
  approvalBlock({ version: pending, state: ready, userId: 'minh', canApprove: true, seenHash: KYC_V3_HASH, ...overrides });

describe('CKP-024 reading the graph diff', () => {
  it('CKP_024_the_proposed_diagram_marks_added_and_changed_steps_and_the_one_in_effect_removed_ones', () => {
    const target = marksFor(diff, 'target');
    expect(target.nodes.get('fraud-check')).toBe('added');
    expect(target.nodes.get('approve')).toBe('changed');
    expect(target.nodes.has('cooling')).toBe(false);
    expect(target.edges.get(edgeKey('verify-docs', 'fraud-check'))).toBe('added');
    const base = marksFor(diff, 'base');
    expect(base.nodes.get('cooling')).toBe('removed');
    expect(base.nodes.get('approve')).toBe('changed');
    expect(base.edges.get(edgeKey('cooling', 'open-account'))).toBe('removed');
    expect(sideOf('removed')).toBe('base');
    expect(sideOf('added')).toBe('target');
  });

  it('CKP_024_changed_values_are_grouped_by_step_and_counted', () => {
    expect(changedSteps(diff).map((step) => [step.id, step.values.map((v) => v.path)])).toEqual([
      ['approve', ['/assignee/role']],
      ['risk-rating', ['/inputs/highRiskAmount']],
    ]);
    expect(counts(diff)).toEqual({ added: 4, changed: 2, removed: 4 });
  });

  it('CKP_024_paths_links_and_step_types_read_as_words', () => {
    expect(pathParts('/branches/0/when')).toEqual([{ kind: 'name', name: 'branches' }, { kind: 'index', index: 0 }, { kind: 'name', name: 'when' }]);
    expect(pathParts('/inputs/a~1b~0c')).toEqual([{ kind: 'name', name: 'inputs' }, { kind: 'name', name: 'a/b~c' }]);
    expect(humanise('highRiskAmount')).toBe('High risk amount');
    expect(humanise('escalate_to')).toBe('Escalate to');
    expect(linkKind('branch[1]')).toEqual({ kind: 'condition', number: 2 });
    expect(linkKind('default')).toEqual({ kind: 'default' });
    expect(linkKind('fork')).toEqual({ kind: 'plain' });
    expect(diagramType('agentTask')).toBe('serviceTask:agentTask');
    expect(diagramType('timer')).toBe('intermediateCatchEvent:timer');
    expect(diagramType('userTask')).toBe('userTask');
  });
});

describe('CKP-024 when Approve is offered', () => {
  it('CKP_024_approve_names_the_reviewed_hash_and_a_first_version_names_its_own', () => {
    expect(block()).toBeUndefined();
    expect(reviewedHash(ready, pending)).toBe(KYC_V3_HASH);
    expect(reviewedHash({ status: 'first-version' }, pending)).toBe(KYC_V3_HASH);
    expect(reviewedHash({ status: 'loading' }, pending)).toBeUndefined();
  });

  it('CKP_024_the_requester_and_the_importer_are_blocked_whatever_the_list', () => {
    expect(block({ userId: 'lan' })).toBe('self-requested');
    expect(block({ userId: 'lan', version: { ...pending, requestedBy: 'thu' } })).toBe('self-created');
    expect(block({ userId: 'lan', state: { status: 'loading' } })).toBe('self-requested');
  });

  it('CKP_024_a_cut_off_list_carries_no_approvable_hash_and_blocks_approve', () => {
    expect(truncated.approvableHash).toBeNull();
    expect(truncated.base.definitionHash).toBeUndefined();
    expect(reviewedHash({ status: 'ready', diff: truncated }, pending)).toBeUndefined();
    expect(block({ state: { status: 'ready', diff: truncated } })).toBe('truncated');
  });

  it('CKP_024_content_that_moved_on_since_it_was_first_seen_needs_a_new_look', () => {
    expect(block({ seenHash: KYC_V2_HASH })).toBe('changed');
    expect(block({ version: { ...pending, definitionHash: KYC_V2_HASH } })).toBe('changed');
  });

  it('CKP_024_no_approve_without_the_permission_or_off_the_pending_state', () => {
    expect(block({ canApprove: false })).toBe('waiting');
    expect(block({ version: { ...pending, status: 'ACTIVE' } })).toBe('waiting');
    expect(block({ state: { status: 'loading' } })).toBe('checking');
    expect(block({ state: { status: 'error' } })).toBe('unavailable');
  });
});
