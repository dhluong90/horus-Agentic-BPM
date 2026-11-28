import { useI18n } from '../../../i18n/I18nProvider';
import { formatMoney } from '../../rules/model/money';
import { CopyButton } from '../../../components/CopyButton';
import { isMasked, isMoney } from '../model/review';

/**
 * One value of the agent's inputs or answer, in words: money with the language's separators and its minor units,
 * a masked value as one "Masked" chip, never raw JSON. A keyed fingerprint is never shown; where `copyable`, a
 * "Copy reference" action copies it, so two masked values can be compared outside the screen.
 */
export function ReviewValue({ value, copyable = false }: { value: unknown; copyable?: boolean }) {
  const { t, locale } = useI18n();
  if (isMasked(value)) {
    return (
      <span className="masked">
        <span className="masked__label"><span aria-hidden="true">◍</span> {t('review.masked')}</span>
        {copyable && value !== '***' ? <CopyButton value={String(value)} label={t('review.copyRef')} iconOnly /> : null}
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
