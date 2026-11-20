import type { Locale } from '../../../i18n/I18nProvider';

/*
 * How machine references read on screen. A reference is shortened for the eye and always copied in full; the
 * cockpit never derives or checks a hash, it only shows the one the engine sent.
 */

/** A decision id as a short reference: the first six and last four characters, `01KF2Q…P7T9`. */
export function shortId(id: string): string {
  return id.length <= 12 ? id : `${id.slice(0, 6)}…${id.slice(-4)}`;
}

/** A SHA-256 as a short reference: its first eight hex digits, `9f4a6c1e…`. */
export function shortHash(hex: string): string {
  return hex.length <= 8 ? hex : `${hex.slice(0, 8)}…`;
}

/** A masked input value as the engine stores it: `hmac:<keyId>:<hex>`. */
export interface MaskedValue {
  keyId: string;
  /** The first six hex digits of the keyed hash: enough to tell two values apart, never the value. */
  reference: string;
  full: string;
}

const MASKED = /^hmac:(?:([^:]+):)?([0-9a-f]{16,})$/;

/** The masked form of a logged value, or undefined when the value is not a keyed hash. */
export function maskedValue(value: unknown): MaskedValue | undefined {
  if (typeof value !== 'string') return undefined;
  const match = MASKED.exec(value);
  if (!match) return undefined;
  return { keyId: match[1] ?? '', reference: (match[2] ?? '').slice(0, 6), full: value };
}

/** The period of a calendar month in UTC, as the decision search takes it: from inclusive, to exclusive. */
export function monthPeriod(month: string): { from: string; to: string } {
  const [year = 1970, number = 1] = month.split('-').map(Number);
  const from = new Date(Date.UTC(year, number - 1, 1));
  const to = new Date(Date.UTC(year, number, 1));
  return { from: from.toISOString().replace('.000', ''), to: to.toISOString().replace('.000', '') };
}

/** The month of an instant, `2026-11`, in UTC. */
export function monthOf(instant: Date): string {
  return `${String(instant.getUTCFullYear())}-${String(instant.getUTCMonth() + 1).padStart(2, '0')}`;
}

/** A month in words: `November 2026`, `Tháng 11 năm 2026`. */
export function monthLabel(month: string, locale: Locale): string {
  const { from } = monthPeriod(month);
  return new Intl.DateTimeFormat(locale === 'vi' ? 'vi-VN' : 'en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(from));
}

/** A file size for people: `812 bytes`, `4.7 KB`, `1.2 MB`, with the language's decimal mark. */
export function fileSize(bytes: number, locale: Locale): { value: string; unit: 'bytes' | 'KB' | 'MB' } {
  const format = (n: number) => new Intl.NumberFormat(locale === 'vi' ? 'vi-VN' : 'en-GB', { maximumFractionDigits: 1 }).format(n);
  if (bytes < 1024) return { value: format(bytes), unit: 'bytes' };
  if (bytes < 1024 * 1024) return { value: format(bytes / 1024), unit: 'KB' };
  return { value: format(bytes / (1024 * 1024)), unit: 'MB' };
}
