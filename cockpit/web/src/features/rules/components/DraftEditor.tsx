import { Fragment, useEffect, useRef, useState, type Dispatch, type FocusEvent, type KeyboardEvent } from 'react';
import type { InputClause, OutputClause, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n, type Translate } from '../../../i18n/I18nProvider';
import { emptyAmountHint } from '../model/cellMessages';
import { columnCurrency, inputKind, outputKind, readConditionCell, readOutputCell, valueLabel, type CellModel } from '../model/cells';
import type { DraftAction, DraftState } from '../model/draft';
import type { ErrorPlace } from '../model/engineErrors';
import { clauseLabel, readCondition, readOutput, type CellReading } from '../model/reading';
import type { EditableRow } from '../model/table';
import { fromText, isCompound, readWords, type Words } from '../model/condition';
import { BoolToggle, ChoiceEditor, CompareEditor, MoneyEditor, TextEditor } from './CellEditors';
import { ConditionBuilder } from './ConditionBuilder';
import { TableHead } from './DecisionTable';
import { MenuButton } from './MenuButton';
import { cellMove, nextFocus } from '../model/gridKeys';

function messagesFor(errors: readonly ErrorPlace[], row: EditableRow, column: string | undefined): string[] {
  return errors.flatMap((e) => {
    if (column === undefined) return e.kind === 'row' && e.ruleId === row.id ? [e.message] : [];
    return e.kind === 'cell' && e.ruleId === row.id && e.column === column ? [e.message] : [];
  });
}

/** The first problem of a cell or row; the count of the rest is in the save dock. */
const first = (messages: string[]): string | undefined => messages[0];

/** The amount of a saved cell as an example ("50,000,000 VND"), when the cell had one. */
function exampleOf(reading: CellReading): string | undefined {
  const value = reading.kind === 'compare' || reading.kind === 'value' ? reading.value : undefined;
  return value?.kind === 'text' && /\d/.test(value.text) ? value.text : undefined;
}

/**
 * What a refused cell says: what to type when its amount field is empty, with the engine's sentence as the detail;
 * otherwise the engine's sentence alone. The engine's SYNTAX_ERROR sentence is generic, so the hint says what to do.
 */
function cellProblem(engine: string | undefined, model: CellModel, example: string | undefined, currency: string | undefined, t: Translate): { error: string | undefined; detail: string | undefined } {
  if (engine === undefined) return { error: undefined, detail: undefined };
  const hint = emptyAmountHint(model, example, currency);
  return hint === undefined ? { error: engine, detail: undefined } : { error: t(hint.key, hint.params), detail: engine };
}

/** What a condition cell shows when the builder is on: its own editor, and the way into the builder. */
interface BuilderCell { open: boolean; onToggle: () => void }

/** A nested condition in plain words, as the button that opens the builder; the stored text stays under Details. */
function NestedSummary({ words, root, label, error, errorId, builder, buildKey }: { words: Words; root: Parameters<typeof readWords>[0]; label: string; error: string | undefined; errorId: string; builder: BuilderCell; buildKey: string }) {
  const { t } = useI18n();
  const reading = readWords(root, words);
  return (
    <div className={error !== undefined ? 'cell-edit cell-edit--bad' : 'cell-edit'}>
      <button type="button" className="cb-summary" data-build={buildKey} aria-expanded={builder.open} aria-label={t('builder.summaryFor', { cell: label, reading })}
        {...(error !== undefined ? { 'aria-describedby': errorId } : {})} onClick={builder.onToggle}>
        <span className="cb-summary__text">{reading}</span>
        <span aria-hidden="true" className="cb-summary__chevron">⌄</span>
      </button>
      {error !== undefined ? <span id={errorId} className="cell-error"><span aria-hidden="true">✕</span> {error}</span> : null}
    </div>
  );
}

