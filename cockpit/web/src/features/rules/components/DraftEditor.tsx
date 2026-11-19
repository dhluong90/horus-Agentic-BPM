import type { Dispatch, KeyboardEvent } from 'react';
import type { RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import type { DraftAction, DraftState } from '../model/draft';
import type { ErrorPlace } from '../model/engineErrors';
import { columnLabel } from '../model/reading';
import type { EditableRow } from '../model/table';
import { TableHead } from './DecisionTable';

/** Arrow up and down move between rows in the same column; Enter moves down, like a spreadsheet. */
function moveFocus(event: KeyboardEvent<HTMLTableSectionElement>) {
  const target = event.target;
  if (!(target instanceof HTMLInputElement) || target.dataset['col'] === undefined) return;
  const by = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' || event.key === 'Enter' ? 1 : 0;
  if (by === 0) return;
  const row = Number(target.dataset['row']) + by;
  const next = event.currentTarget.querySelector<HTMLInputElement>(`input[data-row="${row}"][data-col="${target.dataset['col']}"]`);
  if (next) {
    event.preventDefault();
    next.focus();
    next.select();
  }
}

function cellError(errors: readonly ErrorPlace[], row: EditableRow, column: string): string | undefined {
  const messages = errors.flatMap((e) => (e.kind === 'cell' && e.ruleId === row.id && e.column === column ? [e.message] : []));
  return messages.length > 0 ? messages.join(' · ') : undefined;
}

function rowErrorOf(errors: readonly ErrorPlace[], row: EditableRow): string | undefined {
  const messages = errors.flatMap((e) => (e.kind === 'row' && e.ruleId === row.id ? [e.message] : []));
  return messages.length > 0 ? messages.join(' · ') : undefined;
}

/**
 * The engine's sentence is English in this release. On an English screen it shows on the cell; on a
 * Vietnamese screen the cell says so in Vietnamese and the sentence stays in the notice's disclosure.
 */
function useCellMessage(): (message: string | undefined) => string | undefined {
  const { t, locale } = useI18n();
  return (message) => (message === undefined || locale === 'en' ? message : t('editor.cellRefused'));
}

function CellInput({ row, index, part, column, label, error, dispatch }: {
  row: EditableRow;
  index: number;
  part: 'when' | 'then';
  column: string;
  label: string;
  error: string | undefined;
  dispatch: Dispatch<DraftAction>;
}) {
  const { t } = useI18n();
  const errorId = `err-${row.key}-${column}`;
  return (
    <div className={error !== undefined ? 'cell-edit cell-edit--bad' : 'cell-edit'}>
      <input
        className="cell-input mono"
        value={row[part][column] ?? ''}
        title={row[part][column] ?? ''}
        aria-label={t('editor.cellLabel', { row: row.id, column: label })}
        aria-invalid={error !== undefined}
        {...(error !== undefined ? { 'aria-describedby': errorId } : {})}
        data-row={index}
        data-col={`${part}.${column}`}
        spellCheck={false}
        placeholder={part === 'when' ? t('editor.any') : undefined}
        onChange={(event) => {
          dispatch({ type: 'edit-cell', key: row.key, part, column, text: event.target.value });
        }}
      />
      {error !== undefined ? <span id={errorId} className="cell-error">{error}</span> : null}
    </div>
  );
}

function RowActions({ row, index, count, dispatch }: { row: EditableRow; index: number; count: number; dispatch: Dispatch<DraftAction> }) {
  const { t } = useI18n();
  const button = (label: string, glyph: string, action: DraftAction, disabled = false) => (
    <button type="button" className="iconbtn iconbtn--small hit" aria-label={label} title={label} disabled={disabled} onClick={() => { dispatch(action); }}>
      <span aria-hidden="true">{glyph}</span>
    </button>
  );
  return (
    <div className="row-actions">
      {button(t('editor.moveUp', { id: row.id }), '↑', { type: 'move', key: row.key, by: -1 }, index === 0)}
      {button(t('editor.moveDown', { id: row.id }), '↓', { type: 'move', key: row.key, by: 1 }, index === count - 1)}
      {button(t('editor.copy', { id: row.id }), '⧉', { type: 'copy', key: row.key })}
      {button(t('editor.delete', { id: row.id }), '✕', { type: 'delete', key: row.key }, count === 1)}
    </div>
  );
}

/** The minimal draft editor: cells as plain text, row actions, and the engine's errors on their cells. */
export function DraftEditor({ definition, draft, dispatch, errors }: {
  definition: RuleSetDefinition;
  draft: DraftState;
  dispatch: Dispatch<DraftAction>;
  errors: readonly ErrorPlace[];
}) {
  const { t } = useI18n();
  const cellMessage = useCellMessage();
  return (
    <div className="dt-scroll">
      <table className="dt dt--edit">
        <TableHead definition={definition} extra={t('editor.actions')} />
        <tbody onKeyDown={moveFocus}>
          {draft.rows.map((row, index) => {
            const rowError = cellMessage(rowErrorOf(errors, row));
            return (
              <tr key={row.key} className={rowError !== undefined ? 'dt__row--bad' : undefined}>
                <th scope="row" className="dt__rowhead">
                  <input
                    className="cell-input cell-input--id mono"
                    value={row.id}
                    aria-label={t('editor.idLabel', { position: String(index + 1) })}
                    data-row={index}
                    data-col="id"
                    spellCheck={false}
                    onChange={(event) => { dispatch({ type: 'edit-id', key: row.key, text: event.target.value }); }}
                  />
                  {rowError !== undefined ? <span className="cell-error">{rowError}</span> : null}
                </th>
                {definition.inputs.map((input) => (
                  <td key={input.name}>
                    {row.groups[input.name] !== undefined ? (
                      <span className="cell-raw">{t('table.group')}</span>
                    ) : (
                      <CellInput row={row} index={index} part="when" column={input.name} label={columnLabel(input.name, input.description)} error={cellMessage(cellError(errors, row, input.name))} dispatch={dispatch} />
                    )}
                  </td>
                ))}
                {definition.outputs.map((output, outputIndex) => (
                  <td key={output.name} className={outputIndex === 0 ? 'dt__first-then' : undefined}>
                    <CellInput row={row} index={index} part="then" column={output.name} label={columnLabel(output.name, output.description)} error={cellMessage(cellError(errors, row, output.name))} dispatch={dispatch} />
                  </td>
                ))}
                <td className="dt__actions">
                  <RowActions row={row} index={index} count={draft.rows.length} dispatch={dispatch} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="editor-foot">
        <button type="button" className="btn btn--quiet" onClick={() => { dispatch({ type: 'add' }); }}>
          <span aria-hidden="true">＋</span> {t('editor.addRow')}
        </button>
        <span className="editor-foot__note">{t('editor.fixed')}</span>
      </div>
    </div>
  );
}
