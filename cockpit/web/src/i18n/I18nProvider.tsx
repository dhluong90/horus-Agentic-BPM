import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { en, type MessageKey } from './en';
import { vi } from './vi';
import { readStoredLocale, storeLocale } from '../preferences/storage';

export const LOCALES = ['en', 'vi'] as const;
export type Locale = (typeof LOCALES)[number];

const DICTIONARIES: Record<Locale, Record<MessageKey, string>> = { en, vi };

export type Translate = (key: MessageKey, params?: Readonly<Record<string, string>>) => string;

interface I18nValue {
  locale: Locale;
  /** True when the user picked the language, so a registry default must not override it. */
  chosen: boolean;
  setLocale: (locale: Locale, options?: { remember: boolean }) => void;
  t: Translate;
}

const I18nContext = createContext<I18nValue | undefined>(undefined);

export function isLocale(value: unknown): value is Locale {
  return value === 'en' || value === 'vi';
}

export function format(template: string, params: Readonly<Record<string, string>> | undefined): string {
  if (!params) return template;
  return template.replaceAll(/\{(\w+)\}/g, (whole, name: string) => params[name] ?? whole);
}

export function I18nProvider({ children, initialLocale }: { children: ReactNode; initialLocale?: Locale }) {
  // Storage is read once, in the lazy initialiser.
  const [state, setState] = useState<{ locale: Locale; chosen: boolean }>(() => {
    const stored = readStoredLocale();
    return { locale: initialLocale ?? stored ?? 'en', chosen: stored !== undefined };
  });

  useEffect(() => {
    document.documentElement.lang = state.locale;
  }, [state.locale]);

  const setLocale = useCallback((locale: Locale, options: { remember: boolean } = { remember: true }) => {
    if (options.remember) storeLocale(locale);
    setState((current) => ({ locale, chosen: current.chosen || options.remember }));
  }, []);

  const value = useMemo<I18nValue>(() => {
    const dictionary = DICTIONARIES[state.locale];
    return {
      locale: state.locale,
      chosen: state.chosen,
      setLocale,
      t: (key, params) => format(dictionary[key], params),
    };
  }, [state, setLocale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const value = useContext(I18nContext);
  if (!value) throw new Error('useI18n must be used inside I18nProvider');
  return value;
}
