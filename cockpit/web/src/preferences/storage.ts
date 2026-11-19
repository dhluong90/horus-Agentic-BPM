import type { Locale } from '../i18n/I18nProvider';

/**
 * Display preferences only. No token, user data or host data is ever stored in the browser
 * (cockpit DESIGN section 5.1).
 */
const LOCALE_KEY = 'horus.cockpit.locale';
const THEME_KEY = 'horus.cockpit.theme';

export type Theme = 'light' | 'dark';

function read(key: string): string | undefined {
  try {
    return window.localStorage.getItem(key) ?? undefined;
  } catch {
    return undefined;
  }
}

function write(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Storage may be disabled by policy; the preference then lasts for this page only.
  }
}

export function readStoredLocale(): Locale | undefined {
  const value = read(LOCALE_KEY);
  return value === 'en' || value === 'vi' ? value : undefined;
}

export function storeLocale(locale: Locale): void {
  write(LOCALE_KEY, locale);
}

export function readStoredTheme(): Theme | undefined {
  const value = read(THEME_KEY);
  return value === 'light' || value === 'dark' ? value : undefined;
}

export function storeTheme(theme: Theme): void {
  write(THEME_KEY, theme);
}
