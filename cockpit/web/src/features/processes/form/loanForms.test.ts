import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { build, fieldsOf, initialDrafts } from './model';

/** The two forms of the loan showcase (#623) are read as the host serves them: schema and hints from the example's files. */
const root = resolve(__dirname, '../../../../../../examples/rule-and-process/src/main/resources');
const load = (name: string) => JSON.parse(readFileSync(resolve(root, `forms/${name}.form.json`), 'utf8')) as { schema: Record<string, unknown>; hints: Record<string, unknown> };
const bpmn = readFileSync(resolve(root, 'processes/loan-origination.bpmn'), 'utf8');

describe('#623 loan showcase forms', () => {
  it('the proposal form draws four labelled fields in English and Vietnamese', () => {
    const form = { ...load('loan-proposal'), data: {} };
    expect(fieldsOf(form, 'en').map((f) => f.label)).toEqual(['Proposed amount (VND)', 'Term (months)', 'Your recommendation', 'Reason for the approver (required when declining)']);
    expect(fieldsOf(form, 'vi')[0]?.label).toBe('Số tiền đề xuất (VND)');
    expect(fieldsOf(form, 'en')[0]?.kind).toMatchObject({ kind: 'number', integer: true, grouped: false });
    expect(fieldsOf(form, 'en').filter((f) => f.required).map((f) => f.name)).toEqual(['amountMinor', 'recommendation']);
  });

  it('the approval form asks for a decision the process gateways read', () => {
    const form = { ...load('loan-approval'), data: {} };
    const decision = fieldsOf(form, 'en')[0];
    expect(decision).toMatchObject({ name: 'decision', required: true, kind: { kind: 'choice', options: ['APPROVED', 'REJECTED'], radio: true } });
    expect(bpmn).toContain("approval.decision == 'APPROVED'");
    // The engine's form hints carry labels only: the codes are shown as they are submitted, and no option label or effect is drawn.
    for (const locale of ['en', 'vi'] as const) {
      const [field] = fieldsOf(form, locale);
      expect(field?.optionLabels).toEqual({});
      expect(field?.effects).toEqual({});
    }
  });

  it('a rejection needs a reason, and an approval does not', () => {
    const fields = fieldsOf({ ...load('loan-approval'), data: {} }, 'en');
    const reason = fields.find((f) => f.name === 'reason');
    expect(reason?.requiredWhen).toEqual({ field: 'decision', equals: 'REJECTED' });
    expect(build(fields, { decision: 'REJECTED' }, {}, 'en', new Set()).problems).toEqual({ reason: 'required' });
    expect(build(fields, { decision: 'REJECTED', reason: 'Income unverified' }, {}, 'en', new Set()).problems).toEqual({});
    const approved = build(fields, { decision: 'APPROVED' }, {}, 'vi', new Set());
    expect(approved.problems).toEqual({});
    expect(approved.result).toEqual({ decision: 'APPROVED' });
  });

  it('the amount is typed as plain digits (the engine has no grouping widget) and submitted as a whole number', () => {
    const fields = fieldsOf({ ...load('loan-proposal'), data: {} }, 'vi');
    expect(initialDrafts(fields, { amountMinor: 900000000 }, 'vi')['amountMinor']).toBe('900000000');
    expect(initialDrafts(fieldsOf({ ...load('loan-proposal'), data: {} }, 'en'), { amountMinor: 900000000 }, 'en')['amountMinor']).toBe('900000000');
    const built = build(fields, { amountMinor: '900000000', recommendation: 'APPROVE' }, {}, 'vi', new Set());
    expect(built.problems).toEqual({});
    expect(built.result).toEqual({ amountMinor: 900000000, recommendation: 'APPROVE' });
  });

  it('the proposal and both approval tasks name their form, and both approvals stay four-eyes', () => {
    expect(bpmn).toMatch(/id="prepare-proposal"[^>]*horus:form="loan-proposal"/);
    for (const id of ['approve-manager', 'approve-committee']) {
      expect(bpmn).toMatch(new RegExp(`id="${id}"[^>]*horus:fourEyes="true"[^>]*horus:form="loan-approval"`));
    }
  });
});
