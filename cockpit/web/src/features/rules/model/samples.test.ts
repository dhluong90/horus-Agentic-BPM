import { describe, expect, it } from 'vitest';
import type { InputClause, RuleSetDefinition } from '../../../api/rules/schemas';
import { isBlank, readPastedRows, referenceOf } from './samples';

const definition = {
  schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first',
  inputs: [
    { name: 'tier', type: 'string', allowed: ['CLASSIC', 'GOLD', 'PLATINUM'] },
    { name: 'balance', type: 'money' },
    { name: 'txCount', type: 'integer' },
    { name: 'nationalId', type: 'string', mask: true, description: 'National ID' },
  ],
  outputs: [{ name: 'fee', type: 'money' }],
  rules: [],
} as unknown as RuleSetDefinition;

const labelOf = (input: InputClause) => ({ tier: 'Tier', balance: 'Balance', txCount: 'Tx count', nationalId: 'National ID' })[input.name] ?? input.name;
const read = (text: string, locale: 'en' | 'vi' = 'en') => readPastedRows(text, definition, { balance: 'VND' }, locale, labelOf, 'Name');

describe('pasted sample rows', () => {
  it('CKP_004_spreadsheet_rows_with_a_name_column_become_samples', () => {
    const pasted = read('VIP customer\tGold\t55000000 VND\t3\t079123456789\nNew card\tclassic\t\t25\t');
    expect(pasted).toEqual({ ok: true, rows: [
      { label: 'VIP customer', values: { tier: 'GOLD', balance: '55,000,000', txCount: '3', nationalId: '079123456789' } },
      { label: 'New card', values: { tier: 'CLASSIC', balance: '', txCount: '25', nationalId: '' } },
    ] });
  });

  it('CKP_004_a_header_line_maps_columns_in_any_order', () => {
    const pasted = read('Balance;Tier\n30000000;GOLD');
    expect(pasted).toEqual({ ok: true, rows: [{ label: '', values: { balance: '30,000,000', tier: 'GOLD' } }] });
  });

  it('CKP_004_csv_quotes_keep_a_grouped_amount_in_one_cell_in_vietnamese', () => {
    expect(read('"Gold",30.000.000 VND,1,', 'vi')).toEqual({ ok: true, rows: [{ label: '', values: { tier: 'GOLD', balance: '30.000.000', txCount: '1', nationalId: '' } }] });
  });

  it('CKP_004_a_bad_cell_names_its_line_and_column', () => {
    expect(read('Silver\t1\t1\t')).toEqual({ ok: false, problem: { kind: 'choice', line: 1, input: 'Tier', choices: ['Classic', 'Gold', 'Platinum'] } });
    expect(read('Gold\t5E+07\t1\t')).toEqual({ ok: false, problem: { kind: 'amount', line: 1, input: 'Balance' } });
    expect(read('Gold\t10 USD\t1\t')).toEqual({ ok: false, problem: { kind: 'currency', line: 1, input: 'Balance', currency: 'VND' } });
    expect(read('Gold\t1\n')).toEqual({ ok: false, problem: { kind: 'columns', line: 1, expected: 4 } });
    expect(read('  \n')).toEqual({ ok: false, problem: { kind: 'empty' } });
  });

  it('CKP_004_references_and_blank_rows', () => {
    expect(referenceOf(0)).toBe('S1');
    expect(isBlank({ key: 1, label: ' ', values: { tier: '' } })).toBe(true);
    expect(isBlank({ key: 1, label: '', values: { tier: 'GOLD' } })).toBe(false);
  });
});
