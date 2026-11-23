import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import type { InputClause, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n, type Translate } from '../../../i18n/I18nProvider';
import type { MessageKey } from '../../../i18n/en';
import { columnCurrency, valueLabel } from '../model/cells';
import {
  MAX_DEPTH, addChild, attributeKind, buildable, depthOf, edit, fromText, incomplete, levelOf, locate, moveNode, newId, newLeaf,
  opKeyOf, opsFor, readWords, removeNode, toText, unwrapNot, withInput, withOp, wrapInNot,
  type AttributeKind, type Group, type Leaf, type Literal, type Node, type OpKey, type Unsupported, type Words,
} from '../model/condition';
import { clauseLabel } from '../model/reading';
import { canonicalAmount, shownAmount } from './CellEditors';
import { MenuButton } from './MenuButton';

/*
 * The nested And / Or / Not condition builder for one condition cell (CK-F-45). It edits a tree of groups and
 * comparison rows; every change writes the engine's expression text back to the cell, and the engine checks it as
 * for any other cell. The stored text is shown only under Details. A condition the builder cannot show stays as text
 * in the cell, with the reason.
 */

export interface BuilderProblem { message: string; position: number | null | undefined }

export interface ConditionBuilderProps {
  /** Spoken name of the cell, for example "Row R2, Balance". */
  cell: string;
  column: InputClause;
  definition: RuleSetDefinition;
  /** The cell's text now. */
  text: string;
  /** The cell's text the engine last checked: its problems place by character position in this text. */
  checked: string;
  problems: readonly BuilderProblem[];
  onText: (next: string) => void;
  /** The table's check runs on what is committed; a change of structure commits at once, typing when it is done. */
  onCommit: () => void;
  onClose: () => void;
}

const NOTE_KEY: Record<Unsupported, MessageKey> = {
  function: 'builder.note.function', arithmetic: 'builder.note.arithmetic', between: 'builder.note.between', inputs: 'builder.note.inputs',
  unknown: 'builder.note.unknown', depth: 'builder.note.depth', syntax: 'builder.note.syntax', literal: 'builder.note.literal', type: 'builder.note.type',
};

type Reading = { ok: true; root: Group } | { ok: false; reason: Unsupported };

function parentOf(root: Node, id: string): Group | undefined {
  if (root.kind === 'group') {
    if (root.children.some((child) => child.id === id)) return root;
    return root.children.map((child) => parentOf(child, id)).find((found) => found !== undefined);
  }
  return root.kind === 'not' ? parentOf(root.child, id) : undefined;
}

interface Context {
  t: Translate;
  words: Words;
  inputs: readonly InputClause[];
  kinds: ReadonlyMap<string, AttributeKind | undefined>;
  currencyOf: (input: string) => string;
  root: Group;
  placed: ReadonlyMap<string, string[]>;
  change: (next: Node, commit: boolean, focus?: string) => void;
  commit: () => void;
}

/** The sentence of one node, for the name of its row and of its menu. */
const nodeName = (node: Node, ctx: Context): string => readWords(node, ctx.words);

const OP_LABEL = (key: OpKey, kind: AttributeKind, t: Translate): string => {
  if (kind === 'number' || kind === 'money') {
    const symbols: Partial<Record<OpKey, string>> = { eq: '=', ne: '≠', lt: '<', le: '≤', gt: '>', ge: '≥' };
    const symbol = symbols[key];
    if (symbol !== undefined) return symbol;
  }
  switch (key) {
    case 'eq': return t('builder.op.is');
    case 'ne': return t('builder.op.isNot');
    case 'in': return t('builder.op.oneOf');
    case 'notin': return t('builder.op.noneOf');
    case 'null': return t('builder.op.empty');
    case 'notnull': return t('builder.op.notEmpty');
    default: return key;
  }
};

function Messages({ ids, ctx, node }: { ids: string[] | undefined; ctx: Context; node: Node }) {
  const own = ids ?? [];
  const waiting = node.kind !== 'group' && node.kind !== 'not' && incomplete(node) ? ctx.t(node.kind === 'list' ? 'builder.chooseValues' : 'builder.incomplete') : undefined;
  if (own.length === 0 && waiting === undefined) return null;
  return (
    <>
      {own.map((message, index) => <p key={index} className="cb-msg cb-msg--bad" id={`cb-${node.id}-err-${String(index)}`}><span aria-hidden="true">✕</span> {message}</p>)}
      {own.length === 0 && waiting !== undefined ? <p className="cb-msg">{waiting}</p> : null}
    </>
  );
}

