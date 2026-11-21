import type { RuleSetReadings, RuleSetVersion } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { useVersionReadings } from '../api/useRules';
import { EmptyNote, MaskedNote, ReadingQueryState, ReadingStyleSwitch, TooLargeNote, maskedInputs, readingIn, testsMasked, useViewerReadingStyle } from './ReadingParts';

function ReadingList({ readings, version, matched }: { readings: RuleSetReadings; version: RuleSetVersion; matched: ReadonlySet<string> }) {
  const { t, locale } = useI18n();
  const [style] = useViewerReadingStyle();
  if (readings.rows.length === 0) return <EmptyNote text="readings.empty" />;
  if (!readings.languages.includes(locale)) return <EmptyNote text="readings.noLanguage" />;
  const masked = maskedInputs(version.definition);
  const rules = new Map(version.definition.rules.map((rule) => [rule.id, rule]));
  const caption = t('readings.caption', { name: version.ruleSet, version: String(version.version) });
  return (
    <ol className="readings__list" aria-label={caption}>
      {readings.rows.map((row) => {
        const isMatched = matched.has(row.ruleId);
        return (
          <li key={row.ruleId} className={isMatched ? 'reading reading--matched' : 'reading'}>
            <span className="reading__id">
              <span className="row-id">{row.ruleId}</span>
              {isMatched ? <span className="match-mark"><span aria-hidden="true">✓</span> {t('table.matched')}</span> : null}
            </span>
            <div className="reading__body">
              <p className="reading__text tnum">{readingIn(row.readings, locale, style) ?? ''}</p>
              {testsMasked(rules.get(row.ruleId)?.when, masked) ? <MaskedNote /> : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** Every row of the version as the engine reads it, one line per row, in the user's language (RLE-091). */
export function ReadingsView({ version, matched, onOpenTable }: { version: RuleSetVersion; matched: ReadonlySet<string>; onOpenTable: () => void }) {
  const { t } = useI18n();
  const query = useVersionReadings(version, true);
  const count = version.definition.rules.length;
  return (
    <div className="readings">
      {count === 0 ? null : <div className="readings__bar">
        <p className="readings__count tnum">{count === 1 ? t('readings.countOne') : t('readings.count', { count: String(count) })}</p>
        <ReadingStyleSwitch />
      </div>}
      <ReadingQueryState query={query} rows={Math.min(Math.max(count, 1), 6)}
        tooLarge={<TooLargeNote text="readings.tooLarge.version" action={{ label: t('readings.toTable'), onSelect: onOpenTable }} />}
        ready={(readings) => <ReadingList readings={readings} version={version} matched={matched} />} />
    </div>
  );
}
