import type { InputMasking, Rule, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { diffRules, type RowDiff } from '../model/diff';
import { maskingMark, maskRemoved } from '../model/masking';
import { cellText, nonAsciiCharacters } from '../model/nonAscii';
import { readCondition, readOutput, type CellReading } from '../model/reading';
import { CellValue, ValueText } from './CellValue';
import { TableHead } from './DecisionTable';

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

/** A changed list of values reads as the values with the added ones marked: Gold (+ Platinum). */
function ListDiff({ now, before }: { now: Extract<CellReading, { kind: 'values' }>; before: Extract<CellReading, { kind: 'values' }> }) {
  const { t } = useI18n();
  const text = (value: (typeof now.values)[number]) => (value.kind === 'text' ? value.text : value.kind);
  const old = new Set(before.values.map(text));
  const kept = new Set(now.values.map(text));
  return (
    <span className="cell-values">
      {now.values.map((value, index) => (
        <span key={index} className={old.has(text(value)) ? 'value-pill' : 'value-pill value-pill--added'}>
          {old.has(text(value)) ? null : <span aria-hidden="true">+ </span>}
          {!old.has(text(value)) ? <span className="sr-only">{t('change.ADDED')} </span> : null}
          <ValueText value={value} />
        </span>
      ))}
      {before.values.filter((value) => !kept.has(text(value))).map((value, index) => (
        <span key={`r${String(index)}`} className="value-pill value-pill--removed">
          <span className="sr-only">{t('change.REMOVED')} </span><ValueText value={value} />
        </span>
      ))}
    </span>
  );
}

function DiffCell({ now, before, changed, raw }: { now: CellReading; before: CellReading; changed: boolean; raw: string }) {
  const { t } = useI18n();
  if (!changed) return <CellValue reading={now} />;
  if (now.kind === 'values' && before.kind === 'values' && now.negated === before.negated) {
    return (
      <>
        <ListDiff now={now} before={before} />
        <NonAsciiMark raw={raw} />
      </>
    );
  }
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

/** Under an input's header: its masking change, if any. A removed mask carries the warning mark and words. */
function MaskingNote({ masking }: { masking: InputMasking | undefined }) {
  const { t } = useI18n();
  const mark = maskingMark(masking);
  if (mark.kind === 'none') return null;
  if (mark.kind === 'added') {
    return <span className="masking-note"><span className="sr-only">{t('masking.label')}: </span>{t('masking.added', { status: t(`masking.${mark.to}`) })}</span>;
  }
  return (
    <span className={mark.removed ? 'masking-note masking-note--removed' : 'masking-note'}>
      {mark.removed ? <span className="masking-note__flag"><span aria-hidden="true">▲</span> {t('masking.removed')}</span> : null}
      <span>
        <span className="sr-only">{t('masking.label')}: </span>
        {t(`masking.${mark.from}`)} <span aria-hidden="true">→</span><span className="sr-only">,</span> {t(`masking.${mark.to}`)}
      </span>
    </span>
  );
}

function rowClass(row: RowDiff): string {
  return `diff-row diff-row--${row.change.toLowerCase()}`;
}

/**
 * The checker's view: one summary line, then the rows of this version against the base version with the
 * editor's labels and formats. The comparison is computed in this browser until the engine's diff endpoint
 * (RLE-028, issue #165) lands; the stored version is the Table tab, and Details shows its content hash.
 */
export function ChangesView({ definition, base, baseVersion, baseInEffect, masking = [], maskingUnavailable = false }: {
  definition: RuleSetDefinition;
  base: RuleSetDefinition | undefined;
  baseVersion: number | undefined;
  baseInEffect: boolean;
  /** The engine diff's masking column (RLE-051); empty until it answers or when the host has none. */
  masking?: readonly InputMasking[] | undefined;
  maskingUnavailable?: boolean;
}) {
  const { t, locale } = useI18n();
  const rows = diffRules(base?.rules ?? [], definition.rules);
  // Changed cells whose new literal holds characters outside ASCII, so the summary can flag them too.
  const nonAscii = rows.reduce((total, row) => total + [...row.changedCells].filter((cell) => {
    const [part, name] = cell.split('.') as ['when' | 'then', string];
    return nonAsciiCharacters(cellText(row.rule?.[part][name])).length > 0;
  }).length, 0);
  const count = (change: RowDiff['change']) => String(rows.filter((row) => row.change === change || (change === 'MOVED' && row.moved && row.change === 'CHANGED')).length);
  const empty: Rule = { id: '', when: {}, then: {} };
  const maskingOf = new Map(masking.map((item) => [item.input, item]));
  const removedMasks = definition.inputs.filter((input) => { const item = maskingOf.get(input.name); return item !== undefined && maskRemoved(item); }).length;
  return (
    <div className="changes">
      {base ? <p className="changes__note">{t('changes.browserNote')}</p> : null}
      <p className="changes__summary">
        {base && baseVersion !== undefined ? (
          <>
            <strong className="tnum">{t('changes.summaryLine', { added: count('ADDED'), changed: count('CHANGED'), removed: count('REMOVED'), unchanged: count('UNCHANGED') })}</strong>
            <span>{t(baseInEffect ? 'changes.comparedInEffect' : 'changes.comparedWith', { base: String(baseVersion) })}</span>
          </>
        ) : (
          <strong>{t('changes.first')}</strong>
        )}
        {nonAscii > 0 ? (
          <span className="nonascii-mark"><span aria-hidden="true">▲</span> {t('changes.nonAsciiCount', { count: String(nonAscii) })}</span>
        ) : null}
        {removedMasks > 0 ? (
          <span className="nonascii-mark"><span aria-hidden="true">▲</span> {removedMasks === 1 ? t('changes.masksRemovedOne') : t('changes.masksRemovedCount', { count: String(removedMasks) })}</span>
        ) : null}
      </p>
      {maskingUnavailable && base ? <p className="changes__note">{t('changes.maskingUnavailable')}</p> : null}
      <div className="dt-scroll" role="region" tabIndex={0} aria-label={t('changes.tableLabel')}>
        <table className="dt dt--diff">
          <TableHead definition={definition} inputNote={(input) => <MaskingNote masking={maskingOf.get(input)} />} />
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
    </div>
  );
}
