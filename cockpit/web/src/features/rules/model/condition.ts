import type { InputClause } from '../../../api/rules/schemas';
import type { Locale } from '../../../i18n/I18nProvider';
import { valueLabel } from './cells';
import { formatDecimalText } from './money';

/*
 * The nested And / Or / Not condition builder (CK-F-45), as a pure model.
 *
 * A condition cell of a rule set is stored as the engine's expression text (`urn:horus-bpm:expr:v1`). The builder
 * reads that text into a tree of groups and comparison rows, edits the tree, and writes text back. It only handles the
 * part of the language it can show without losing meaning: comparisons of a declared input with a literal
 * (`== != < <= > >=`), `in` and `not in` over a list, `is null` and `is not null`, a bare boolean input, and
 * `and`, `or`, `not` with parentheses. Anything else (a function call, arithmetic, `between`, two inputs compared
 * with each other, a date) is refused with a reason, and the cell stays text. The engine remains the only judge of
 * validity: this model checks shape and completeness, never business rules, and never evaluates anything.
 *
 * Grammar mirrored from the engine's parser (bpm-expression Parser): or < and < not < comparison; a cell that starts
 * with an operator is a shorthand for its own column (CompiledRuleSet.conditionSource).
 */

/** Levels of groups (And, Or, Not) that may sit inside one another. */
export const MAX_DEPTH = 5;

export type CompareOp = '==' | '!=' | '<' | '<=' | '>' | '>=';

export type Literal =
  | { type: 'number'; text: string }
  | { type: 'money'; text: string; currency: string }
  | { type: 'string'; text: string }
  | { type: 'bool'; value: boolean };

export type Leaf =
  | { kind: 'compare'; id: string; input: string; op: CompareOp; value: Literal }
  | { kind: 'list'; id: string; input: string; negate: boolean; values: Literal[] }
  | { kind: 'empty'; id: string; input: string; negate: boolean };

export interface Group { kind: 'group'; id: string; op: 'and' | 'or'; children: Node[] }
export interface Not { kind: 'not'; id: string; child: Node }
export type Node = Group | Not | Leaf;

let counter = 0;
/** A fresh node id; ids only tie a node to its screen row, and to the place the engine reports a problem. */
export const newId = (): string => { counter += 1; return `c${String(counter)}`; };

/* ---------------------------------------------------------------- attributes */

/** What an input can be compared with in the builder. A date or datetime is not buildable yet. */
export type AttributeKind = 'choice' | 'number' | 'money' | 'bool' | 'string';

export function attributeKind(input: InputClause): AttributeKind | undefined {
  if (input.allowed !== undefined && input.allowed.length > 0) return 'choice';
  switch (input.type) {
    case 'money': return 'money';
    case 'integer':
    case 'decimal': return 'number';
    case 'boolean': return 'bool';
    case 'string': return 'string';
    default: return undefined;
  }
}

export const buildable = (inputs: readonly InputClause[]): InputClause[] => inputs.filter((input) => attributeKind(input) !== undefined);

/* ---------------------------------------------------------------- operators */

export type OpKey = 'eq' | 'ne' | 'lt' | 'le' | 'gt' | 'ge' | 'in' | 'notin' | 'null' | 'notnull';

const COMPARE_OF: Record<string, CompareOp> = { eq: '==', ne: '!=', lt: '<', le: '<=', gt: '>', ge: '>=' };

export function opsFor(kind: AttributeKind): readonly OpKey[] {
  switch (kind) {
    case 'choice': return ['eq', 'ne', 'in', 'notin', 'null', 'notnull'];
    case 'number':
    case 'money': return ['eq', 'ne', 'lt', 'le', 'gt', 'ge', 'null', 'notnull'];
    case 'bool': return ['eq', 'ne', 'null', 'notnull'];
    case 'string': return ['eq', 'ne', 'null', 'notnull'];
  }
}