function ValueEditor({ leaf, kind, ctx }: { leaf: Leaf; kind: AttributeKind; ctx: Context }) {
  const { t, locale } = useI18n();
  const input = ctx.inputs.find((candidate) => candidate.name === leaf.input);
  const options = (input?.allowed ?? []).map((value) => ({ raw: value, text: String(value) }));
  const toLiteral = (text: string): Literal => {
    const original = options.find((option) => option.text === text)?.raw;
    return typeof original === 'number' ? { type: 'number', text } : { type: 'string', text };
  };
  const set = (next: Leaf, commit: boolean) => { ctx.change(replace(ctx.root, next), commit); };
  if (leaf.kind === 'empty') return null;
  if (leaf.kind === 'list') {
    return (
      <fieldset className="cb-values" aria-label={t('builder.values')}>
        {options.map((option) => {
          const on = leaf.values.some((value) => value.type !== 'bool' && value.text === option.text);
          return (
            <label key={option.text} className="cb-check">
              <input type="checkbox" checked={on}
                onChange={() => {
                  const kept = options.filter((o) => (o.text === option.text ? !on : leaf.values.some((value) => value.type !== 'bool' && value.text === o.text)));
                  set({ ...leaf, values: kept.map((o) => toLiteral(o.text)) }, true);
                }} />
              {valueLabel(option.text)}
            </label>
          );
        })}
      </fieldset>
    );
  }
  const value = leaf.value;
  if (kind === 'choice') {
    return (
      <select className="cb-select" aria-label={t('builder.value')} value={value.type === 'bool' ? '' : value.type === 'money' ? value.text : value.text}
        onChange={(event) => { set({ ...leaf, value: toLiteral(event.target.value) }, true); }}>
        <option value="">—</option>
        {options.map((option) => <option key={option.text} value={option.text}>{valueLabel(option.text)}</option>)}
      </select>
    );
  }
  if (kind === 'bool') {
    return (
      <select className="cb-select" aria-label={t('builder.value')} value={value.type === 'bool' && !value.value ? 'no' : 'yes'}
        onChange={(event) => { set({ ...leaf, value: { type: 'bool', value: event.target.value === 'yes' } }, true); }}>
        <option value="yes">{t('table.yes')}</option>
        <option value="no">{t('table.no')}</option>
      </select>
    );
  }
  if (kind === 'string') {
    return (
      <input className="cell-input cb-text" aria-label={t('builder.value')} spellCheck={false} value={value.type === 'string' ? value.text : ''}
        aria-invalid={incomplete(leaf) ? undefined : (ctx.placed.get(leaf.id)?.length ?? 0) > 0}
        onChange={(event) => { set({ ...leaf, value: { type: 'string', text: event.target.value } }, false); }} onBlur={ctx.commit} />
    );
  }
  const money = kind === 'money';
  const text = value.type === 'number' || value.type === 'money' ? value.text : '';
  const currency = value.type === 'money' && value.currency !== '' ? value.currency : ctx.currencyOf(leaf.input);
  const bad = (ctx.placed.get(leaf.id)?.length ?? 0) > 0;
  return (
    <span className="cb-amount">
      <label className={money && currency !== '' ? 'amount amount--money' : 'amount'}>
        <input className="amount__input tnum" inputMode="decimal" aria-label={t('builder.value')} aria-invalid={bad ? true : undefined}
          value={shownAmount(text, locale)}
          onChange={(event) => {
            const next = canonicalAmount(event.target.value, locale);
            set({ ...leaf, value: money ? { type: 'money', text: next, currency } : { type: 'number', text: next } }, false);
          }}
          onBlur={ctx.commit} />
        {money && currency !== '' ? <span className="amount__code" aria-hidden="true">{currency}</span> : null}
      </label>
      {money && currency === '' ? (
        <input className="cell-input cb-currency" aria-label={t('builder.currency')} maxLength={3} placeholder="VND" spellCheck={false} value=""
          onChange={(event) => { set({ ...leaf, value: { type: 'money', text, currency: event.target.value.toUpperCase().replace(/[^A-Z]/g, '') } }, false); }} onBlur={ctx.commit} />
      ) : null}
    </span>
  );
}

/** The tree with one row replaced by an edited copy of itself. */
const replace = (root: Node, next: Leaf): Node => edit(root, next.id, () => next);

