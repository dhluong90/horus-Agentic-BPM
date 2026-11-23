import type { SimulationReport } from '../../../api/rules/schemas';
import { useI18n, type Locale } from '../../../i18n/I18nProvider';
import { formatDateTime } from '../model/dates';
import { useNames, useWantNames } from '../../processes/api/displayNames';

/** A whole number with the reader's thousand separators. */
export function formatCount(value: number, locale: Locale): string {
  return new Intl.NumberFormat(locale === 'vi' ? 'vi-VN' : 'en-GB').format(value);
}

function formatDay(iso: string | null, locale: Locale): string {
  if (iso === null) return '';
  return new Intl.DateTimeFormat(locale === 'vi' ? 'vi-VN' : 'en-GB', { day: 'numeric', month: locale === 'vi' ? 'numeric' : 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(iso));
}

/** What a report ran over, in words: the sample customers, or the logged decisions of a period. */
export function useScopeText(report: SimulationReport): string {
  const { t, locale } = useI18n();
  const { scope, counts } = report;
  if (scope.source === 'SAMPLES') return t('report.scope.samples', { count: formatCount(counts.total, locale) });
  return t('report.scope.replay', { count: formatCount(counts.total, locale), from: formatDay(scope.from, locale), to: formatDay(scope.to === null ? null : new Date(Date.parse(scope.to) - 1).toISOString(), locale) });
}

/** "Run by Lan, 19 Nov 2026 10:42": the person as the host names them. */
export function useRunText(report: SimulationReport): string {
  const { t, locale } = useI18n();
  const { user } = useNames();
  useWantNames({ users: [report.createdBy] });
  return t('report.run', { who: user(report.createdBy), when: formatDateTime(report.createdAt, locale) ?? '' });
}

/** Current or Out of date, as a word and a mark, never colour alone. */
export function ReportStatus({ status }: { status: SimulationReport['status'] }) {
  const { t } = useI18n();
  return status === 'CURRENT'
    ? <span className="badge badge--ok"><span aria-hidden="true">✓</span> {t('report.status.CURRENT')}</span>
    : <span className="badge badge--warn"><span aria-hidden="true">▲</span> {t('report.status.OUT_OF_DATE')}</span>;
}
