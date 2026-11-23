import { describe, expect, it } from 'vitest';
import type { InputClause } from '../../../api/rules/schemas';
import { en } from '../../../i18n/en';
import { vi } from '../../../i18n/vi';
import {
  MAX_DEPTH, addChild, fromText, isCompound, locate, moveNode, newId, readWords, removeNode, shape, toText, unwrapNot, wrapInNot,
  type CompareOp, type Group, type Leaf, type Literal, type Node, type Words,
} from './condition';

/* The inputs of the card-fee table, with a boolean and a date so the reasons for staying as text are covered. */
const INPUTS: InputClause[] = [
  { name: 'tier', type: 'string', allowed: ['CLASSIC', 'GOLD', 'PLATINUM'] },
  { name: 'balance', type: 'money' },
  { name: 'txCount', type: 'integer' },
  { name: 'blocked', type: 'boolean' },
  { name: 'country', type: 'string' },
  { name: 'openedOn', type: 'date' },
];

const num = (text: string): Literal => ({ type: 'number', text });
const vnd = (text: string): Literal => ({ type: 'money', text, currency: 'VND' });
const str = (text: string): Literal => ({ type: 'string', text });
const cmp = (input: string, op: CompareOp, value: Literal): Leaf => ({ kind: 'compare', id: newId(), input, op, value });
const among = (input: string, negate: boolean, ...values: string[]): Leaf => ({ kind: 'list', id: newId(), input, negate, values: values.map(str) });
const blank = (input: string, negate: boolean): Leaf => ({ kind: 'empty', id: newId(), input, negate });
const group = (op: 'and' | 'or', ...children: Node[]): Group => ({ kind: 'group', id: newId(), op, children });
const not = (child: Node): Node => ({ kind: 'not', id: newId(), child });

const read = (text: string, column = 'tier') => {
  const result = fromText(text, column, INPUTS);
  if (!result.ok) throw new Error(`not shown: ${result.reason}`);
  return result.root;
};