export function opKeyOf(leaf: Leaf): OpKey {
  if (leaf.kind === 'empty') return leaf.negate ? 'notnull' : 'null';
  if (leaf.kind === 'list') return leaf.negate ? 'notin' : 'in';
  return (Object.entries(COMPARE_OF).find(([, op]) => op === leaf.op)?.[0] ?? 'eq') as OpKey;
}

const emptyLiteral = (kind: AttributeKind, currency: string): Literal => {
  switch (kind) {
    case 'money': return { type: 'money', text: '', currency };
    case 'number': return { type: 'number', text: '' };
    case 'bool': return { type: 'bool', value: true };
    default: return { type: 'string', text: '' };
  }
};

/** A new comparison row for an input: its first operator, and no value yet (which the row shows as incomplete). */
export function newLeaf(input: InputClause, currency: string): Leaf {
  const kind = attributeKind(input) ?? 'string';
  return { kind: 'compare', id: newId(), input: input.name, op: kind === 'number' || kind === 'money' ? '>=' : '==', value: emptyLiteral(kind, currency) };
}

/** The row with another operator; a value that still fits stays, otherwise the row starts empty for the new shape. */
export function withOp(leaf: Leaf, key: OpKey, kind: AttributeKind, currency: string): Leaf {
  const first = leaf.kind === 'compare' ? leaf.value : leaf.kind === 'list' ? leaf.values[0] : undefined;
  if (key === 'null' || key === 'notnull') return { kind: 'empty', id: leaf.id, input: leaf.input, negate: key === 'notnull' };
  if (key === 'in' || key === 'notin') return { kind: 'list', id: leaf.id, input: leaf.input, negate: key === 'notin', values: leaf.kind === 'list' ? leaf.values : first !== undefined && first.type === 'string' && first.text !== '' ? [first] : [] };
  const op = COMPARE_OF[key];
  if (op === undefined) return leaf;
  return { kind: 'compare', id: leaf.id, input: leaf.input, op, value: first ?? emptyLiteral(kind, currency) };
}

/** The row on another input: the operator and value stay only when the new input is of the same kind. */
export function withInput(leaf: Leaf, input: InputClause, was: AttributeKind | undefined, currency: string): Leaf {
  const kind = attributeKind(input) ?? 'string';
  if (kind === was) return { ...leaf, input: input.name };
  const fresh = newLeaf(input, currency);
  return { ...fresh, id: leaf.id };
}

/* ---------------------------------------------------------------- completeness */

/** True when a row still lacks the value that makes it a condition. The engine is not asked about it. */
export function incomplete(leaf: Leaf): boolean {
  switch (leaf.kind) {
    case 'compare': return leaf.value.type === 'bool' ? false : leaf.value.text.trim() === '' || (leaf.value.type === 'money' && leaf.value.currency.trim() === '');
    case 'list': return leaf.values.length === 0;
    case 'empty': return false;
  }
}

/* ---------------------------------------------------------------- reading text into a tree */

export type Unsupported = 'function' | 'arithmetic' | 'between' | 'inputs' | 'unknown' | 'depth' | 'syntax' | 'literal' | 'type';
export type FromText = { ok: true; root: Group } | { ok: false; reason: Unsupported };

type Token =
  | { t: 'num'; text: string }
  | { t: 'str'; text: string }
  | { t: 'id'; text: string }
  | { t: 'op'; text: string }
  | { t: '(' } | { t: ')' } | { t: '[' } | { t: ']' } | { t: ',' };

class Refused extends Error {
  constructor(readonly reason: Unsupported) { super(reason); }
}

