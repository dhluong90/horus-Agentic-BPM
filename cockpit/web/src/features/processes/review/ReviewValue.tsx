import { useI18n } from '../../../i18n/I18nProvider';
import { formatMoney } from '../../rules/model/money';
import { fingerprintHint, isMasked, isMoney } from '../model/review';

/**
 * One value of the agent's inputs or answer, in words: money with the language's separators and its minor units,
 * a masked value as "Masked" with at most the start of its keyed fingerprint, never raw JSON.
 */
export function ReviewValue({ value }: { value: unknown }) {
  const { t, locale } = useI18n();
  if (isMasked(value)) {
    const hint = fingerprintHint(value);
    return (
      <span className="masked">
        <span className="masked__tag"><span aria-hidden="true">◍</span> {t('review.masked')}</span>
        {hint !== undefined ? <span className="mono masked__hint" title={t('review.fingerprint')}>{hint}</span> : null}
      </span>
    );
  }
  if (isMoney(value)) return <span className="tnum">{formatMoney(value, locale)}</span>;
  if (value === null || value === undefined) return <span className="muted" aria-label={t('rulesArea.none')}>—</span>;
  if (typeof value === 'boolean') return <span>{value ? t('review.yes') : t('review.no')}</span>;
  if (typeof value === 'number') return <span className="tnum">{new Intl.NumberFormat(locale === 'vi' ? 'vi-VN' : 'en-US', { maximumFractionDigits: 20 }).format(value)}</span>;
  if (typeof value === 'string') return <span className="review-value__text">{value}</span>;
  if (Array.isArray(value)) return <span className="muted">{t('review.items', { count: String(value.length) })}</span>;
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    return (
      <span className="review-value__nested">
        {entries.map(([key, inner], index) => (
          <span key={key}>{index > 0 ? ', ' : ''}{key}: <ReviewValue value={inner} /></span>
        ))}
      </span>
    );
  }
  return null;
}