function ConditionCell({ row, input, definition, error: engine, dispatch, builder }: { row: EditableRow; input: InputClause; definition: RuleSetDefinition; error: string | undefined; dispatch: Dispatch<DraftAction>; builder: BuilderCell | undefined }) {
  const { t, locale } = useI18n();
  const column = clauseLabel(input, locale);
  const label = t('editor.cellLabel', { row: row.id, column });
  const errorId = `err-${row.key}-${input.name}`;
  const kind = inputKind(input);
  const text = row.when[input.name] ?? '';
  const model = readConditionCell(text, kind);
  const currency = kind === 'money' ? columnCurrency(definition, 'when', input.name) : undefined;
  const allowed = (input.allowed ?? []).map(String);
  const outside = kind === 'choice' && model.kind === 'choice' && model.values.some((v) => !allowed.includes(v));
  const choose = outside ? t('editor.chooseFrom', { values: new Intl.ListFormat(locale, { type: 'disjunction' }).format(allowed.map(valueLabel)) }) : undefined;
  const { error, detail } = choose !== undefined ? { error: choose, detail: undefined } : cellProblem(engine, model, exampleOf(readCondition(row.original?.when[input.name], locale)), currency, t);
  const onText = (next: string) => { dispatch({ type: 'edit-cell', key: row.key, part: 'when', column: input.name, text: next }); };
  const common = { label, error, detail, errorId, column };
  const buildKey = `${row.key}|${input.name}`;
  const nested = builder !== undefined ? fromText(text, input.name, definition.inputs) : undefined;
  if (builder !== undefined && nested?.ok === true && isCompound(nested.root)) {
    const words: Words = { locale, t, label: (name) => { const found = definition.inputs.find((candidate) => candidate.name === name); return found ? clauseLabel(found, locale) : name; } };
    return <NestedSummary words={words} root={nested.root} label={label} error={error} errorId={errorId} builder={builder} buildKey={buildKey} />;
  }
  let editor;
  if (model.kind === 'raw' || kind === 'raw' || kind === 'text') editor = <TextEditor {...common} text={text} mono onText={onText} />;
  else if (kind === 'choice') editor = <ChoiceEditor {...common} model={model} options={(input.allowed ?? []).map(String)} onText={onText} />;
  else if (kind === 'bool') editor = <BoolToggle {...common} value={model.kind === 'bool' ? model.value : undefined} condition onText={onText} />;
  else editor = <CompareEditor {...common} model={model} currency={currency} onText={onText} />;
  if (builder === undefined) return editor;
  return (
    <div className="cb-cell">
      {editor}
      <button type="button" className="cb-open hit" data-build={buildKey} aria-expanded={builder.open} aria-label={t('builder.openFor', { cell: label })} title={t('builder.openFor', { cell: label })} onClick={builder.onToggle}>
        {t('builder.open')}
      </button>
    </div>
  );
}

function OutputCell({ row, output, definition, error: engine, dispatch }: { row: EditableRow; output: OutputClause; definition: RuleSetDefinition; error: string | undefined; dispatch: Dispatch<DraftAction> }) {
  const { t, locale } = useI18n();
  const column = clauseLabel(output, locale);
  const label = t('editor.cellLabel', { row: row.id, column });
  const errorId = `err-${row.key}-${output.name}`;
  const kind = outputKind(output);
  const text = row.then[output.name] ?? '';
  const currency = kind === 'money' ? columnCurrency(definition, 'then', output.name) : undefined;
  const model = readOutputCell(text, kind, currency);
  const { error, detail } = cellProblem(engine, model, exampleOf(readOutput(row.original?.then[output.name], locale)), currency, t);
  const onText = (next: string) => { dispatch({ type: 'edit-cell', key: row.key, part: 'then', column: output.name, text: next }); };
  const common = { label, error, detail, errorId, column };
  if (model.kind === 'money') return <MoneyEditor {...common} model={model} onText={onText} />;
  if (kind === 'bool' && (model.kind === 'bool' || model.kind === 'any')) return <BoolToggle {...common} value={model.kind === 'bool' ? model.value : undefined} condition={false} onText={onText} />;
  if (model.kind === 'text') return <TextEditor {...common} text={model.text} mono={false} onText={(value) => { onText(value === '' ? '' : `'${value.replaceAll("'", "\\'")}'`); }} />;
  return <TextEditor {...common} text={text} mono onText={onText} />;
}

function RowMenu({ row, index, count, dispatch, onDelete }: { row: EditableRow; index: number; count: number; dispatch: Dispatch<DraftAction>; onDelete: () => void }) {
  const { t } = useI18n();
  return (
    <MenuButton
      label={t('editor.rowActions', { id: row.id })}
      tooltip={t('editor.rowActions', { id: row.id })}
      className="iconbtn iconbtn--small hit"
      trigger={<span aria-hidden="true">⋯</span>}
      items={[
        { label: t('editor.moveUp', { id: row.id }), onSelect: () => { dispatch({ type: 'move', key: row.key, by: -1 }); }, disabled: index === 0 },
        { label: t('editor.moveDown', { id: row.id }), onSelect: () => { dispatch({ type: 'move', key: row.key, by: 1 }); }, disabled: index === count - 1 },
        { label: t('editor.copy', { id: row.id }), onSelect: () => { dispatch({ type: 'copy', key: row.key }); } },
        { label: t('editor.delete', { id: row.id }), onSelect: onDelete, disabled: count === 1, danger: true },
      ]}
    />
  );
}

/** The value a cell had when it took the focus, so Escape can put it back. */
interface Held { key: string; part: 'when' | 'then'; column: string; text: string }

/**
 * The grid editor of a draft: typed cells, one ⋯ menu per row, and the engine's problems on their cells.
 * Arrow keys move between cells, Enter commits a cell and Escape puts back the value it had. A commit (Enter,
 * leaving a cell, a row action) is what the engine checks; nothing is stored before Save draft.
 */
