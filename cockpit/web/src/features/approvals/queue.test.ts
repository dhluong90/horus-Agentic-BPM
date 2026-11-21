import { describe, expect, it } from 'vitest';
import { denialMessage, fromProcess, fromRule, inView, mergeQueue, reviewRoute, type ApprovalItem } from './queue';

const HASH = 'b'.repeat(64);
const rule = (ruleSet: string, requestedAt: string, extra: Partial<Parameters<typeof fromRule>[0]> = {}) =>
  fromRule({ ruleSet, version: 2, canonicalHash: HASH, lockVersion: 1, requestedBy: 'hoa', requestedAt, allowedActions: ['approve', 'reject'], deniedActions: {}, ...extra }, 'minh');
const process = (key: string, requestedAt: string) =>
  fromProcess({ key, name: 'Customer onboarding (KYC)', version: 3, definitionHash: HASH, lockVersion: 2, requestedBy: 'lan', requestedAt, allowedActions: ['approve', 'reject'], deniedActions: {} }, 'minh');
const keys = (items: readonly ApprovalItem[]) => items.map((item) => `${item.engine}:${item.key}`);

describe('approvals queue model (#31)', () => {
  it('CKP_003_both_engines_merge_oldest_request_first', () => {
    const merged = mergeQueue([
      { items: [rule('card-fee', '2026-11-18T08:30:00Z'), rule('fx-margin', '2026-11-21T01:00:00Z')], hasMore: false },
      { items: [process('kyc-onboarding', '2026-11-20T08:40:00Z')], hasMore: false },
    ]);
    expect(keys(merged.items)).toEqual(['rules:card-fee', 'processes:kyc-onboarding', 'rules:fx-margin']);
    expect(merged.more).toBe(false);
  });

  it('CKP_003_an_engine_with_more_pages_hides_nothing_newer_than_its_last_loaded_request', () => {
    // The Rule Engine has more after 19 Nov: a process request of 20 Nov must wait until that page is read.
    const merged = mergeQueue([
      { items: [rule('card-fee', '2026-11-18T08:30:00Z'), rule('fx-margin', '2026-11-19T01:00:00Z')], hasMore: true },
      { items: [process('kyc-onboarding', '2026-11-20T08:40:00Z')], hasMore: false },
    ]);
    expect(keys(merged.items)).toEqual(['rules:card-fee', 'rules:fx-margin']);
    expect(merged.more).toBe(true);
  });

  it('CKP_003_the_process_title_is_its_name_and_the_review_opens_the_existing_diff_screens', () => {
    const item = process('kyc-onboarding', '2026-11-20T08:40:00Z');
    expect(item.title).toBe('Customer onboarding (KYC)');
    expect(reviewRoute(item)).toEqual({ name: 'process', key: 'kyc-onboarding', version: 3, lens: 'changes' });
    expect(reviewRoute(rule('card-fee', '2026-11-18T08:30:00Z'))).toEqual({ name: 'rule-set', ruleSet: 'card-fee', version: 2, lens: 'changes' });
  });

  it('CKP_003_reason_codes_map_to_one_message_and_an_unknown_code_to_a_plain_one', () => {
    const self = rule('card-fee', '2026-11-18T08:30:00Z', { allowedActions: [], deniedActions: { approve: 'SELF_APPROVAL_FORBIDDEN' } });
    const unknown = rule('card-fee', '2026-11-18T08:30:00Z', { allowedActions: [], deniedActions: { approve: 'NEW_CODE' } });
    expect(self.canApprove).toBe(false);
    expect(denialMessage(self.approveDenied ?? 'UNAVAILABLE')).toBe('approvals.denied.SELF_APPROVAL_FORBIDDEN');
    expect(unknown.approveDenied).toBe('UNAVAILABLE');
    expect(rule('card-fee', '2026-11-18T08:30:00Z').approveDenied).toBeUndefined();
  });

  it('CKP_003_views_put_the_users_own_changes_apart_and_hide_others_they_may_not_approve', () => {
    const own = rule('card-fee', '2026-11-18T08:30:00Z', { requestedBy: 'minh', allowedActions: [], deniedActions: { approve: 'SELF_APPROVAL_FORBIDDEN' } });
    const authored = rule('kyc-risk', '2026-11-18T08:30:00Z', { allowedActions: [], deniedActions: { approve: 'SELF_APPROVAL_FORBIDDEN' } });
    const stuck = rule('fx-margin', '2026-11-18T08:30:00Z', { allowedActions: [], deniedActions: { approve: 'AUTHOR_UNKNOWN' } });
    const notMine = rule('atm-fee', '2026-11-18T08:30:00Z', { allowedActions: [], deniedActions: { approve: 'PERMISSION_REQUIRED' } });
    const open = rule('loan-limit', '2026-11-18T08:30:00Z');
    expect([own, authored, stuck, notMine, open].filter((item) => inView(item, 'mine')).map((item) => item.key)).toEqual(['card-fee', 'kyc-risk']);
    expect([own, authored, stuck, notMine, open].filter((item) => inView(item, 'review')).map((item) => item.key)).toEqual(['fx-margin', 'loan-limit']);
  });
});
