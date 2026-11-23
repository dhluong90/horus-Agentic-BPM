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

/** How long ago, in the reader's language ("2 minutes ago"); under a minute it is undefined so the caller words it. */
export function agoText(elapsedMs: number, locale: Locale): string | undefined {
  const minutes = Math.floor(elapsedMs / 60_000);
  if (minutes < 1) return undefined;
  const format = new Intl.RelativeTimeFormat(locale === 'vi' ? 'vi-VN' : 'en-GB', { numeric: 'auto' });
  return minutes < 60 ? format.format(-minutes, 'minute') : format.format(-Math.floor(minutes / 60), 'hour');
}

/** "19 Nov 2026" in English, "19/11/2026" in Vietnamese: the day alone, for a dense list. */
export function formatDate(iso: string | null | undefined, locale: Locale, timeZone?: string): string | undefined {
  const full = formatDateTime(iso, locale, timeZone);
  return full === undefined ? undefined : full.slice(0, full.lastIndexOf(' '));
}