function lex(source: string): Token[] {
  const tokens: Token[] = [];
  let i = 0;
  while (i < source.length) {
    const c = source.charAt(i);
    if (/\s/.test(c)) { i += 1; continue; }
    if (/[0-9]/.test(c)) {
      const m = /^\d+(?:\.\d+)?/.exec(source.slice(i));
      const text = m?.[0] ?? c;
      i += text.length;
      tokens.push({ t: 'num', text });
      continue;
    }
    if (c === "'") {
      let text = '';
      i += 1;
      let closed = false;
      while (i < source.length) {
        const s = source.charAt(i);
        i += 1;
        if (s === '\\' && i < source.length) {
          const escaped = source.charAt(i);
          i += 1;
          if (escaped === 'u' && /^[0-9a-fA-F]{4}/.test(source.slice(i))) { text += String.fromCharCode(parseInt(source.slice(i, i + 4), 16)); i += 4; }
          else if (escaped === "'" || escaped === '\\') text += escaped;
          else throw new Refused('syntax');
        } else if (s === "'") { closed = true; break; }
        else text += s;
      }
      if (!closed) throw new Refused('syntax');
      tokens.push({ t: 'str', text });
      continue;
    }
    if (/[A-Za-z_]/.test(c)) {
      const m = /^[A-Za-z_][A-Za-z0-9_]*/.exec(source.slice(i));
      const text = m?.[0] ?? c;
      i += text.length;
      tokens.push({ t: 'id', text });
      continue;
    }
    const two = source.slice(i, i + 2);
    if (['==', '!=', '<=', '>='].includes(two)) { tokens.push({ t: 'op', text: two }); i += 2; continue; }
    if (['<', '>'].includes(c)) { tokens.push({ t: 'op', text: c }); i += 1; continue; }
    if (['+', '-', '*', '/'].includes(c)) throw new Refused('arithmetic');
    if (c === '(') tokens.push({ t: '(' });
    else if (c === ')') tokens.push({ t: ')' });
    else if (c === '[') tokens.push({ t: '[' });
    else if (c === ']') tokens.push({ t: ']' });
    else if (c === ',') tokens.push({ t: ',' });
    else throw new Refused('syntax');
    i += 1;
  }
  return tokens;
}

/** The same test the engine uses to take a cell as a shorthand of its own column. */
const SHORTHAND = ['==', '!=', '<', '>', 'in ', 'in[', 'not in', 'is ', 'between '];

class Reader {
  private at = 0;
  constructor(private readonly tokens: Token[], private readonly inputs: ReadonlyMap<string, AttributeKind | undefined>) {}

  done(): boolean { return this.at >= this.tokens.length; }
  private peek(offset = 0): Token | undefined { return this.tokens[this.at + offset]; }
  private word(text: string, offset = 0): boolean { const t = this.peek(offset); return t?.t === 'id' && t.text === text; }
  private eatWord(text: string): boolean { if (this.word(text)) { this.at += 1; return true; } return false; }

  or(): Node {
    const parts = [this.and()];
    while (this.eatWord('or')) parts.push(this.and());
    return parts.length === 1 ? (parts[0] as Node) : { kind: 'group', id: newId(), op: 'or', children: parts };
  }

  private and(): Node {
    const parts = [this.not()];
    while (this.eatWord('and')) parts.push(this.not());
    return parts.length === 1 ? (parts[0] as Node) : { kind: 'group', id: newId(), op: 'and', children: parts };
  }

  private not(): Node {
    if (this.word('not') && !this.word('in', 1)) {
      this.at += 1;
      return { kind: 'not', id: newId(), child: this.not() };
    }
    return this.comparison();
  }

