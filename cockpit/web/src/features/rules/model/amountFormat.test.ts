import { describe, expect, it } from 'vitest';
import type { RuleSetDefinition } from '../../../api/rules/schemas';
import { amountProblems } from './amountFormat';
import type { EditableRow } from './table';

const definition = { inputs: [{ name: 'balance', type: 'money' }], outputs: [] } as unknown as RuleSetDefinition;
const row = (balance: string): EditableRow => ({ key: 'k', id: 'R1', when: { balance }, then: {}, groups: {}, original: undefined });
const problems = (balance: string) => amountProblems(definition, [row(balance)], () => 'cellError.amount');

describe('amount format check', () => {
  it('CKP_006_a_comparison_with_another_input_is_left_to_the_engine', () => {
    expect(problems('>= income')).toEqual([]);
  });

  it('CKP_006_a_typed_amount_that_is_not_a_number_is_a_cell_problem', () => {
    expect(problems('>= 12abc')).toHaveLength(1);
    expect(problems('>= 50000000 VND')).toEqual([]);
  });
});
