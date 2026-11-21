import type { Rule, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n, type Locale, type Translate } from '../../../i18n/I18nProvider';
import { diffRules, type RowDiff } from '../model/diff';
import { clauseLabel, readCondition, readOutput, type CellReading, type Value } from '../model/reading';

function valueText(value: Value, t: Translate): string {
  return value.kind === 'text' ? value.text : value.kind === 'yes' ? t('table.yes') : value.kind === 'no' ? t('table.no') : t('table.empty');
}

/** A cell reading as one short phrase for a list ("Gold, Platinum", "≥ 20,000,000 VND", "—"). */
export function readingText(reading: CellReading, t: Translate): string {
  switch (reading.kind) {
    case 'any':
      return '—';
    case 'values':
      return `${reading.negated ? `${t('table.not')} ` : ''}${reading.values.map((v) => valueText(v, t)).join(', ')}`;
    case 'compare':
      return `${reading.operator} ${valueText(reading.value, t)}`;
    case 'value':
      return valueText(reading.value, t);
    case 'group':
      return t('table.group');
    case 'raw':
      // An unfinished cell (an operator with no value yet) reads with the table's symbols, never engine syntax.
      return reading.text.replace(/^>=/, '≥').replace(/^<=/, '≤').replace(/^==/, '=').replace(/^!=/, '≠');
  }
}

/** Cells and rows the engine refused in the committed table: they stay out of the card until they are valid. */
export interface Refused { cells: ReadonlySet<string>; rows: ReadonlySet<string> }

export const cellKey = (ruleId: string, column: string): string => `${ruleId}|${column}`;

const MARK: Record<RowDiff['change'], string> = { ADDED: '+', CHANGED: '~', MOVED: '⇅', REMOVED: '−', UNCHANGED: '' };

/** One changed row as a phrase, or undefined when every change in it is still refused. */
function lineText(row: RowDiff, definition: RuleSetDefinition, refused: Refused, t: Translate, locale: Locale): string | undefined {
  const empty: Rule = { id: '', when: {}, then: {} };
  const now = row.rule ?? empty;
  const before = row.base ?? empty;
  const cell = (part: 'when' | 'then', clause: RuleSetDefinition['inputs'][number] | RuleSetDefinition['outputs'][number]) => {
    const read = (rule: Rule) => (part === 'when' ? readCondition(rule.when[clause.name], locale) : readOutput(rule.then[clause.name], locale));
    return { name: clause.name, label: clauseLabel(clause, locale), before: readingText(read(before), t), now: readingText(read(now), t) };
  };
  const columns = [...definition.inputs.map((i) => cell('when', i)), ...definition.outputs.map((o) => cell('then', o))]
    .filter((c) => !refused.cells.has(cellKey(row.ruleId, c.name)));
  if (row.change === 'ADDED') return `${t('draft.added')}: ${columns.filter((c) => c.now !== '—').map((c) => `${c.label} ${c.now}`).join(', ')}`;
  if (row.change === 'REMOVED') return t('draft.removed');
  if (row.change === 'MOVED') return t('draft.moved');
  const changed = columns.filter((c) => c.before !== c.now);
  return changed.length === 0 ? undefined : changed.map((c) => `${c.label}: ${c.before} → ${c.now}`).join('; ');
}

/**
 * The rows this draft changes against the version it started from, as short phrases beside the editor. `next` is the
 * table as of the last committed cell, and a cell or row the engine refused stays out, so the card never shows a
 * half-typed value ("≥ 50,000,000 VND → ≥").
 */
export function DraftChanges({ definition, base, next, refused }: { definition: RuleSetDefinition; base: readonly Rule[]; next: readonly Rule[]; refused: Refused }) {
  const { t, locale } = useI18n();
  const lines = diffRules(base, next)
    .filter((row) => row.change !== 'UNCHANGED' && !refused.rows.has(row.ruleId))
    .flatMap((row) => {
      const text = lineText(row, definition, refused, t, locale);
      return text === undefined ? [] : [{ row, text }];
    });
  return (
    <section id="draft-changes" tabIndex={-1} className="card draft-changes" aria-labelledby="draft-changes-title">
      <h2 id="draft-changes-title" className="rules-card-title">{t('editor.draftChanges')}</h2>
      {lines.length === 0 ? <p className="dock__hint">{t('editor.noDraftChanges')}</p> : (
        <ul className="draft-changes__list">
          {lines.map(({ row, text }) => (
            <li key={`${row.change}-${row.ruleId}`} className={`draft-changes__line draft-changes__line--${row.change.toLowerCase()}`}>
              <span className="draft-changes__mark">{MARK[row.change]} {row.ruleId}</span>
              <span>{text}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
