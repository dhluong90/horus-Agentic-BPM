import type { Locale } from '../../../i18n/I18nProvider';
import type { Money } from '../../../api/rules/schemas';

/*
 * Money display without floating point. Amounts arrive as integer minor units ({currency, amount}) or as
 * decimal text in major units ("50000000 VND" in a rule cell). Digits are grouped as strings; no number
 * arithmetic touches an amount. Separators follow the language: 50,000,000 VND in English and 50.000.000 VND
 * in Vietnamese (ux-design section 9).
 */

const SEPARATORS: Record<Locale, { group: string; decimal: string }> = {
  en: { group: ',', decimal: '.' },
  vi: { group: '.', decimal: ',' },
};

/** Fraction digits of a currency (VND 0, USD 2), from the platform's currency data. */
export function currencyDigits(currency: string): number {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

function groupDigits(integer: string, separator: string): string {
  let out = '';
  for (let i = 0; i < integer.length; i += 1) {
    if (i > 0 && (integer.length - i) % 3 === 0) out += separator;
    out += integer.charAt(i);
  }
  return out;
}

/** Formats canonical decimal text ("-1234567.5") with the language's separators. Returns undefined for other text. */
export function formatDecimalText(text: string, locale: Locale): string | undefined {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text.trim());
  if (!match) return undefined;
  const [, sign = '', integer = '', fraction] = match;
  const { group, decimal } = SEPARATORS[locale];
  const grouped = groupDigits(integer.replace(/^0+(?=\d)/, ''), group);
  return `${sign === '-' ? '−' : ''}${grouped}${fraction !== undefined ? decimal + fraction : ''}`;
}

/** Formats integer minor units as major units with the currency code: {VND, 50000000} -> "50,000,000 VND". */
export function formatMoney(value: Money, locale: Locale): string {
  const digits = currencyDigits(value.currency);
  const negative = value.amount < 0;
  const absolute = BigInt(value.amount < 0 ? -value.amount : value.amount).toString();
  const padded = absolute.padStart(digits + 1, '0');
  const integer = digits === 0 ? padded : padded.slice(0, -digits);
  const fraction = digits === 0 ? '' : `.${padded.slice(-digits)}`;
  return `${formatDecimalText(`${negative ? '-' : ''}${integer}${fraction}`, locale) ?? ''} ${value.currency}`;
}

/** Live grouping while the user types an amount in major units; keeps an unfinished decimal part as typed. */
export function groupWhileTyping(text: string, locale: Locale): string {
  const { group, decimal } = SEPARATORS[locale];
  const cleaned = text.split(group).join('');
  const [integer = '', ...rest] = cleaned.split(decimal);
  if (!/^\d*$/.test(integer)) return text;
  const grouped = integer === '' ? '' : groupDigits(integer, group);
  return rest.length > 0 ? `${grouped}${decimal}${rest.join('')}` : grouped;
}

export type MinorUnits = { ok: true; amount: number } | { ok: false; reason: 'format' | 'scale' | 'range' };

/** Typed major units ("60,000,000" or "12.50") to integer minor units for the currency, exactly. */
export function toMinorUnits(text: string, currency: string, locale: Locale): MinorUnits {
  const { group, decimal } = SEPARATORS[locale];
  const cleaned = text.trim().split(group).join('').replace(decimal, '.');
  const match = /^(-?)(\d+)(?:\.(\d*))?$/.exec(cleaned);
  if (!match) return { ok: false, reason: 'format' };
  const [, sign = '', integer = '', fraction = ''] = match;
  const digits = currencyDigits(currency);
  if (fraction.replace(/0+$/, '').length > digits) return { ok: false, reason: 'scale' };
  const minor = BigInt(`${integer}${fraction.padEnd(digits, '0').slice(0, digits)}`) * (sign === '-' ? -1n : 1n);
  if (minor > BigInt(Number.MAX_SAFE_INTEGER) || minor < -BigInt(Number.MAX_SAFE_INTEGER)) return { ok: false, reason: 'range' };
  return { ok: true, amount: Number(minor) };
}
