import { describe, expect, it } from 'vitest';
import type { Rule, RuleSetDefinition } from '../../../api/rules/schemas';
import { diffRules } from './diff';
import { draftReducer, initialDraft } from './draft';
import { placeError } from './engineErrors';
import { nonAsciiCharacters } from './nonAscii';
import { formatDecimalText, formatMoney, groupWhileTyping, toMinorUnits } from './money';
import { clauseLabel, columnLabel, readCondition, readOutput, readResult } from './reading';
import { emptyAmountHint } from './cellMessages';
import { valueLabel } from './cells';
import { nextRowId, sameRows, toDocument, toRows } from './table';

const definition: RuleSetDefinition = {
  schemaVersion: 1,
  ruleSet: 'card-fee',
  hitPolicy: 'first',
  effectiveFrom: '2026-11-01T00:00:00Z',
  inputs: [{ name: 'tier', type: 'string' }, { name: 'balance', type: 'money' }],
  outputs: [{ name: 'fee', type: 'money' }, { name: 'waived', type: 'boolean' }],
  rules: [
    { id: 'R1', when: { tier: "== 'PLATINUM'" }, then: { fee: '0 VND', waived: true } },
    { id: 'R2', when: { tier: "== 'GOLD'", balance: '>= 50000000 VND' }, then: { fee: '0 VND', waived: true } },
    { id: 'R3', when: {}, then: { fee: '30000 VND', waived: false }, description: 'Everyone else' },
  ],
};

describe('money without floating point', () => {
  it('TR_UI_05_minor_units_format_with_the_language_separators', () => {
    expect(formatMoney({ currency: 'VND', amount: 50000000 }, 'en')).toBe('50,000,000 VND');
    expect(formatMoney({ currency: 'VND', amount: 50000000 }, 'vi')).toBe('50.000.000 VND');
    expect(formatMoney({ currency: 'USD', amount: 1250 }, 'en')).toBe('12.50 USD');
    expect(formatMoney({ currency: 'USD', amount: -5 }, 'en')).toBe('−0.05 USD');
    expect(formatMoney({ currency: 'VND', amount: 9007199254740991 }, 'en')).toBe('9,007,199,254,740,991 VND');
  });

  it('TR_UI_05_decimal_text_and_typing_keep_every_digit', () => {
    expect(formatDecimalText('1234567.891', 'vi')).toBe('1.234.567,891');
    expect(formatDecimalText('abc', 'en')).toBeUndefined();
    expect(groupWhileTyping('60000000', 'en')).toBe('60,000,000');
    expect(groupWhileTyping('60.000.0001', 'vi')).toBe('600.000.001');
    expect(groupWhileTyping('1234.5', 'en')).toBe('1,234.5');
  });

  it('TR_UI_05_typed_amounts_become_exact_minor_units', () => {
    expect(toMinorUnits('60,000,000', 'VND', 'en')).toEqual({ ok: true, amount: 60000000 });
    expect(toMinorUnits('12,50', 'USD', 'vi')).toEqual({ ok: true, amount: 1250 });
    expect(toMinorUnits('0.1', 'VND', 'en')).toEqual({ ok: false, reason: 'scale' });
    expect(toMinorUnits('ten', 'VND', 'en')).toEqual({ ok: false, reason: 'format' });
    expect(toMinorUnits('99999999999999999', 'VND', 'en')).toEqual({ ok: false, reason: 'range' });
  });
});

describe('cell readings', () => {
  it('CKP_030_conditions_read_as_values_not_expressions', () => {
    expect(readCondition(undefined, 'en')).toEqual({ kind: 'any' });
    expect(readCondition("== 'GOLD'", 'en')).toEqual({ kind: 'values', negated: false, values: [{ kind: 'text', text: 'Gold' }] });
    expect(readCondition("in ['GOLD', 'PLATINUM']", 'en')).toEqual({ kind: 'values', negated: false, values: [{ kind: 'text', text: 'Gold' }, { kind: 'text', text: 'Platinum' }] });
    expect(readCondition('>= 50000000 VND', 'vi')).toEqual({ kind: 'compare', operator: '≥', value: { kind: 'text', text: '50.000.000 VND' } });
    expect(readCondition('== true', 'en')).toEqual({ kind: 'values', negated: false, values: [{ kind: 'yes' }] });
    expect(readCondition('balance > txCount * 2', 'en')).toEqual({ kind: 'raw', text: 'balance > txCount * 2' });
    expect(readCondition([{ tier: "== 'GOLD'" }], 'en')).toEqual({ kind: 'group' });
  });

  it('CKP_030_outputs_and_results_read_with_formatted_amounts', () => {
    expect(readOutput('30000 VND', 'en')).toEqual({ kind: 'value', value: { kind: 'text', text: '30,000 VND' } });
    expect(readOutput(false, 'en')).toEqual({ kind: 'value', value: { kind: 'no' } });
    expect(readOutput("'REVIEW'", 'en')).toEqual({ kind: 'value', value: { kind: 'text', text: 'Review' } });
    expect(readResult({ currency: 'VND', amount: 10000 }, 'en')).toEqual({ kind: 'text', text: '10,000 VND' });
    expect(columnLabel('txCount', undefined)).toBe('Tx count');
    expect(valueLabel('PLATINUM')).toBe('Platinum');
    expect(valueLabel('VN')).toBe('VN');
    expect(columnLabel('fee', 'Annual fee')).toBe('Annual fee');
  });
});

