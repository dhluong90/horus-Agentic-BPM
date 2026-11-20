import { describe, expect, it } from 'vitest';
import { buildAnswer, changesOf, containsMasked, fingerprintHint, fourEyesBlock, isMasked, schemaVerdict, violationsByField } from './review';

describe('CKP-007 review model', () => {
  it('CKP_007_schema_verdict_follows_the_refusal_code', () => {
    expect(schemaVerdict('GUARDRAIL_VIOLATION')).toBe('passed');
    expect(schemaVerdict('GUARDRAIL_EVALUATION_ERROR')).toBe('passed');
    expect(schemaVerdict('AGENT_SCHEMA_INVALID')).toBe('failed');
    expect(schemaVerdict('SOMETHING_NEW')).toBe('unknown');
  });

  it('CKP_007_masked_values_are_recognised_and_only_a_fingerprint_hint_is_shown', () => {
    expect(isMasked('***')).toBe(true);
    expect(isMasked('hmac:k2026:9f4a1c7e2b8d')).toBe(true);
    expect(isMasked('APP-1042')).toBe(false);
    expect(fingerprintHint('hmac:k2026:9f4a1c7e2b8d')).toBe('hmac:k2026:9f4a…');
    expect(fingerprintHint('***')).toBeUndefined();
    expect(containsMasked({ a: [1, { b: '***' }] })).toBe(true);
  });

  it('CKP_007_the_override_keeps_types_and_lists_changes_from_to', () => {
    const output = { riskScore: 38, recommendation: 'APPROVE', incomeVerified: true };
    const built = buildAnswer(output, { riskScore: '72', recommendation: 'REFER', incomeVerified: 'false' });
    expect(built.problems).toEqual({});
    expect(built.result).toEqual({ riskScore: 72, recommendation: 'REFER', incomeVerified: false });
    expect(changesOf(output, built.result)).toEqual([
      { field: 'riskScore', from: 38, to: 72 },
      { field: 'recommendation', from: 'APPROVE', to: 'REFER' },
      { field: 'incomeVerified', from: true, to: false },
    ]);
  });

  it('CKP_007_a_masked_field_needs_a_new_value_and_never_sends_the_mask', () => {
    const built = buildAnswer({ riskScore: 38, summary: '***' }, {});
    expect(built.problems).toEqual({ summary: 'required' });
    expect(built.result).not.toHaveProperty('summary');
    expect(buildAnswer({ riskScore: 38 }, { riskScore: '7x' }).problems).toEqual({ riskScore: 'number' });
  });

  it('CKP_007_four_eyes_blocks_the_reporter_and_the_starter', () => {
    expect(fourEyesBlock('tuan', { reportedBy: 'tuan' }, { startedBy: 'lan' })).toBe('reported');
    expect(fourEyesBlock('lan', { reportedBy: 'engine:loans-7f9c' }, { startedBy: 'lan' })).toBe('started');
    expect(fourEyesBlock('hoa', { reportedBy: 'engine:loans-7f9c' }, { startedBy: 'lan' })).toBeUndefined();
    expect(fourEyesBlock('hoa', { reportedBy: 'engine:loans-7f9c' }, undefined)).toBeUndefined();
  });

  it('CKP_007_engine_violations_are_placed_on_their_fields', () => {
    expect(violationsByField(['$.riskScore: expected integer', 'answer too large'])).toEqual({ byField: { riskScore: 'expected integer' }, other: ['answer too large'] });
  });
});
