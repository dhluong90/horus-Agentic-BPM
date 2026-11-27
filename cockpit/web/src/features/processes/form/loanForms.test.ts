import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { fieldsOf } from './model';

/** The two forms of the loan showcase (#623) are read as the host serves them: schema and hints from the example's files. */
const root = resolve(__dirname, '../../../../../../examples/rule-and-process/src/main/resources');
const load = (name: string) => JSON.parse(readFileSync(resolve(root, `forms/${name}.form.json`), 'utf8')) as { schema: Record<string, unknown>; hints: Record<string, unknown> };
const bpmn = readFileSync(resolve(root, 'processes/loan-origination.bpmn'), 'utf8');

describe('#623 loan showcase forms', () => {
  it('the proposal form draws four labelled fields in English and Vietnamese', () => {
    const form = { ...load('loan-proposal'), data: {} };
    expect(fieldsOf(form, 'en').map((f) => f.label)).toEqual(['Proposed amount (VND)', 'Term in months', 'Your recommendation', 'Reason for the approver']);
    expect(fieldsOf(form, 'vi')[0]?.label).toBe('Số tiền đề xuất (VND)');
    expect(fieldsOf(form, 'en').filter((f) => f.required).map((f) => f.name)).toEqual(['amountMinor', 'recommendation']);
  });

  it('the approval form asks for a decision the process gateways read', () => {
    const form = { ...load('loan-approval'), data: {} };
    const decision = fieldsOf(form, 'en')[0];
    expect(decision).toMatchObject({ name: 'decision', required: true, kind: { kind: 'choice', options: ['APPROVED', 'REJECTED'], radio: true } });
    expect(bpmn).toContain("approval.decision == 'APPROVED'");
  });

  it('the proposal and both approval tasks name their form, and both approvals stay four-eyes', () => {
    expect(bpmn).toMatch(/id="prepare-proposal"[^>]*horus:form="loan-proposal"/);
    for (const id of ['approve-manager', 'approve-committee']) {
      expect(bpmn).toMatch(new RegExp(`id="${id}"[^>]*horus:fourEyes="true"[^>]*horus:form="loan-approval"`));
    }
  });
});