export function DraftEditor({ definition, draft, dispatch, errors, readings, builder = false }: {
  definition: RuleSetDefinition;
  draft: DraftState;
  dispatch: Dispatch<DraftAction>;
  errors: readonly ErrorPlace[];
  /** The engine's sentence of each row by row id, from the last non-storing check; undefined when that call failed. */
  readings: ReadonlyMap<string, string> | undefined;
  /** The host turns on the nested And/Or/Not condition builder (/meta feature rules.condition-builder). */
  builder?: boolean;
}) {
  const { t } = useI18n();
  const [deleting, setDeleting] = useState<EditableRow | undefined>(undefined);
  // The cell whose condition builder is open, one at a time; closing returns the focus to the button that opened it.
  const [building, setBuilding] = useState<{ key: string; column: string } | undefined>(undefined);
  const opener = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (building !== undefined || opener.current === undefined) return;
    const key = opener.current;
    opener.current = undefined;
    document.querySelector<HTMLElement>(`[data-build="${key}"]`)?.focus();
  }, [building]);
  const columns = definition.inputs.length + definition.outputs.length + 2;
  const held = useRef<Held | undefined>(undefined);
  const cellOf = (target: EventTarget | null) => (target instanceof HTMLElement ? target.closest<HTMLTableCellElement>('td[data-cell]') : null);
  const rowText = (key: string, part: 'when' | 'then', column: string) => draft.rows.find((r) => r.key === key)?.[part][column] ?? '';
  const onFocus = (event: FocusEvent<HTMLTableElement>) => {
    const cell = cellOf(event.target);
    const [key = '', part, column = ''] = (cell?.dataset['cell'] ?? '').split('|');
    if (cell && (part === 'when' || part === 'then') && (held.current?.key !== key || held.current.part !== part || held.current.column !== column)) {
      held.current = { key, part, column, text: rowText(key, part, column) };
    }
  };
  const onBlur = (event: FocusEvent<HTMLTableElement>) => {
    const from = cellOf(event.target);
    if (from && cellOf(event.relatedTarget) !== from) {
      held.current = undefined;
      dispatch({ type: 'commit' });
    }
  };
  const onKeyDown = (event: KeyboardEvent<HTMLTableElement>) => {
    if (event.defaultPrevented || !(event.target instanceof HTMLElement)) return;
    const target = event.target;
    if (event.key === 'Enter' && target instanceof HTMLInputElement && target.type !== 'checkbox') {
      event.preventDefault();
      dispatch({ type: 'commit' });
      return;
    }
    if (event.key === 'Escape' && held.current !== undefined && !target.closest('.picker__list')) {
      const { key, part, column, text } = held.current;
      event.preventDefault();
      dispatch({ type: 'edit-cell', key, part, column, text });
      dispatch({ type: 'commit' });
      return;
    }
    const move = cellMove(event.key, target, event.altKey || event.ctrlKey || event.metaKey || event.shiftKey);
    if (move === undefined) return;
    const next = nextFocus(event.currentTarget, target, move);
    if (next) {
      event.preventDefault();
      next.focus();
    }
  };
  return (
    <div className="dt-scroll" role="region" tabIndex={0} aria-label={t('editor.tableLabel')}>
      <table className="dt dt--edit" aria-describedby="grid-keys" onFocus={onFocus} onBlur={onBlur} onKeyDown={onKeyDown}>
        <TableHead definition={definition} extra={t('editor.actions')} />
        <tbody>
          {draft.rows.map((row, index) => {
            const rowError = first(messagesFor(errors, row, undefined));
            const sentence = readings?.get(row.id);
            const bad = rowError !== undefined || errors.some((e) => e.kind === 'cell' && e.ruleId === row.id);
            return (
              <Fragment key={row.key}>
              <tr className={[row.original === undefined ? 'dt__row--new' : '', rowError !== undefined ? 'dt__row--bad' : ''].join(' ').trim() || undefined}>
                <th scope="row" className="dt__rowhead">
                  <span className="row-id">{row.id}</span>
                  {row.original === undefined ? <span className="change-mark change-mark--added"><span aria-hidden="true">+</span> {t('editor.newRow')}</span> : null}
                  {rowError !== undefined ? <span className="cell-error"><span aria-hidden="true">✕</span> {rowError}</span> : null}
                </th>
                {definition.inputs.map((input) => {
                  const error = first(messagesFor(errors, row, input.name));
                  return (
                    <td key={input.name} data-cell={`${row.key}|when|${input.name}`}>
                      {row.groups[input.name] !== undefined ? (
                        <>
                          <span className="cell-raw">{t('table.group')}</span>
                          {error !== undefined ? <span className="cell-error"><span aria-hidden="true">✕</span> {error}</span> : null}
                        </>
                      ) : (
                        <ConditionCell row={row} input={input} definition={definition} error={error} dispatch={dispatch}
                          builder={builder ? { open: building?.key === row.key && building.column === input.name, onToggle: () => {
                            const open = building?.key === row.key && building.column === input.name;
                            opener.current = `${row.key}|${input.name}`;
                            setBuilding(open ? undefined : { key: row.key, column: input.name });
                          } } : undefined} />
                      )}
                    </td>
                  );
                })}
                {definition.outputs.map((output, outputIndex) => (
                  <td key={output.name} data-cell={`${row.key}|then|${output.name}`} className={outputIndex === 0 ? 'dt__first-then' : undefined}>
                    <OutputCell row={row} output={output} definition={definition} error={first(messagesFor(errors, row, output.name))} dispatch={dispatch} />
                  </td>
                ))}
                <td className="dt__actions">
                  <RowMenu row={row} index={index} count={draft.rows.length} dispatch={dispatch} onDelete={() => { setDeleting(row); }} />
                </td>
              </tr>
              {builder && building?.key === row.key ? (
                <tr className="dt__builder-row">
                  <td colSpan={columns}>
                    <BuilderPanel row={row} column={building.column} definition={definition} draft={draft} errors={errors} dispatch={dispatch}
                      onClose={() => { opener.current = `${row.key}|${building.column}`; setBuilding(undefined); }} />
                  </td>
                </tr>
              ) : null}
              {!bad && sentence !== undefined ? (
                <tr className="dt__reading-row">
                  <td colSpan={columns} className="dt__reading">
                    <span className="reading-line" aria-label={t('editor.readingOf', { id: row.id })}>{sentence}</span>
                  </td>
                </tr>
              ) : null}
              </Fragment>
            );
          })}
        </tbody>
      </table>
      {deleting !== undefined ? (
        <DeleteRowDialog row={deleting} onCancel={() => { setDeleting(undefined); }}
          onConfirm={() => { dispatch({ type: 'delete', key: deleting.key }); setDeleting(undefined); }} />
      ) : null}
      <div className="editor-foot">
        <button type="button" className="btn" onClick={() => { dispatch({ type: 'add' }); }}>
          <span aria-hidden="true">＋</span> {t('editor.addRow')}
        </button>
        <p id="grid-keys" className="editor-foot__keys">{t('editor.keys')}</p>
        {readings === undefined ? <p className="editor-foot__keys" role="status">{t('editor.readingUnavailable')}</p> : null}
      </div>
    </div>
  );
}

