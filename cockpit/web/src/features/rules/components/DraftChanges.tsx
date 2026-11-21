import type { Rule, RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n, type Translate } from '../../../i18n/I18nProvider';
import { diffRules, type RowDiff } from '../model/diff';
import { columnLabel, readCondition, readOutput, type CellReading, type Value } from '../model/reading';

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

const MARK: Record<RowDiff['change'], string> = { ADDED: '+', CHANGED: '~', MOVED: '⇅', REMOVED: '−', UNCHANGED: '' };

function Line({ row, definition }: { row: RowDiff; definition: RuleSetDefinition }) {
  const { t, locale } = useI18n();
  const empty: Rule = { id: '', when: {}, then: {} };
  const now = row.rule ?? empty;
  const before = row.base ?? empty;
  const cell = (part: 'when' | 'then', name: string, description: string | undefined) => {
    const read = (rule: Rule) => (part === 'when' ? readCondition(rule.when[name], locale) : readOutput(rule.then[name], locale));
    return { label: columnLabel(name, description), before: readingText(read(before), t), now: readingText(read(now), t) };
  };
  const columns = [...definition.inputs.map((i) => cell('when', i.name, i.description)), ...definition.outputs.map((o) => cell('then', o.name, o.description))];
  let text: string;
  if (row.change === 'ADDED') text = `${t('draft.added')}: ${columns.filter((c) => c.now !== '—').map((c) => `${c.label} ${c.now}`).join(', ')}`;
  else if (row.change === 'REMOVED') text = t('draft.removed');
  else if (row.change === 'MOVED') text = t('draft.moved');
  else text = columns.filter((c) => c.before !== c.now).map((c) => `${c.label}: ${c.before} → ${c.now}`).join('; ');
  return (
    <li className={`draft-changes__line draft-changes__line--${row.change.toLowerCase()}`}>
      <span className="draft-changes__mark">{MARK[row.change]} {row.ruleId}</span>
      <span>{text}</span>
    </li>
  );
}

/** The rows this draft changes against the version it started from, as short phrases beside the editor. */
export function DraftChanges({ definition, base, next }: { definition: RuleSetDefinition; base: readonly Rule[]; next: readonly Rule[] }) {
  const { t } = useI18n();
  const rows = diffRules(base, next).filter((row) => row.change !== 'UNCHANGED');
  return (
    <section id="draft-changes" tabIndex={-1} className="card draft-changes" aria-labelledby="draft-changes-title">
      <h2 id="draft-changes-title" className="rules-card-title">{t('editor.draftChanges')}</h2>
      {rows.length === 0 ? <p className="dock__hint">{t('editor.noDraftChanges')}</p> : (
        <ul className="draft-changes__list">{rows.map((row) => <Line key={`${row.change}-${row.ruleId}`} row={row} definition={definition} />)}</ul>
      )}
    </section>
  );
}
