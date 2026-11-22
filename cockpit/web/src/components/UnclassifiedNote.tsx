import { useId } from 'react';
import { useI18n } from '../i18n/I18nProvider';

/** How many fields the note names before it counts the rest. */
export const NOTE_VISIBLE = 4;

/**
 * Fields whose masking nobody has set (PD-146): a calm note in neutral colours, never a dialog and never a reason
 * to hold approval. It names fields only; it has no way to show a value. Nothing renders without a field.
 */
export function UnclassifiedNote({ fields }: { fields: readonly string[] }) {
  const { t } = useI18n();
  const titleId = useId();
  if (fields.length === 0) return null;
  const shown = fields.slice(0, NOTE_VISIBLE);
  const rest = fields.length - shown.length;
  return (
    <div className="unclassified" role="group" aria-labelledby={titleId}>
      <p className="unclassified__head">
        <span id={titleId} className="unclassified__title"><span aria-hidden="true" className="unclassified__icon">○</span> {t('unclassified.title')}</span>
        <span className="unclassified__text">{t('unclassified.text')}</span>
      </p>
      <ul className="unclassified__list">
        {shown.map((field) => <li key={field}>{field}</li>)}
        {rest > 0 ? <li className="unclassified__more">{t('unclassified.more', { count: String(rest) })}</li> : null}
      </ul>
    </div>
  );
}