  private comparison(): Node {
    const first = this.peek();
    if (first?.t === '(') {
      this.at += 1;
      const inner = this.or();
      if (this.peek()?.t !== ')') throw new Refused('syntax');
      this.at += 1;
      const next = this.peek();
      if (next?.t === 'op' || this.word('in') || this.word('is') || this.word('not') || this.word('between')) throw new Refused('syntax');
      return inner;
    }
    if (first?.t !== 'id') throw new Refused(first?.t === 'num' || first?.t === 'str' ? 'inputs' : 'syntax');
    this.at += 1;
    if (this.peek()?.t === '(') throw new Refused('function');
    if (!this.inputs.has(first.text)) throw new Refused('unknown');
    const input = first.text;
    const kind = this.inputs.get(input);
    if (kind === undefined) throw new Refused('type');
    const next = this.peek();
    if (next?.t === 'op') {
      this.at += 1;
      const op = next.text as CompareOp;
      const value = this.literal(kind);
      if (value === null) return { kind: 'empty', id: newId(), input, negate: op === '!=' };
      return { kind: 'compare', id: newId(), input, op, value };
    }
    if (this.word('not') && this.word('in', 1)) { this.at += 2; return { kind: 'list', id: newId(), input, negate: true, values: this.list(kind) }; }
    if (this.eatWord('in')) return { kind: 'list', id: newId(), input, negate: false, values: this.list(kind) };
    if (this.eatWord('is')) {
      const negate = this.eatWord('not');
      if (!this.eatWord('null')) throw new Refused('syntax');
      return { kind: 'empty', id: newId(), input, negate };
    }
    if (this.word('between')) throw new Refused('between');
    if (kind === 'bool') return { kind: 'compare', id: newId(), input, op: '==', value: { type: 'bool', value: true } };
    throw new Refused('syntax');
  }

  /** One literal; null for the word `null`. */
  private literal(kind: AttributeKind): Literal | null {
    const token = this.peek();
    if (token === undefined) throw new Refused('syntax');
    this.at += 1;
    if (token.t === 'num') {
      const after = this.peek();
      if (after?.t === 'id' && /^[A-Z]{3}$/.test(after.text) && !this.inputs.has(after.text)) { this.at += 1; return { type: 'money', text: token.text, currency: after.text }; }
      return kind === 'money' ? { type: 'money', text: token.text, currency: '' } : { type: 'number', text: token.text };
    }
    if (token.t === 'str') return { type: 'string', text: token.text };
    if (token.t === 'id') {
      if (token.text === 'true') return { type: 'bool', value: true };
      if (token.text === 'false') return { type: 'bool', value: false };
      if (token.text === 'null') return null;
      if (this.peek()?.t === '(') throw new Refused('function');
      throw new Refused(this.inputs.has(token.text) ? 'inputs' : 'unknown');
    }
    throw new Refused('literal');
  }

  private list(kind: AttributeKind): Literal[] {
    if (this.peek()?.t !== '[') throw new Refused('syntax');
    this.at += 1;
    const values: Literal[] = [];
    if (this.peek()?.t === ']') { this.at += 1; return values; }
    for (;;) {
      const value = this.literal(kind);
      if (value === null || value.type === 'bool' || value.type === 'money') throw new Refused('literal');
      values.push(value);
      const separator = this.peek();
      this.at += 1;
      if (separator?.t === ']') return values;
      if (separator?.t !== ',') throw new Refused('syntax');
    }
  }
}

/** The levels of Groups and Nots in a tree, a leaf being none. */
export function depthOf(node: Node): number {
  if (node.kind === 'group') return 1 + Math.max(0, ...node.children.map(depthOf));
  if (node.kind === 'not') return 1 + depthOf(node.child);
  return 0;
}

/**
 * Reads a condition cell into the builder's tree: always a group at the top, so a single comparison shows as an And
 * of one. Empty text is an empty group (the cell matches any value). Negative numbers are not shown: the engine reads
 * `-5` as arithmetic, and the builder leaves them as text.
 */
export function fromText(text: string, column: string, inputs: readonly InputClause[]): FromText {
  const trimmed = text.trim();
  if (trimmed === '') return { ok: true, root: { kind: 'group', id: newId(), op: 'and', children: [] } };
  const known = new Map(inputs.map((input) => [input.name, attributeKind(input)]));
  const source = SHORTHAND.some((prefix) => trimmed.startsWith(prefix)) ? `${column} ${trimmed}` : trimmed;
  try {
    const reader = new Reader(lex(source), known);
    const node = reader.or();
    if (!reader.done()) throw new Refused('syntax');
    const root: Group = node.kind === 'group' ? node : { kind: 'group', id: newId(), op: 'and', children: [node] };
    if (depthOf(root) > MAX_DEPTH) return { ok: false, reason: 'depth' };
    return { ok: true, root };
  } catch (error) {
    if (error instanceof Refused) return { ok: false, reason: error.reason };
    throw error;
  }
}