describe('engine errors on cells', () => {
  it('CKP_032_engine_detail_names_the_cell_or_the_row', () => {
    expect(placeError('Rule R4, input balance: compares a money with a number')).toEqual({ kind: 'cell', ruleId: 'R4', column: 'balance', message: 'compares a money with a number' });
    expect(placeError("rule R3: Unknown input 'x' at position 1")).toEqual({ kind: 'row', ruleId: 'R3', message: "Unknown input 'x' at position 1" });
    expect(placeError('Document is not well-formed YAML: bad')).toEqual({ kind: 'table', message: 'Document is not well-formed YAML: bad' });
  });
});

describe('draft editing', () => {
  it('CKP_032_row_actions_keep_stable_keys_and_fresh_ids', () => {
    let state = initialDraft(toRows(definition));
    state = draftReducer(state, { type: 'copy', key: state.rows[1]?.key ?? '' });
    expect(state.rows.map((r) => r.id)).toEqual(['R1', 'R2', 'R4', 'R3']);
    state = draftReducer(state, { type: 'move', key: state.rows[2]?.key ?? '', by: 1 });
    expect(state.rows.map((r) => r.id)).toEqual(['R1', 'R2', 'R3', 'R4']);
    state = draftReducer(state, { type: 'add' });
    expect(state.rows.at(-1)?.id).toBe('R5');
    state = draftReducer(state, { type: 'delete', key: state.rows[0]?.key ?? '' });
    expect(state.rows.map((r) => r.id)).toEqual(['R2', 'R3', 'R4', 'R5']);
    expect(new Set(state.rows.map((r) => r.key)).size).toBe(4);
    expect(nextRowId([{ id: 'R9' }, { id: 'X1' }])).toBe('R10');
  });

  it('CKP_032_document_keeps_types_drops_effective_from_and_never_changes_columns', () => {
    let state = initialDraft(toRows(definition));
    state = draftReducer(state, { type: 'edit-cell', key: state.rows[1]?.key ?? '', part: 'when', column: 'tier', text: "in ['GOLD', 'PLATINUM']" });
    state = draftReducer(state, { type: 'edit-cell', key: state.rows[2]?.key ?? '', part: 'then', column: 'waived', text: 'true' });
    state = draftReducer(state, { type: 'edit-cell', key: state.rows[1]?.key ?? '', part: 'when', column: 'balance', text: '' });
    const document = toDocument(definition, state.rows);
    expect(document['effectiveFrom']).toBeUndefined();
    expect(document['inputs']).toEqual(definition.inputs);
    expect(document['hitPolicy']).toBe('first');
    expect(document['rules']).toEqual([
      { id: 'R1', when: { tier: "== 'PLATINUM'" }, then: { fee: '0 VND', waived: true } },
      { id: 'R2', when: { tier: "in ['GOLD', 'PLATINUM']" }, then: { fee: '0 VND', waived: true } },
      { description: 'Everyone else', id: 'R3', when: {}, then: { fee: '30000 VND', waived: true } },
    ]);
    expect(sameRows(state.rows, toRows(definition))).toBe(false);
    expect(sameRows(toRows(definition), toRows(definition))).toBe(true);
  });
});

describe('version diff', () => {
  it('CKP_030_rows_match_by_id_with_added_changed_moved_and_removed', () => {
    const base = definition.rules;
    const [r1, r2] = base as [Rule, Rule, Rule];
    const next: Rule[] = [r2, { ...r1, then: { fee: '5000 VND', waived: false } }, { id: 'R9', when: {}, then: {} }];
    const diff = diffRules(base, next);
    expect(diff.map((row) => [row.ruleId, row.change, row.moved])).toEqual([
      ['R2', 'UNCHANGED', false],
      ['R1', 'CHANGED', true],
      ['R9', 'ADDED', false],
      ['R3', 'REMOVED', false],
    ]);
    expect([...(diff[1]?.changedCells ?? [])]).toEqual(['then.fee', 'then.waived']);
  });
});

