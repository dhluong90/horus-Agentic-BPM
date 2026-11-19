import type { Locale } from '../../../i18n/I18nProvider';

/** "19 Nov 2026 14:30" in English, "19/11/2026 14:30" in Vietnamese (ux-design section 9). */
export function formatDateTime(iso: string | null | undefined, locale: Locale, timeZone?: string): string | undefined {
  if (iso === null || iso === undefined) return undefined;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return undefined;
  const parts = new Intl.DateTimeFormat(locale === 'vi' ? 'vi-VN' : 'en-GB', {
    day: '2-digit',
    month: locale === 'vi' ? '2-digit' : 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    ...(timeZone ? { timeZone } : {}),
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? '';
  const time = `${part('hour')}:${part('minute')}`;
  return locale === 'vi' ? `${part('day')}/${part('month')}/${part('year')} ${time}` : `${part('day')} ${part('month')} ${part('year')} ${time}`;
}
