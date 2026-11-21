import type { ReadingsDiff, ReadingsDiffRow, RuleSetDefinition, RuleSetVersion } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { useDiffReadings } from '../api/useRules';
import { EmptyNote, MaskedNote, ReadingQueryState, TooLargeNote, maskedInputs, readingIn, testsMasked, useViewerReadingStyle } from './ReadingParts';

const MARKS = { ADDED: '＋', CHANGED: '✎', MOVED: '⇅', REMOVED: '−' } as const;
type Mark = keyof typeof MARKS;

function markOf(change: string): Mark | undefined {
  return change === 'ADDED' || change === 'CHANGED' || change === 'MOVED' || change === 'REMOVED' ? change : undefined;
}

function rowTestsMasked(row: ReadingsDiffRow, base: RuleSetDefinition, next: RuleSetDefinition): boolean {
  const before = base.rules.find((rule) => rule.id === row.ruleId);
  const after = next.rules.find((rule) => rule.id === row.ruleId);
  return testsMasked(before?.when, maskedInputs(base)) || testsMasked(after?.when, maskedInputs(next));
}

/** One changed row: the old reading struck and the new one added; a row whose wording did not change shows once. */
function DiffRow({ row, base, next }: { row: ReadingsDiffRow; base: RuleSetVersion; next: RuleSetVersion }) {
  const { t, locale } = useI18n();
  const [style] = useViewerReadingStyle();
  const mark = markOf(row.change);
  const previous = readingIn(row.previous, locale, style);
  const current = readingIn(row.current, locale, style);
  const strike = previous !== undefined && (row.readingChanged || current === undefined);
  return (
    <li className={`reading reading--${row.change.toLowerCase()}`}>
      <span className="reading__id">
        <span className="row-id">{row.ruleId}</span>
        {mark !== undefined ? <span className={`change-mark change-mark--${mark.toLowerCase()}`}><span aria-hidden="true">{MARKS[mark]}</span> {t(`change.${mark}`)}</span> : null}
      </span>
      <div className="reading__body">
        {strike ? (
          <p className="reading__text reading__text--old tnum">
            <span className="sr-only">{t('readings.diff.was', { version: String(base.version) })} </span>
            <del>{previous}</del>
          </p>
        ) : null}
        {current !== undefined ? (
          <p className={row.readingChanged ? 'reading__text reading__text--new tnum' : 'reading__text tnum'}>
            {row.readingChanged ? <span className="sr-only">{t('readings.diff.now', { version: String(next.version) })} </span> : null}
            {row.readingChanged ? <ins>{current}</ins> : current}
          </p>
        ) : null}
        {!row.readingChanged && current !== undefined ? <p className="reading__note">{t('readings.diff.sameWords')}</p> : null}
        {rowTestsMasked(row, base.definition, next.definition) ? <MaskedNote /> : null}
      </div>
    </li>
  );
}

function DiffList({ diff, base, next }: { diff: ReadingsDiff; base: RuleSetVersion; next: RuleSetVersion }) {
  const { t, locale } = useI18n();
  if (!diff.languages.includes(locale)) return <EmptyNote text="readings.noLanguage" />;
  const unchanged = diff.unchangedCount === 1 ? t('readings.diff.unchangedOne') : t('readings.diff.unchanged', { count: String(diff.unchangedCount) });
  return (
    <>
      {diff.rows.length === 0 ? <EmptyNote text="readings.diff.none" /> : (
        <ol className="readings__list" aria-label={t('readings.diff.caption', { base: String(base.version), version: String(next.version) })}>
          {diff.rows.map((row) => <DiffRow key={`${row.change}-${row.ruleId}`} row={row} base={base} next={next} />)}
        </ol>
      )}
      {diff.unchangedCount > 0 ? <p className="readings__foot tnum">{unchanged}</p> : null}
    </>
  );
}

/** The policy redline: each changed row read in both versions by the engine (RLE-092). */
export function ReadingsDiffView({ base, next, onCompareTable }: { base: RuleSetVersion; next: RuleSetVersion; onCompareTable: () => void }) {
  const { t } = useI18n();
  const query = useDiffReadings(next, base.version);
  return (
    <ReadingQueryState query={query}
      tooLarge={<TooLargeNote text="readings.tooLarge.diff" action={{ label: t('readings.compareTable'), onSelect: onCompareTable }} />}
      ready={(diff) => <DiffList diff={diff} base={base} next={next} />} />
  );
}
