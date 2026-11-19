import type { Dispatch } from 'react';
import type { InputClause, OutputClause, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { columnCurrency, inputKind, outputKind, readConditionCell, readOutputCell } from '../model/cells';
import { cellSentence } from '../model/cellMessages';
import type { DraftAction, DraftState } from '../model/draft';
import type { ErrorPlace } from '../model/engineErrors';
import { columnLabel } from '../model/reading';
import type { EditableRow } from '../model/table';
import { BoolToggle, ChoiceEditor, CompareEditor, MoneyEditor, TextEditor } from './CellEditors';
import { TableHead } from './DecisionTable';
import { MenuButton } from './MenuButton';

function messagesFor(errors: readonly ErrorPlace[], row: EditableRow, column: string | undefined): string[] {
  return errors.flatMap((e) => {
    if (column === undefined) return e.kind === 'row' && e.ruleId === row.id ? [e.message] : [];
    return e.kind === 'cell' && e.ruleId === row.id && e.column === column ? [e.message] : [];
  });
}

/** One plain sentence for a refused cell; the engine's own words stay in the notice's Details. */
function useSentence(): (messages: string[]) => string | undefined {
  const { t } = useI18n();
  return (messages) => (messages.length === 0 ? undefined : t(cellSentence(messages[0] ?? '')));
}

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

/** The draft editor: typed cells, one ⋯ menu per row, and the engine's errors as sentences on their cells. */
export function DraftEditor({ definition, draft, dispatch, errors }: {
  definition: RuleSetDefinition;
  draft: DraftState;
  dispatch: Dispatch<DraftAction>;
  errors: readonly ErrorPlace[];
}) {
  const { t } = useI18n();
  const sentence = useSentence();
  return (
    <div className="dt-scroll" role="region" tabIndex={0} aria-label={t('editor.tableLabel')}>
      <table className="dt dt--edit">
        <TableHead definition={definition} extra={t('editor.actions')} />
        <tbody>
          {draft.rows.map((row, index) => {
            const rowError = sentence(messagesFor(errors, row, undefined));
            return (
              <tr key={row.key} className={[row.original === undefined ? 'dt__row--new' : '', rowError !== undefined ? 'dt__row--bad' : ''].join(' ').trim() || undefined}>
                <th scope="row" className="dt__rowhead">
                  <span className="row-id">{row.id}</span>
                  {row.original === undefined ? <span className="change-mark change-mark--added"><span aria-hidden="true">+</span> {t('editor.newRow')}</span> : null}
                  {rowError !== undefined ? <span className="cell-error">{rowError}</span> : null}
                </th>
                {definition.inputs.map((input) => (
                  <td key={input.name}>
                    {row.groups[input.name] !== undefined ? (
                      <span className="cell-raw">{t('table.group')}</span>
                    ) : (
                      <ConditionCell row={row} input={input} definition={definition} error={sentence(messagesFor(errors, row, input.name))} dispatch={dispatch} />
                    )}
                  </td>
                ))}
                {definition.outputs.map((output, outputIndex) => (
                  <td key={output.name} className={outputIndex === 0 ? 'dt__first-then' : undefined}>
                    <OutputCell row={row} output={output} definition={definition} error={sentence(messagesFor(errors, row, output.name))} dispatch={dispatch} />
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
      </div>
    </div>
  );
}