/** The builder of one cell, fed by the draft: its text, what the engine last checked, and the engine's problems for the cell. */
function BuilderPanel({ row, column, definition, draft, errors, dispatch, onClose }: { row: EditableRow; column: string; definition: RuleSetDefinition; draft: DraftState; errors: readonly ErrorPlace[]; dispatch: Dispatch<DraftAction>; onClose: () => void }) {
  const { t, locale } = useI18n();
  const input = definition.inputs.find((candidate) => candidate.name === column);
  if (input === undefined) return null;
  const problems = errors.flatMap((e) => (e.kind === 'cell' && e.ruleId === row.id && e.column === column ? [{ message: e.message, position: e.position }] : []));
  return (
    <ConditionBuilder
      cell={t('editor.cellLabel', { row: row.id, column: clauseLabel(input, locale) })}
      column={input}
      definition={definition}
      text={row.when[column] ?? ''}
      checked={draft.committed.find((r) => r.key === row.key)?.when[column] ?? ''}
      problems={problems}
      onText={(next) => { dispatch({ type: 'edit-cell', key: row.key, part: 'when', column, text: next }); }}
      onCommit={() => { dispatch({ type: 'commit' }); }}
      onClose={onClose}
    />
  );
}

/** "Delete row R3?" before a row goes; the draft is only changed on Delete, and nothing is stored before Save draft. */
function DeleteRowDialog({ row, onCancel, onConfirm }: { row: EditableRow; onCancel: () => void; onConfirm: () => void }) {
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    if (!element || element.open) return;
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
  }, []);
  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="delete-row-title" onClose={onCancel}>
      <form method="dialog" className="dialog__body" onSubmit={(event) => { event.preventDefault(); onConfirm(); }}>
        <h2 id="delete-row-title" className="dialog__title">{t('editor.deleteTitle', { id: row.id })}</h2>
        <p className="dialog__text">{t('editor.deleteText')}</p>
        <div className="dialog__actions">
          <button type="button" className="btn btn--quiet" onClick={onCancel}>{t('action.cancel')}</button>
          <button type="submit" className="btn btn--danger">{t('editor.deleteConfirm')}</button>
        </div>
      </form>
    </dialog>
  );
}