function NodeMenu({ node, ctx, siblings, index }: { node: Node; ctx: Context; siblings: number; index: number }) {
  const { t } = ctx;
  const isRoot = node.id === ctx.root.id;
  if (isRoot) return null;
  const canWrap = depthOf(ctx.root) < MAX_DEPTH;
  const parent = parentOf(ctx.root, node.id);
  return (
    <MenuButton
      label={t('builder.nodeActions', { name: nodeName(node, ctx) })}
      tooltip={t('builder.nodeActions', { name: nodeName(node, ctx) })}
      className="iconbtn iconbtn--small hit"
      trigger={<span aria-hidden="true">⋯</span>}
      items={[
        ...(node.kind === 'not'
          ? [{ label: t('builder.unwrapNot'), onSelect: () => { ctx.change(unwrapNot(ctx.root, node.id), true); } }]
          : [{ label: t('builder.wrapNot'), onSelect: () => { ctx.change(wrapInNot(ctx.root, node.id), true); }, disabled: !canWrap }]),
        { label: t('builder.moveUp'), onSelect: () => { ctx.change(moveNode(ctx.root, node.id, -1), true); }, disabled: index === 0 },
        { label: t('builder.moveDown'), onSelect: () => { ctx.change(moveNode(ctx.root, node.id, 1), true); }, disabled: index === siblings - 1 },
        { label: t('builder.remove'), danger: true, onSelect: () => { ctx.change(removeNode(ctx.root, node.id), true, parent !== undefined ? `add:${parent.id}` : undefined); } },
      ]}
    />
  );
}

function LeafRow({ leaf, ctx, siblings, index }: { leaf: Leaf; ctx: Context; siblings: number; index: number }) {
  const { t, locale } = useI18n();
  const kind = ctx.kinds.get(leaf.input) ?? 'string';
  const messages = ctx.placed.get(leaf.id);
  const bad = (messages?.length ?? 0) > 0;
  const options = buildable(ctx.inputs);
  return (
    <li>
      <div className={bad ? 'cb-leaf cb-leaf--bad' : 'cb-leaf'} role="group" aria-label={nodeName(leaf, ctx)} data-node={leaf.id}
        {...(bad ? { 'aria-describedby': `cb-${leaf.id}-err-0` } : {})}>
        <select className="cb-select cb-attribute" aria-label={t('builder.attribute')} value={leaf.input}
          onChange={(event) => {
            const chosen = ctx.inputs.find((input) => input.name === event.target.value);
            if (chosen) ctx.change(replace(ctx.root, withInput(leaf, chosen, kind, ctx.currencyOf(chosen.name))), true);
          }}>
          {options.map((input) => <option key={input.name} value={input.name}>{clauseLabel(input, locale)}</option>)}
        </select>
        <select className="cb-select cb-op" aria-label={t('builder.operator')} value={opKeyOf(leaf)}
          onChange={(event) => { ctx.change(replace(ctx.root, withOp(leaf, event.target.value as OpKey, kind, ctx.currencyOf(leaf.input))), true); }}>
          {opsFor(kind).map((key) => <option key={key} value={key}>{OP_LABEL(key, kind, t)}</option>)}
        </select>
        <ValueEditor leaf={leaf} kind={kind} ctx={ctx} />
        <NodeMenu node={leaf} ctx={ctx} siblings={siblings} index={index} />
      </div>
      <Messages ids={messages} ctx={ctx} node={leaf} />
    </li>
  );
}

function Children({ nodes, ctx }: { nodes: readonly Node[]; ctx: Context }) {
  return (
    <ul className="cb-children">
      {nodes.map((child, index) => <NodeView key={child.id} node={child} ctx={ctx} siblings={nodes.length} index={index} />)}
    </ul>
  );
}