describe('condition builder model', () => {
  it('CK_F_45_a_text_condition_opens_as_an_and_group_holding_an_or_group_and_a_not', () => {
    // The issue's example, in the engine's own words: `==` is the engine's equality, there is no single `=`.
    const root = read('(txCount > 1 or tier == \'GOLD\') and not blocked');
    expect(shape(root)).toEqual({
      group: 'and',
      children: [
        { group: 'or', children: [
          { kind: 'compare', input: 'txCount', op: '>', value: num('1') },
          { kind: 'compare', input: 'tier', op: '==', value: str('GOLD') },
        ] },
        { not: { kind: 'compare', input: 'blocked', op: '==', value: { type: 'bool', value: true } } },
      ],
    });
  });

  it('CK_F_45_b_builder_to_expression_to_builder_keeps_the_tree', () => {
    const trees: Node[] = [
      group('and', cmp('tier', '==', str('GOLD')), cmp('balance', '>=', vnd('50000000'))),
      group('or', group('and', cmp('tier', '==', str('GOLD')), cmp('balance', '>=', vnd('50000000.5'))), not(cmp('txCount', '<', num('5')))),
      group('and', group('or', among('tier', false, 'GOLD', 'PLATINUM'), blank('tier', false)), not(group('or', cmp('blocked', '==', { type: 'bool', value: false }), among('country', true, 'VN', 'SG')))),
      group('or', not(not(cmp('txCount', '!=', num('0')))), blank('country', true)),
      group('and', cmp('country', '==', str("O'Brien \\ Co"))),
      group('or', group('and', group('or', cmp('txCount', '<=', num('1')), cmp('txCount', '>', num('9'))), cmp('blocked', '!=', { type: 'bool', value: true })), blank('balance', false)),
    ];
    for (const tree of trees) {
      const written = toText(tree, 'tier');
      const back = read(written.text);
      expect(shape(back), written.text).toEqual(shape(tree));
      // And the text is stable: writing what was read gives the same text.
      expect(toText(back, 'tier').text).toBe(written.text);
    }
  });

  it('CK_F_45_c_the_stored_text_gives_the_same_result_as_the_tree_on_sample_inputs', () => {
    const tree = group('and', group('or', cmp('tier', '==', str('GOLD')), cmp('balance', '>=', vnd('50000000'))), not(cmp('blocked', '==', { type: 'bool', value: true })), among('country', true, 'US'));
    const back = read(toText(tree, 'tier').text);
    const samples = [
      { tier: 'GOLD', balance: 1, blocked: false, country: 'VN' },
      { tier: 'CLASSIC', balance: 60_000_000, blocked: false, country: 'VN' },
      { tier: 'CLASSIC', balance: 10, blocked: false, country: 'VN' },
      { tier: 'GOLD', balance: 1, blocked: true, country: 'VN' },
      { tier: 'GOLD', balance: 1, blocked: false, country: 'US' },
    ];
    for (const sample of samples) expect(evaluate(back, sample)).toBe(evaluate(tree, sample));
    expect(samples.map((sample) => evaluate(back, sample))).toEqual([true, true, false, false, false]);
  });

  it('CK_F_45_d_a_single_comparison_on_the_cells_own_column_is_written_as_the_shorthand', () => {
    expect(toText(group('and', cmp('balance', '>=', vnd('50000000'))), 'balance').text).toBe('>= 50000000 VND');
    expect(toText(group('and', among('tier', false, 'GOLD', 'PLATINUM')), 'tier').text).toBe("in ['GOLD', 'PLATINUM']");
    expect(toText(group('and', blank('tier', true)), 'tier').text).toBe('is not null');
    // Another column, or several rows, needs the full form.
    expect(toText(group('and', cmp('balance', '>=', vnd('5'))), 'tier').text).toBe('balance >= 5 VND');
    // A cell the other editors wrote reads back in the builder as one comparison.
    expect(shape(read('>= 50000000 VND', 'balance'))).toEqual({ group: 'and', children: [{ kind: 'compare', input: 'balance', op: '>=', value: vnd('50000000') }] });
    expect(shape(read("not in ['GOLD']", 'tier'))).toEqual({ group: 'and', children: [{ kind: 'list', input: 'tier', negate: true, values: [str('GOLD')] }] });
  });

  it('CK_F_45_e_an_empty_builder_is_a_wildcard_and_an_empty_text_is_an_empty_builder', () => {
    expect(toText(group('and'), 'tier').text).toBe('');
    expect(read('  ').children).toEqual([]);
    // A group that holds only empty groups adds nothing to the text.
    expect(toText(group('and', group('or'), cmp('txCount', '>', num('3'))), 'tier').text).toBe('txCount > 3');
  });

  it('CK_F_45_f_what_the_builder_cannot_show_stays_text_with_a_reason', () => {
    const refused = (text: string) => { const result = fromText(text, 'tier', INPUTS); return result.ok ? 'shown' : result.reason; };
    expect(refused("startsWith(country, 'V') and txCount > 1")).toBe('function');
    expect(refused('txCount + 1 > 3')).toBe('arithmetic');
    expect(refused('txCount > -1')).toBe('arithmetic');
    expect(refused('txCount between 1 and 5')).toBe('between');
    expect(refused('txCount > balance')).toBe('inputs');
    expect(refused('creditScore > 600')).toBe('unknown');
    expect(refused("openedOn > date('2026-12-01')")).toBe('type');
    expect(refused('txCount >')).toBe('syntax');
    expect(refused('(txCount > 1')).toBe('syntax');
    expect(refused('txCount > 1; drop')).toBe('syntax');
    expect(refused("tier == 'GOLD' and ")).toBe('syntax');
  });

  it('CK_F_45_g_nesting_is_capped_at_five_levels', () => {
    // The top group counts as level 1, so four Nots fit and a fifth does not.
    expect(fromText('not (not (not (not (txCount > 1))))', 'tier', INPUTS).ok).toBe(true);
    const deep = fromText('not (not (not (not (not (txCount > 1)))))', 'tier', INPUTS);
    expect(deep).toEqual({ ok: false, reason: 'depth' });
    expect(MAX_DEPTH).toBe(5);
  });

  it('CK_F_45_h_a_problem_at_a_character_position_finds_its_node', () => {
    const left = cmp('txCount', '>', num('1'));
    const right = cmp('tier', '==', str('GOLD'));
    const inner = group('or', left, right);
    const negated = not(cmp('blocked', '==', { type: 'bool', value: true }));
    const root = group('and', inner, negated);
    const written = toText(root, 'tier');
    expect(written.text).toBe("(txCount > 1 or tier == 'GOLD') and not (blocked == true)");
    // The engine's position of a comparison is its operator; of `or`, the word; of `not`, the word.
    expect(locate(written, root, written.text.indexOf('>'))).toBe(left.id);
    expect(locate(written, root, written.text.indexOf('=='))).toBe(right.id);
    expect(locate(written, root, written.text.indexOf(' or ') + 1)).toBe(inner.id);
    expect(locate(written, root, written.text.indexOf(' and ') + 1)).toBe(root.id);
    expect(locate(written, root, written.text.indexOf('not'))).toBe((negated as { id: string }).id);
    expect(locate(written, root, written.text.indexOf('blocked'))).toBe(((negated as { child: Node }).child).id);
    expect(locate(written, root, null)).toBeUndefined();
  });

  it('CK_F_45_i_a_problem_right_after_a_row_without_its_value_belongs_to_that_row', () => {
    const lacking = cmp('txCount', '>', num(''));
    const other = cmp('tier', '==', str('GOLD'));
    const root = group('and', lacking, other);
    const written = toText(root, 'tier');
    expect(written.text).toBe("txCount > and tier == 'GOLD'");
    // The engine meets `and` where the value should be.
    expect(locate(written, root, written.text.indexOf('and'))).toBe(lacking.id);
    // At the end of the text it is the last row.
    const last = group('and', other, lacking);
    const lastWritten = toText(last, 'tier');
    expect(locate(lastWritten, last, lastWritten.text.length)).toBe(lacking.id);
  });

  it('CK_F_45_j_editing_helpers_keep_the_tree_a_tree', () => {
    const a = cmp('txCount', '>', num('1'));
    const b = cmp('tier', '==', str('GOLD'));
    let root: Node = group('and', a);
    root = addChild(root, root.id, b);
    expect((root as Group).children.map((child) => child.id)).toEqual([a.id, b.id]);
    root = moveNode(root, b.id, -1);
    expect((root as Group).children.map((child) => child.id)).toEqual([b.id, a.id]);
    root = wrapInNot(root, a.id);
    expect(isCompound(root)).toBe(true);
    expect(shape(unwrapNot(root, ((root as Group).children[1] as { id: string }).id))).toEqual(shape(group('and', b, a)));
    // Removing the only row of a Not removes the Not with it.
    expect((removeNode(root, a.id) as Group).children).toEqual([b]);
  });

  it('CK_F_45_k_the_condition_reads_back_as_plain_words_in_english_and_vietnamese', () => {
    const tree = group('and', group('or', cmp('txCount', '>', num('1')), cmp('balance', '>=', vnd('50000000'))), not(cmp('blocked', '==', { type: 'bool', value: true })));
    const words = (locale: 'en' | 'vi'): Words => {
      const messages: Record<string, string> = locale === 'en' ? en : vi;
      return {
        locale,
        label: (name) => ({ txCount: locale === 'en' ? 'Transactions' : 'Số giao dịch', balance: locale === 'en' ? 'Balance' : 'Số dư', blocked: locale === 'en' ? 'Blocked' : 'Bị khoá' })[name] ?? name,
        t: (key, params = {}) => Object.entries(params).reduce((text, [name, value]) => text.replace(`{${name}}`, value), messages[key] ?? key),
      };
    };
    expect(readWords(tree, words('en'))).toBe('(Transactions is more than 1 or Balance is at least 50,000,000 VND) and not Blocked is Yes');
    expect(readWords(tree, words('vi'))).toBe('(Số giao dịch lớn hơn 1 hoặc Số dư ít nhất là 50.000.000 VND) và không phải Bị khoá là Có');
    expect(readWords(group('and'), words('en'))).toBe('any value');
    expect(readWords(not(group('or', cmp('txCount', '>', num('1')), cmp('txCount', '<', num('0')))), words('en'))).toBe('not (Transactions is more than 1 or Transactions is less than 0)');
  });
});

/** The two-valued meaning of a tree on one input: what a reader of the words would decide. */
function evaluate(node: Node, sample: Record<string, string | number | boolean>): boolean {
  if (node.kind === 'group') return node.op === 'and' ? node.children.every((child) => evaluate(child, sample)) : node.children.some((child) => evaluate(child, sample));
  if (node.kind === 'not') return !evaluate(node.child, sample);
  const have = sample[node.input];
  const same = (value: Literal): boolean => (value.type === 'bool' ? have === value.value : String(have) === value.text);
  if (node.kind === 'empty') return node.negate ? have !== undefined : have === undefined;
  if (node.kind === 'list') return node.negate !== node.values.some(same);
  const value = node.value;
  if (node.op === '==') return same(value);
  if (node.op === '!=') return !same(value);
  const left = Number(have);
  const right = Number(value.type === 'bool' ? NaN : value.text);
  return { '<': left < right, '<=': left <= right, '>': left > right, '>=': left >= right }[node.op];
}