describe('non-ASCII literals', () => {
  it('ADR_0026_look_alike_characters_are_named_by_code_point', () => {
    expect(nonAsciiCharacters("== 'GОLD'")).toEqual(['О U+041E']);
    expect(nonAsciiCharacters("== 'GOLD'")).toEqual([]);
    expect(nonAsciiCharacters("'Phí thường niên'")).toEqual(['í U+00ED', 'ư U+01B0', 'ờ U+1EDD', 'ê U+00EA']);
  });
});

describe('Try it currencies', () => {
  it('CKP_030_an_input_takes_its_currency_from_its_own_cells_only', async () => {
    const { currencyOfInput } = await import('../components/TryItPanel');
    const mixed: RuleSetDefinition = {
      ...definition,
      rules: [
        { id: 'R1', when: { balance: '>= 1000 USD' }, then: { fee: '0 VND' } },
        { id: 'R2', when: {}, then: { fee: '50000 VND' } },
      ],
    };
    expect(currencyOfInput(mixed, 'balance')).toEqual({ kind: 'known', code: 'USD' });
    expect(currencyOfInput(mixed, 'tier')).toEqual({ kind: 'unknown' });
    const disagree: RuleSetDefinition = { ...mixed, rules: [...mixed.rules, { id: 'R3', when: { balance: '< 5000000 VND' }, then: {} }] };
    expect(currencyOfInput(disagree, 'balance')).toEqual({ kind: 'mixed' });
  });
});

describe('column labels and cell hints', () => {
  it('CKP_006_a_column_reads_with_the_rule_sets_own_label_in_the_screen_language', () => {
    // The display label arrives with the labels slice (#392): one text, or texts by language code.
    expect(clauseLabel({ name: 'balance', label: { en: 'Balance', vi: 'Số dư' } }, 'vi')).toBe('Số dư');
    expect(clauseLabel({ name: 'balance', label: { en: 'Balance' } }, 'vi')).toBe('Balance');
    expect(clauseLabel({ name: 'txCount', label: 'Số giao dịch' }, 'en')).toBe('Số giao dịch');
    // Without a label, the existing reading of the description or the name.
    expect(clauseLabel({ name: 'txCount' }, 'vi')).toBe(columnLabel('txCount', undefined));
    expect(clauseLabel({ name: 'tier', description: 'customer tier' }, 'vi')).toBe('Customer tier');
  });

  it('CKP_006_an_empty_amount_says_what_to_type', () => {
    const compare = { kind: 'compare', op: '≥', amount: '', currency: 'VND' };
    expect(emptyAmountHint(compare, '50,000,000 VND', 'VND')).toEqual({ key: 'cellHint.amountExample', params: { example: '50,000,000 VND' } });
    expect(emptyAmountHint(compare, undefined, 'VND')).toEqual({ key: 'cellHint.amountIn', params: { currency: 'VND' } });
    expect(emptyAmountHint({ kind: 'compare', amount: '', currency: undefined }, undefined, undefined)).toEqual({ key: 'cellHint.number', params: {} });
    // A filled amount, or any other cell, shows the engine's sentence alone.
    expect(emptyAmountHint({ ...compare, amount: '5' }, '50,000,000 VND', 'VND')).toBeUndefined();
    expect(emptyAmountHint({ kind: 'choice' }, undefined, undefined)).toBeUndefined();
  });
});

describe('CKP_053 choice cells that exclude values', () => {
  it('reads and writes not-in and not-equal as Not these', async () => {
    const { readConditionCell, writeConditionCell } = await import('./cells');
    expect(readConditionCell("not in ['GOLD', 'PLATINUM']", 'choice')).toEqual({ kind: 'choice', values: ['GOLD', 'PLATINUM'], negate: true });
    expect(readConditionCell("!= 'GOLD'", 'choice')).toEqual({ kind: 'choice', values: ['GOLD'], negate: true });
    expect(writeConditionCell({ kind: 'choice', values: ['GOLD', 'PLATINUM'], negate: true })).toBe("not in ['GOLD', 'PLATINUM']");
    expect(writeConditionCell({ kind: 'choice', values: ['GOLD'], negate: true })).toBe("!= 'GOLD'");
  });
});