function GroupCard({ group, ctx, siblings, index }: { group: Group; ctx: Context; siblings: number; index: number }) {
  const { t } = ctx;
  const level = levelOf(ctx.root, group.id);
  const root = group.id === ctx.root.id;
  const room = level < MAX_DEPTH;
  const name = group.op === 'and' ? t('builder.and') : t('builder.or');
  const messages = ctx.placed.get(group.id);
  const card = (
    <div className={`cb-group cb-group--${group.op}${root ? ' cb-group--root' : ''}${(messages?.length ?? 0) > 0 ? ' cb-group--bad' : ''}`} role="group"
      aria-label={t('builder.groupLabel', { op: name, level: String(level) })} data-node={group.id}>
      <div className="cb-group__head">
        <span className="seg seg--small" role="group" aria-label={t('builder.groupKind')}>
          <button type="button" className="hit" aria-pressed={group.op === 'and'} onClick={() => { ctx.change(edit(ctx.root, group.id, (node) => (node.kind === 'group' ? { ...node, op: 'and' } : node)), true); }}>{t('builder.and')}</button>
          <button type="button" className="hit" aria-pressed={group.op === 'or'} onClick={() => { ctx.change(edit(ctx.root, group.id, (node) => (node.kind === 'group' ? { ...node, op: 'or' } : node)), true); }}>{t('builder.or')}</button>
        </span>
        <span className="cb-hint">{group.op === 'and' ? t('builder.andHint') : t('builder.orHint')}</span>
        <span className="cb-group__actions">
          <button type="button" className="btn btn--small" data-add={group.id}
            onClick={() => {
              const input = ctx.inputs.find((candidate) => attributeKind(candidate) !== undefined) as InputClause;
              const leaf = newLeaf(input, ctx.currencyOf(input.name));
              ctx.change(addChild(ctx.root, group.id, leaf), true, leaf.id);
            }}>
            <span aria-hidden="true">＋</span> {t('builder.addCondition')}
          </button>
          <MenuButton
            label={t('builder.addGroup')}
            className="btn btn--small"
            trigger={<><span aria-hidden="true">＋</span> {t('builder.addGroup')} <span aria-hidden="true">⌄</span></>}
            items={[
              { label: t('builder.addAnd'), disabled: !room, onSelect: () => { addGroup(ctx, group.id, 'and'); } },
              { label: t('builder.addOr'), disabled: !room, onSelect: () => { addGroup(ctx, group.id, 'or'); } },
              { label: t('builder.addNot'), disabled: !room, onSelect: () => { addNot(ctx, group.id); } },
            ]}
          />
          <NodeMenu node={group} ctx={ctx} siblings={siblings} index={index} />
        </span>
      </div>
      <Messages ids={messages} ctx={ctx} node={group} />
      {group.children.length === 0 ? <p className="cb-empty">{t('builder.empty')}</p> : <Children nodes={group.children} ctx={ctx} />}
      {!room ? <p className="cb-msg">{t('builder.depthCap', { max: String(MAX_DEPTH) })}</p> : null}
    </div>
  );
  return root ? card : <li>{card}</li>;
}

function addGroup(ctx: Context, parent: string, op: 'and' | 'or'): void {
  const input = ctx.inputs.find((candidate) => attributeKind(candidate) !== undefined) as InputClause;
  const leaf = newLeaf(input, ctx.currencyOf(input.name));
  ctx.change(addChild(ctx.root, parent, { kind: 'group', id: newId(), op, children: [leaf] }), true, leaf.id);
}

function addNot(ctx: Context, parent: string): void {
  const input = ctx.inputs.find((candidate) => attributeKind(candidate) !== undefined) as InputClause;
  const leaf = newLeaf(input, ctx.currencyOf(input.name));
  ctx.change(addChild(ctx.root, parent, { kind: 'not', id: newId(), child: leaf }), true, leaf.id);
}

function NotCard({ node, ctx, siblings, index }: { node: Extract<Node, { kind: 'not' }>; ctx: Context; siblings: number; index: number }) {
  const { t } = ctx;
  const level = levelOf(ctx.root, node.id);
  const messages = ctx.placed.get(node.id);
  return (
    <li>
      <div className={`cb-not${(messages?.length ?? 0) > 0 ? ' cb-not--bad' : ''}`} role="group" aria-label={t('builder.notLabel', { level: String(level) })} data-node={node.id}>
        <div className="cb-not__head">
          <span className="cb-badge">{t('builder.not')}</span>
          <span className="cb-hint">{t('builder.notHint')}</span>
          <span className="cb-group__actions"><NodeMenu node={node} ctx={ctx} siblings={siblings} index={index} /></span>
        </div>
        <Messages ids={messages} ctx={ctx} node={node} />
        <Children nodes={[node.child]} ctx={ctx} />
      </div>
    </li>
  );
}

function NodeView({ node, ctx, siblings, index }: { node: Node; ctx: Context; siblings: number; index: number }) {
  if (node.kind === 'group') return <GroupCard group={node} ctx={ctx} siblings={siblings} index={index} />;
  if (node.kind === 'not') return <NotCard node={node} ctx={ctx} siblings={siblings} index={index} />;
  return <LeafRow leaf={node} ctx={ctx} siblings={siblings} index={index} />;
}