/* ---------------------------------------------------------------- writing a tree as text */

export interface Span { id: string; start: number; end: number }
export interface Written { text: string; spans: Span[] }

const quote = (text: string): string => `'${text.replaceAll('\\', '\\\\').replaceAll("'", "\\'")}'`;

function literalText(value: Literal): string {
  switch (value.type) {
    case 'number': return value.text.trim();
    case 'money': return value.text.trim() === '' || value.currency === '' ? value.text.trim() : `${value.text.trim()} ${value.currency}`;
    case 'string': return quote(value.text);
    case 'bool': return String(value.value);
  }
}

const OP_TEXT: Record<CompareOp, string> = { '==': '==', '!=': '!=', '<': '<', '<=': '<=', '>': '>', '>=': '>=' };

/** The row as engine text; `named` false drops the input name for a cell of that input (the shorthand). */
function leafText(leaf: Leaf, named: boolean): string {
  const name = named ? `${leaf.input} ` : '';
  switch (leaf.kind) {
    case 'compare': return `${name}${OP_TEXT[leaf.op]} ${literalText(leaf.value)}`.trimEnd();
    case 'list': return `${name}${leaf.negate ? 'not in' : 'in'} [${leaf.values.map(literalText).join(', ')}]`;
    case 'empty': return `${name}${leaf.negate ? 'is not null' : 'is null'}`;
  }
}

function isEmptyNode(node: Node): boolean {
  if (node.kind === 'group') return node.children.every(isEmptyNode);
  if (node.kind === 'not') return isEmptyNode(node.child);
  return false;
}

function emit(node: Node, offset: number, parenthesise: boolean): Written {
  if (node.kind === 'group') {
    const parts = node.children.filter((child) => !isEmptyNode(child));
    if (parts.length === 0) return { text: '', spans: [] };
    if (parts.length === 1) {
      const only = emit(parts[0] as Node, offset, parenthesise);
      return { text: only.text, spans: [...only.spans, { id: node.id, start: offset, end: offset + only.text.length }] };
    }
    let text = parenthesise ? '(' : '';
    const spans: Span[] = [];
    parts.forEach((child, index) => {
      if (index > 0) text += ` ${node.op} `;
      const part = emit(child, offset + text.length, true);
      spans.push(...part.spans);
      text += part.text;
    });
    if (parenthesise) text += ')';
    spans.push({ id: node.id, start: offset, end: offset + text.length });
    return { text, spans };
  }
  if (node.kind === 'not') {
    const inner = emit(node.child, offset + 5, false);
    const text = `not (${inner.text})`;
    return { text, spans: [...inner.spans, { id: node.id, start: offset, end: offset + text.length }] };
  }
  const text = leafText(node, true);
  return { text, spans: [{ id: node.id, start: offset, end: offset + text.length }] };
}

/** The one comparison a tree comes down to, when the groups around it hold nothing else; its wrappers come with it. */
function sole(node: Node, wrappers: string[] = []): { leaf: Leaf; wrappers: string[] } | undefined {
  if (node.kind === 'group') {
    const parts = node.children.filter((child) => !isEmptyNode(child));
    return parts.length === 1 ? sole(parts[0] as Node, [...wrappers, node.id]) : undefined;
  }
  return node.kind === 'not' ? undefined : { leaf: node, wrappers };
}

/** True when a tree is more than one comparison: nested groups, a Not, or several rows. */
export const isCompound = (root: Node): boolean => !isEmptyNode(root) && sole(root) === undefined;

/**
 * The tree as the engine's text, with where each node sits in it so a problem the engine places by character
 * position finds its node. A tree that is one comparison on the cell's own column is the shorthand (`>= 50000000 VND`),
 * so a plain cell stays as the other editors write it.
 */
