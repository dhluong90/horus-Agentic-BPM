import type { Rule, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { diffRules, type RowDiff } from '../model/diff';
import { cellText, nonAsciiCharacters } from '../model/nonAscii';
import { readCondition, readOutput, type CellReading } from '../model/reading';
import { CellValue } from './CellValue';
import { DecisionTable, TableHead } from './DecisionTable';

const GLYPH: Record<RowDiff['change'], string> = { ADDED: '＋', CHANGED: '✎', MOVED: '⇅', REMOVED: '−', UNCHANGED: '' };

function NonAsciiMark({ raw }: { raw: string }) {
  const { t } = useI18n();
  const characters = nonAsciiCharacters(raw);
  if (characters.length === 0) return null;
  return (
    <span className="nonascii-mark">
      <span aria-hidden="true">▲</span> {t('changes.nonAscii', { chars: characters.join(', ') })}
    </span>
  );
}

function DiffCell({ now, before, changed, raw }: { now: CellReading; before: CellReading; changed: boolean; raw: string }) {
  const { t } = useI18n();
  if (!changed) return <CellValue reading={now} />;
  return (
    <>
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
    <NonAsciiMark raw={raw} />
    </>
  );
}

function rowClass(row: RowDiff): string {
  return `diff-row diff-row--${row.change.toLowerCase()}`;
}

/**
 * The checker's view. The row comparison is computed in this browser and is an aid only; the record the
 * approver signs off is the engine's canonical table of the pending version, shown below with its hash.
 * When the engine's diff endpoint (RLE-028) lands, it replaces the browser comparison.
 */
export function ChangesView({ definition, base, baseVersion, record }: {
  definition: RuleSetDefinition;
  base: RuleSetDefinition | undefined;
  baseVersion: number | undefined;
  record: { version: number; canonicalHash: string };
}) {
  const { t, locale } = useI18n();
  const rows = diffRules(base?.rules ?? [], definition.rules);
  const count = (change: RowDiff['change']) => String(rows.filter((row) => row.change === change || (change === 'MOVED' && row.moved && row.change === 'CHANGED')).length);
  const empty: Rule = { id: '', when: {}, then: {} };
  return (
    <div className="changes">
      <p className="changes__aid">
        <span aria-hidden="true">ⓘ</span> {t('changes.aid')}
      </p>
      <p className="changes__summary">
        <span>{base && baseVersion !== undefined ? t('changes.base', { base: String(baseVersion) }) : t('changes.first')}</span>
        <span className="tnum">{t('changes.summary', { added: count('ADDED'), changed: count('CHANGED'), moved: count('MOVED'), removed: count('REMOVED') })}</span>
      </p>
      <div className="dt-scroll" role="region" tabIndex={0} aria-label={t('changes.tableLabel')}>
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
                      <DiffCell now={readCondition(now.when[input.name], locale)} before={readCondition(before.when[input.name], locale)} changed={row.changedCells.has(`when.${input.name}`)} raw={cellText(now.when[input.name])} />
                    </td>
                  ))}
                  {definition.outputs.map((output, index) => (
                    <td key={output.name} className={[index === 0 ? 'dt__first-then' : '', row.changedCells.has(`then.${output.name}`) ? 'diff-td--mod' : ''].join(' ').trim() || undefined}>
                      <DiffCell now={readOutput(now.then[output.name], locale)} before={readOutput(before.then[output.name], locale)} changed={row.changedCells.has(`then.${output.name}`)} raw={cellText(now.then[output.name])} />
                    </td>
                  ))}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {rows.every((row) => row.change === 'UNCHANGED') && base ? <p className="empty">{t('changes.none')}</p> : null}
      <section className="changes__record" aria-labelledby="record-title">
        <div className="changes__record-head">
          <h2 id="record-title" className="card__title">{t('changes.record', { version: String(record.version) })}</h2>
          <span className="changes__hash">{t('changes.hash')} <span className="mono" title={record.canonicalHash}>{record.canonicalHash.slice(0, 12)}</span></span>
        </div>
        <DecisionTable definition={definition} version={record.version} matched={new Set()} />
      </section>
    </div>
  );
}