export function ConditionBuilder({ cell, column, definition, text, checked, problems, onText, onCommit, onClose }: ConditionBuilderProps) {
  const { t, locale } = useI18n();
  const heading = useId();
  const panel = useRef<HTMLElement>(null);
  const [state, setState] = useState<Reading>(() => fromText(text, column.name, definition.inputs));
  // The text this builder wrote last: a different text from outside (Esc on the cell, a reload) is read again.
  const written = useRef(text);
  const focusAfter = useRef<string | undefined>(undefined);

  useEffect(() => {
    if (text === written.current) return;
    written.current = text;
    setState(fromText(text, column.name, definition.inputs));
  }, [text, column.name, definition.inputs]);

  useEffect(() => {
    const target = focusAfter.current;
    if (target === undefined) return;
    focusAfter.current = undefined;
    const element = target.startsWith('add:')
      ? panel.current?.querySelector<HTMLElement>(`[data-add="${target.slice(4)}"]`)
      : panel.current?.querySelector<HTMLElement>(`[data-node="${target}"] select, [data-node="${target}"] input`);
    element?.focus();
  });

  useEffect(() => { panel.current?.querySelector<HTMLElement>('select, input, button')?.focus(); }, []);

  const words = useMemo<Words>(() => ({
    locale,
    label: (name) => { const input = definition.inputs.find((candidate) => candidate.name === name); return input ? clauseLabel(input, locale) : name; },
    t,
  }), [locale, definition.inputs, t]);

  const root = state.ok ? state.root : undefined;
  const output = useMemo(() => (root ? toText(root, column.name) : undefined), [root, column.name]);

  const placed = useMemo(() => {
    const map = new Map<string, string[]>();
    const loose: string[] = [];
    if (root === undefined || output === undefined) return { map, loose };
    for (const problem of problems) {
      const id = output.text === checked ? locate(output, root, problem.position) : undefined;
      if (id === undefined) { loose.push(problem.message); continue; }
      map.set(id, [...(map.get(id) ?? []), problem.message]);
    }
    return { map, loose };
  }, [root, output, problems, checked]);

  const change = (next: Node, commit: boolean, focus?: string) => {
    const group = next as Group;
    const result = toText(group, column.name);
    written.current = result.text;
    focusAfter.current = focus;
    setState({ ok: true, root: group });
    onText(result.text);
    if (commit) onCommit();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    // Keys belong to the builder: the grid around it must not move the focus or commit on them.
    event.stopPropagation();
    if (event.key === 'Escape' && !event.defaultPrevented) { event.preventDefault(); onClose(); }
    if (event.key === 'Enter' && event.target instanceof HTMLInputElement && event.target.type !== 'checkbox') { event.preventDefault(); onCommit(); }
  };

  const currencyOf = (name: string): string => columnCurrency(definition, 'when', name) ?? '';
  const kinds = useMemo(() => new Map(definition.inputs.map((input) => [input.name, attributeKind(input)])), [definition.inputs]);
  const available = buildable(definition.inputs);

  let body: ReactNode;
  if (!state.ok) {
    body = (
      <div className="notice cb-note" role="status">
        <p className="notice__title">{t('builder.stays')}</p>
        <p className="notice__detail">{t(NOTE_KEY[state.reason], { max: String(MAX_DEPTH) })}</p>
      </div>
    );
  } else if (available.length === 0) {
    body = <div className="notice cb-note" role="status"><p className="notice__detail">{t(NOTE_KEY.type)}</p></div>;
  } else if (root !== undefined) {
    const ctx: Context = { t, words, inputs: definition.inputs, kinds, currencyOf, root, placed: placed.map, change, commit: onCommit };
    body = (
      <>
        <p className="cb-reading" aria-live="polite"><span className="cb-reading__label">{t('builder.reading')}</span> {readWords(root, words)}</p>
        <GroupCard group={root} ctx={ctx} siblings={1} index={0} />
      </>
    );
  }

  return (
    <section ref={panel} className="cb" aria-labelledby={heading} onKeyDown={onKeyDown}>
      <div className="cb__head">
        <h3 id={heading} className="cb__title">{t('builder.title', { cell })}</h3>
        <button type="button" className="btn btn--primary btn--small" onClick={onClose}>{t('builder.close')}</button>
      </div>
      {body}
      {placed.loose.length > 0 ? (
        <ul className="cb-loose" aria-label={t('builder.problems')}>
          {placed.loose.map((message, index) => <li key={index} className="cb-msg cb-msg--bad"><span aria-hidden="true">✕</span> {message}</li>)}
        </ul>
      ) : null}
      {state.ok && output !== undefined && output.text !== '' ? (
        <details className="cb-details">
          <summary>{t('builder.details')}</summary>
          <p className="cb-details__row"><span className="cb-reading__label">{t('builder.expression')}</span> <code className="mono">{output.text}</code></p>
        </details>
      ) : null}
      <p className="cb-keys">{t('builder.keys')}</p>
    </section>
  );
}