export function toText(root: Node, column: string): Written {
  const one = sole(root);
  if (one !== undefined && one.leaf.input === column) {
    const text = leafText(one.leaf, false);
    return { text, spans: [one.leaf.id, ...one.wrappers].map((id) => ({ id, start: 0, end: text.length })) };
  }
  return emit(root, 0, false);
}

/* ---------------------------------------------------------------- placing an engine problem */

/**
 * The node a problem at `position` (zero-based, in the text as written) belongs to: the smallest node around that
 * character. A problem right after a row that lacks its value (the engine meets the next word where the value
 * should be) belongs to that row.
 */
export function locate(written: Written, root: Node, position: number | null | undefined): string | undefined {
  if (position === null || position === undefined || position < 0) return undefined;
  const incompleteLeaves = new Map<string, Leaf>();
  const collect = (node: Node): void => {
    if (node.kind === 'group') node.children.forEach(collect);
    else if (node.kind === 'not') collect(node.child);
    else if (incomplete(node)) incompleteLeaves.set(node.id, node);
  };
  collect(root);
  for (const span of written.spans) {
    if (incompleteLeaves.has(span.id) && position >= span.end && written.text.slice(span.end, position).trim() === '') return span.id;
  }
  const around = written.spans.filter((span) => span.start <= position && (position < span.end || (position >= written.text.length && span.end === written.text.length)));
  // The spans list a node after its children, so the first of the smallest is the innermost.
  return around.reduce<Span | undefined>((best, span) => (best === undefined || span.end - span.start < best.end - best.start ? span : best), undefined)?.id;
}

/* ---------------------------------------------------------------- editing a tree */

type Edit = (node: Node) => Node | Node[] | null;

/** Applies `edit` to the node `id`: a node, several nodes (they take its place), or null (it goes). */
export function edit(root: Node, id: string, change: Edit): Node {
  const walk = (node: Node): Node[] => {
    if (node.id === id) {
      const result = change(node);
      return result === null ? [] : Array.isArray(result) ? result : [result];
    }
    if (node.kind === 'group') return [{ ...node, children: node.children.flatMap(walk) }];
    if (node.kind === 'not') {
      const inner = walk(node.child);
      return inner.length === 0 ? [] : [{ ...node, child: inner[0] as Node }];
    }
    return [node];
  };
  return walk(root)[0] ?? { kind: 'group', id: newId(), op: 'and', children: [] };
}

export function addChild(root: Node, groupId: string, child: Node): Node {
  return edit(root, groupId, (node) => (node.kind === 'group' ? { ...node, children: [...node.children, child] } : node));
}

export const removeNode = (root: Node, id: string): Node => edit(root, id, () => null);

export const wrapInNot = (root: Node, id: string): Node => edit(root, id, (node) => ({ kind: 'not', id: newId(), child: node }));

export const unwrapNot = (root: Node, id: string): Node => edit(root, id, (node) => (node.kind === 'not' ? node.child : node));

export function moveNode(root: Node, id: string, by: -1 | 1): Node {
  const walk = (node: Node): Node => {
    if (node.kind === 'group') {
      const at = node.children.findIndex((child) => child.id === id);
      if (at !== -1) {
        const to = at + by;
        if (to < 0 || to >= node.children.length) return node;
        const children = [...node.children];
        [children[at], children[to]] = [children[to] as Node, children[at] as Node];
        return { ...node, children };
      }
      return { ...node, children: node.children.map(walk) };
    }
    return node.kind === 'not' ? { ...node, child: walk(node.child) } : node;
  };
  return walk(root);
}

/** Levels of Groups and Nots around the node `id`, itself included when it is one; 0 when it is not in the tree. */
export function levelOf(root: Node, id: string, level = 0): number {
  const here = root.kind === 'group' || root.kind === 'not' ? level + 1 : level;
  if (root.id === id) return here;
  if (root.kind === 'group') return Math.max(0, ...root.children.map((child) => levelOf(child, id, here)));
  if (root.kind === 'not') return levelOf(root.child, id, here);
  return 0;
}

