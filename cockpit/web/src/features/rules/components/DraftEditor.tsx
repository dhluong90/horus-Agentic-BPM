import { useRef, type Dispatch, type FocusEvent, type KeyboardEvent } from 'react';
import type { InputClause, OutputClause, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { columnCurrency, inputKind, outputKind, readConditionCell, readOutputCell } from '../model/cells';
import type { DraftAction, DraftState } from '../model/draft';
import type { ErrorPlace } from '../model/engineErrors';
import { columnLabel } from '../model/reading';
import type { EditableRow } from '../model/table';
import { BoolToggle, ChoiceEditor, CompareEditor, MoneyEditor, TextEditor } from './CellEditors';
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

function ConditionCell({ row, input, definition, error, dispatch }: { row: EditableRow; input: InputClause; definition: RuleSetDefinition; error: string | undefined; dispatch: Dispatch<DraftAction> }) {
  const { t } = useI18n();
  const label = t('editor.cellLabel', { row: row.id, column: columnLabel(input.name, input.description) });
  const errorId = `err-${row.key}-${input.name}`;
  const kind = inputKind(input);
  const text = row.when[input.name] ?? '';
  const model = readConditionCell(text, kind);
  const onText = (next: string) => { dispatch({ type: 'edit-cell', key: row.key, part: 'when', column: input.name, text: next }); };
  const common = { label, error, errorId };
  if (model.kind === 'raw' || kind === 'raw' || kind === 'text') return <TextEditor {...common} text={text} mono onText={onText} />;
  if (kind === 'choice') return <ChoiceEditor {...common} model={model} options={(input.allowed ?? []).map(String)} onText={onText} />;
  if (kind === 'bool') return <BoolToggle {...common} value={model.kind === 'bool' ? model.value : undefined} condition onText={onText} />;
  return <CompareEditor {...common} model={model} currency={kind === 'money' ? columnCurrency(definition, 'when', input.name) : undefined} onText={onText} />;
}

function OutputCell({ row, output, definition, error, dispatch }: { row: EditableRow; output: OutputClause; definition: RuleSetDefinition; error: string | undefined; dispatch: Dispatch<DraftAction> }) {
  const { t } = useI18n();
  const label = t('editor.cellLabel', { row: row.id, column: columnLabel(output.name, output.description) });
  const errorId = `err-${row.key}-${output.name}`;
  const kind = outputKind(output);
  const text = row.then[output.name] ?? '';
  const model = readOutputCell(text, kind, kind === 'money' ? columnCurrency(definition, 'then', output.name) : undefined);
  const onText = (next: string) => { dispatch({ type: 'edit-cell', key: row.key, part: 'then', column: output.name, text: next }); };
  const common = { label, error, errorId };
  if (model.kind === 'money') return <MoneyEditor {...common} model={model} onText={onText} />;
  if (kind === 'bool' && (model.kind === 'bool' || model.kind === 'any')) return <BoolToggle {...common} value={model.kind === 'bool' ? model.value : undefined} condition={false} onText={onText} />;
  if (model.kind === 'text') return <TextEditor {...common} text={model.text} mono={false} onText={(value) => { onText(value === '' ? '' : `'${value.replaceAll("'", "\\'")}'`); }} />;
  return <TextEditor {...common} text={text} mono onText={onText} />;
}

function RowMenu({ row, index, count, dispatch }: { row: EditableRow; index: number; count: number; dispatch: Dispatch<DraftAction> }) {
  const { t } = useI18n();
  return (
    <MenuButton
      label={t('editor.rowActions', { id: row.id })}
      className="iconbtn iconbtn--small hit"
      trigger={<span aria-hidden="true">⋯</span>}
      items={[
        { label: t('editor.moveUp', { id: row.id }), onSelect: () => { dispatch({ type: 'move', key: row.key, by: -1 }); }, disabled: index === 0 },
        { label: t('editor.moveDown', { id: row.id }), onSelect: () => { dispatch({ type: 'move', key: row.key, by: 1 }); }, disabled: index === count - 1 },
        { label: t('editor.copy', { id: row.id }), onSelect: () => { dispatch({ type: 'copy', key: row.key }); } },
        { label: t('editor.delete', { id: row.id }), onSelect: () => { dispatch({ type: 'delete', key: row.key }); }, disabled: count === 1, danger: true },
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
export function DraftEditor({ definition, draft, dispatch, errors }: {
  definition: RuleSetDefinition;
  draft: DraftState;
  dispatch: Dispatch<DraftAction>;
  errors: readonly ErrorPlace[];
}) {
  const { t } = useI18n();
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
            return (
              <tr key={row.key} className={[row.original === undefined ? 'dt__row--new' : '', rowError !== undefined ? 'dt__row--bad' : ''].join(' ').trim() || undefined}>
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
                        <ConditionCell row={row} input={input} definition={definition} error={error} dispatch={dispatch} />
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
                  <RowMenu row={row} index={index} count={draft.rows.length} dispatch={dispatch} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="editor-foot">
        <button type="button" className="btn" onClick={() => { dispatch({ type: 'add' }); }}>
          <span aria-hidden="true">＋</span> {t('editor.addRow')}
        </button>
        <p id="grid-keys" className="editor-foot__keys">{t('editor.keys')}</p>
      </div>
    </div>
  );
}
