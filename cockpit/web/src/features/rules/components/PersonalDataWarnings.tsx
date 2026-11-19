import type { RuleSetDefinition, VersionWarning } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { columnLabel } from '../model/reading';

/**
 * The engine's personal-data warnings for a version (RLE-049), in plain words, next to the decision. Inputs are
 * named with the column labels of the table. Nothing renders when there is no warning; the engine, not the
 * cockpit, decides whether a warning blocks publication.
 */
export function PersonalDataWarnings({ warnings, definition }: { warnings: readonly VersionWarning[]; definition: RuleSetDefinition }) {
  const { t } = useI18n();
  if (warnings.length === 0) return null;
  const label = (input: string) => columnLabel(input, definition.inputs.find((clause) => clause.name === input)?.description);
  return (
    <div className="pii-warning" role="note" aria-labelledby="pii-warning-title">
      <p id="pii-warning-title" className="pii-warning__title">
        <span aria-hidden="true">▲</span> {t('pii.title')}
      </p>
      <ul className="pii-warning__list">
        {warnings.map((warning) => (
          <li key={warning.input}>
            {warning.matchedName !== null && warning.matchedName !== ''
              ? t('pii.line', { input: label(warning.input), matched: warning.matchedName })
              : t('pii.lineNoMatch', { input: label(warning.input) })}
          </li>
        ))}
      </ul>
      <p className="pii-warning__why">{t('pii.consequence')}</p>
    </div>
  );
}
