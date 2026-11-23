import type { Locale } from '../i18n/I18nProvider';

/**
 * Display preferences only. No token, user data or host data is ever stored in the browser
 * (cockpit DESIGN section 5.1).
 */
const LOCALE_KEY = 'horus.cockpit.locale';
const THEME_KEY = 'horus.cockpit.theme';
const MENU_KEY = 'horus.cockpit.menu';

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

/** Whether the left menu shows icons only. */
export function readMenuCollapsed(): boolean {
  return read(MENU_KEY) === 'collapsed';
}

export function storeMenuCollapsed(collapsed: boolean): void {
  write(MENU_KEY, collapsed ? 'collapsed' : 'expanded');
}

const READING_STYLE_KEY = 'horus.cockpit.readingStyle';

/** How rule readings show: the engine's full sentence or its short form. A display preference only. */
export type ReadingStyle = 'full' | 'short';

export function readStoredReadingStyle(): ReadingStyle | undefined {
  const value = read(READING_STYLE_KEY);
  return value === 'full' || value === 'short' ? value : undefined;
}

export function storeReadingStyle(style: ReadingStyle): void {
  write(READING_STYLE_KEY, style);
}

const INSTANCE_PANEL_KEY = 'horus.cockpit.instancePanel';

/** Whether this viewer last left the case's step panel open or closed; undefined until they choose. A display preference only. */
export function readInstancePanelOpen(): boolean | undefined {
  const value = read(INSTANCE_PANEL_KEY);
  return value === 'open' ? true : value === 'collapsed' ? false : undefined;
}

export function storeInstancePanelOpen(open: boolean): void {
  write(INSTANCE_PANEL_KEY, open ? 'open' : 'collapsed');
}
