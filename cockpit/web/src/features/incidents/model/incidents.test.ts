import { describe, expect, it } from 'vitest';
import type { Incident } from '../../../api/processes/schemas';
import { approveBlock, buildOutput, causeKey, failingFields, resolveMode, retryBlock, type OutputField } from './incidents';

const field = (change: Partial<OutputField>): OutputField => ({ key: 'k1', name: 'approvedLimit', kind: 'text', text: '', currency: 'VND', yes: true, ...change });

const incident = (change: Partial<Incident>): Incident => ({
  id: 'I', instanceId: 'C', nodeId: 'check-limit', tokenId: null, errorCode: 'AGENT_TIMEOUT', causeSummary: 'AGENT_TIMEOUT', retryCount: 0, status: 'OPEN',
  lockVersion: 0, retrying: false, createdAt: '2026-11-21T01:00:00Z', resolvedAt: null, resolution: null, resolvedBy: null, pendingResolution: null, allowedActions: [], ...change,
});

describe('incident rules of the cockpit (CKP-008, PD-126)', () => {
  it('CKP_008_an_amount_becomes_integer_minor_units_with_its_currency_and_never_a_float', () => {
    expect(buildOutput([field({ kind: 'amount', text: '30,000,000', currency: 'VND' })], 'en')).toEqual({ ok: true, output: { approvedLimit: { amount: 30000000, currency: 'VND' } } });
    expect(buildOutput([field({ kind: 'amount', text: '30.000.000', currency: 'vnd' })], 'vi')).toEqual({ ok: true, output: { approvedLimit: { amount: 30000000, currency: 'VND' } } });
    expect(buildOutput([field({ kind: 'amount', text: '12.50', currency: 'USD' })], 'en')).toEqual({ ok: true, output: { approvedLimit: { amount: 1250, currency: 'USD' } } });
    expect(buildOutput([field({ kind: 'amount', text: '12.5', currency: 'VND' })], 'en')).toMatchObject({ ok: false, problems: { k1: 'amount' } });
  });

  it('CKP_008_numbers_text_and_yes_or_no_keep_their_kind', () => {
    const built = buildOutput([
      field({ key: 'a', name: 'riskScore', kind: 'number', text: '72' }),
      field({ key: 'b', name: 'recommendation', kind: 'text', text: 'APPROVE' }),
      field({ key: 'c', name: 'incomeVerified', kind: 'yesno', yes: false }),
    ], 'en');
    expect(built).toEqual({ ok: true, output: { riskScore: 72, recommendation: 'APPROVE', incomeVerified: false } });
  });

  it('CKP_008_a_field_without_a_name_a_bad_name_a_repeat_or_a_value_is_named_before_anything_is_sent', () => {
    const built = buildOutput([
      field({ key: 'a', name: '' }),
      field({ key: 'b', name: 'approved limit', text: 'x' }),
      field({ key: 'c', name: 'note', text: 'x' }),
      field({ key: 'd', name: 'note', text: 'y' }),
      field({ key: 'e', name: 'score', kind: 'number', text: 'lots' }),
      field({ key: 'f', name: 'limit', kind: 'amount', text: '5', currency: 'V' }),
      field({ key: 'g', name: 'memo', text: ' ' }),
    ], 'en');
    expect(built).toEqual({ ok: false, empty: false, problems: { a: 'name-missing', b: 'name-format', d: 'name-duplicate', e: 'number', f: 'currency', g: 'value-missing' } });
    expect(buildOutput([], 'en')).toEqual({ ok: false, empty: true, problems: {} });
  });

  it('CKP_008_retry_unavailable_says_why_in_words', () => {
    expect(retryBlock(incident({ allowedActions: ['retry', 'resolve'] }), true)).toBeUndefined();
    expect(retryBlock(incident({}), false)).toBe('incidents.why.permission');
    expect(retryBlock(incident({ retrying: true }), true)).toBe('incidents.why.retrying');
    expect(retryBlock(incident({ errorCode: 'COMPENSATION_MANUAL_REQUIRED', allowedActions: ['resolve'] }), true)).toBe('incidents.why.manual');
  });

  it('CKP_080_the_proposer_is_told_a_colleague_approves', () => {
    const pending = incident({ status: 'RESOLUTION_PENDING', pendingResolution: { proposedBy: 'linh', proposedAt: '2026-11-21T01:45:00Z', reason: 'memo', output: {}, approvalMode: 'four-eyes' } });
    expect(approveBlock(pending, 'linh', true)).toBe('incidents.why.self');
    expect(approveBlock({ ...pending, allowedActions: ['approve-resolution', 'reject-resolution'] }, 'tuan', true)).toBeUndefined();
    expect(approveBlock(pending, 'hoa', false)).toBe('incidents.why.permission');
  });

  it('CKP_080_the_resolve_mode_follows_the_host_setting_and_is_four_eyes_unless_single', () => {
    expect(resolveMode(undefined)).toBe('four-eyes');
    expect(resolveMode({ 'incidents.resolve-approval': 'four-eyes' })).toBe('four-eyes');
    expect(resolveMode({ 'incidents.resolve-approval': 'single' })).toBe('single');
    expect(resolveMode({ 'incidents.resolve-approval': 'anything' })).toBe('four-eyes');
  });

  it('CKP_008_causes_read_in_words_and_failing_paths_name_fields_only', () => {
    expect(causeKey('AGENT_TIMEOUT')).toBe('incidents.cause.AGENT_TIMEOUT');
    expect(causeKey('SOMETHING_NEW')).toBe('incidents.cause.other');
    expect(failingFields({ kind: 'problem', status: 422, code: 'OUTPUT_INVALID', correlationId: undefined, title: undefined, detail: undefined, members: { paths: ['/approvedLimit', '$.riskScore', '/a/b'] } })).toEqual(['approvedLimit', 'riskScore', 'a']);
    expect(failingFields({ kind: 'problem', status: 409, code: 'VERSION_CONFLICT', correlationId: undefined, title: undefined, detail: undefined })).toEqual([]);
  });
});