export const findNode = (root: Node, id: string): Node | undefined => {
  if (root.id === id) return root;
  if (root.kind === 'group') return root.children.map((child) => findNode(child, id)).find((found) => found !== undefined);
  return root.kind === 'not' ? findNode(root.child, id) : undefined;
};

/** The tree without ids, to compare two trees by what they say. */
export function shape(node: Node): unknown {
  if (node.kind === 'group') return { group: node.op, children: node.children.map(shape) };
  if (node.kind === 'not') return { not: shape(node.child) };
  return Object.fromEntries(Object.entries(node).filter(([key]) => key !== 'id'));
}

/* ---------------------------------------------------------------- reading a tree in plain words */

/** What the words need from the screen: a name for an input and the sentence parts of the language. */
export interface Words {
  locale: Locale;
  label: (input: string) => string;
  t: (key: WordKey, params?: Readonly<Record<string, string>>) => string;
}

export type WordKey =
  | 'builder.read.eq' | 'builder.read.ne' | 'builder.read.gt' | 'builder.read.ge' | 'builder.read.lt' | 'builder.read.le'
  | 'builder.read.in' | 'builder.read.notin' | 'builder.read.null' | 'builder.read.notnull' | 'builder.read.not'
  | 'builder.read.any' | 'table.yes' | 'table.no';

const WORD_OF: Record<CompareOp, WordKey> = { '==': 'builder.read.eq', '!=': 'builder.read.ne', '>': 'builder.read.gt', '>=': 'builder.read.ge', '<': 'builder.read.lt', '<=': 'builder.read.le' };

/** One literal as a person reads it: grouped amounts with the currency, a code as words, Yes or No. */
export function literalWords(value: Literal, words: Words): string {
  switch (value.type) {
    case 'bool': return words.t(value.value ? 'table.yes' : 'table.no');
    case 'string': return valueLabel(value.text);
    case 'number': return formatDecimalText(value.text, words.locale) ?? value.text;
    case 'money': return `${formatDecimalText(value.text, words.locale) ?? value.text} ${value.currency}`.trim();
  }
}

function leafWords(leaf: Leaf, words: Words): string {
  const input = words.label(leaf.input);
  switch (leaf.kind) {
    case 'compare': return words.t(WORD_OF[leaf.op], { input, value: literalWords(leaf.value, words) });
    case 'list': return words.t(leaf.negate ? 'builder.read.notin' : 'builder.read.in', { input, values: new Intl.ListFormat(words.locale, { type: 'disjunction' }).format(leaf.values.map((value) => literalWords(value, words))) });
    case 'empty': return words.t(leaf.negate ? 'builder.read.notnull' : 'builder.read.null', { input });
  }
}

/**
 * The condition in plain words: rows as sentences, an And as "A and B", an Or as "A or B", a Not as "not (...)", and
 * a group inside a group in brackets so nesting reads the same way it is built. An empty condition is "any value".
 */
export function readWords(node: Node, words: Words): string {
  const body = (current: Node, inner: boolean): string => {
    if (current.kind === 'group') {
      const parts = current.children.filter((child) => !isEmptyNode(child));
      if (parts.length === 0) return '';
      const texts = parts.map((child) => body(child, true));
      if (texts.length === 1) return texts[0] ?? '';
      const joined = new Intl.ListFormat(words.locale, { type: current.op === 'and' ? 'conjunction' : 'disjunction' }).format(texts);
      return inner ? `(${joined})` : joined;
    }
    if (current.kind === 'not') {
      const inside = body(current.child, false);
      const compound = current.child.kind === 'group' && current.child.children.filter((child) => !isEmptyNode(child)).length > 1;
      return words.t('builder.read.not', { x: compound ? `(${inside})` : inside });
    }
    return leafWords(current, words);
  };
  const text = body(node, false);
  return text === '' ? words.t('builder.read.any') : text;
}
