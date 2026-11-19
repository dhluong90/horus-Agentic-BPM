import type { Rule, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { diffRules, type RowDiff } from '../model/diff';
import { readCondition, readOutput, type CellReading } from '../model/reading';
import { CellValue } from './CellValue';
import { TableHead } from './DecisionTable';

const GLYPH: Record<RowDiff['change'], string> = { ADDED: '＋', CHANGED: '✎', MOVED: '⇅', REMOVED: '−', UNCHANGED: '' };

function DiffCell({ now, before, changed }: { now: CellReading; before: CellReading; changed: boolean }) {
  const { t } = useI18n();
  if (!changed) return <CellValue reading={now} />;
  return (
    <span className="diff-cell">
      <span className="diff-cell__old">
        <span className="sr-only">{t('changes.was')} </span>
        <CellValue reading={before} />
      </span>
      <span aria-hidden="true" className="diff-cell__arrow">→</span>
      <span className="diff-cell__new">
        <CellValue reading={now} />
      </span>
    </span>
  );
}

function rowClass(row: RowDiff): string {
  return `diff-row diff-row--${row.change.toLowerCase()}`;
}

/** The checker's view: rows of this version against the base version, with the same labels and formats as the table. */
export function ChangesView({ definition, base, baseVersion }: { definition: RuleSetDefinition; base: RuleSetDefinition | undefined; baseVersion: number | undefined }) {
  const { t, locale } = useI18n();
  const rows = diffRules(base?.rules ?? [], definition.rules);
  const count = (change: RowDiff['change']) => String(rows.filter((row) => row.change === change || (change === 'MOVED' && row.moved && row.change === 'CHANGED')).length);
  const empty: Rule = { id: '', when: {}, then: {} };
  return (
    <div className="changes">
      <p className="changes__summary">
        <span>{base && baseVersion !== undefined ? t('changes.base', { base: String(baseVersion) }) : t('changes.first')}</span>
        <span className="tnum">{t('changes.summary', { added: count('ADDED'), changed: count('CHANGED'), moved: count('MOVED'), removed: count('REMOVED') })}</span>
      </p>
      <div className="dt-scroll">
        <table className="dt dt--diff">
          <TableHead definition={definition} />
          <tbody>
            {rows.map((row) => {
              const now = row.rule ?? row.base ?? empty;
              const before = row.base ?? empty;
              return (
                <tr key={`${row.change}-${row.ruleId}`} className={rowClass(row)}>
                  <th scope="row" className="dt__rowhead">
                    <span className="row-id mono">{row.ruleId}</span>
                    {row.change !== 'UNCHANGED' ? (
                      <span className={`change-mark change-mark--${row.change.toLowerCase()}`}>
                        <span aria-hidden="true">{GLYPH[row.change]}</span> {t(`change.${row.change}`)}
                      </span>
                    ) : null}
                    {row.change === 'CHANGED' && row.moved ? (
                      <span className="change-mark change-mark--moved">
                        <span aria-hidden="true">{GLYPH.MOVED}</span> {t('change.MOVED')}
                      </span>
                    ) : null}
                  </th>
                  {definition.inputs.map((input) => (
                    <td key={input.name} className={row.changedCells.has(`when.${input.name}`) ? 'diff-td--mod' : undefined}>
                      <DiffCell now={readCondition(now.when[input.name], locale)} before={readCondition(before.when[input.name], locale)} changed={row.changedCells.has(`when.${input.name}`)} />
                    </td>
                  ))}
                  {definition.outputs.map((output, index) => (
                    <td key={output.name} className={[index === 0 ? 'dt__first-then' : '', row.changedCells.has(`then.${output.name}`) ? 'diff-td--mod' : ''].join(' ').trim() || undefined}>
                      <DiffCell now={readOutput(now.then[output.name], locale)} before={readOutput(before.then[output.name], locale)} changed={row.changedCells.has(`then.${output.name}`)} />
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {rows.every((row) => row.change === 'UNCHANGED') && base ? <p className="empty">{t('changes.none')}</p> : null}
    </div>
  );
}
