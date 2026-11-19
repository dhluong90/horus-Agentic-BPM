import { useCallback, useState } from 'react';
import { readStoredTheme, storeTheme, type Theme } from './storage';

function systemTheme(): Theme {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function initialTheme(): Theme {
  return readStoredTheme() ?? systemTheme();
}

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset['theme'] = theme;
}

export function useTheme(): { theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(() => document.documentElement.dataset['theme'] === 'dark' ? 'dark' : 'light');
  // Side effects run in the event handler, not in a state updater (StrictMode runs updaters twice).
  const toggle = useCallback(() => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    applyTheme(next);
    storeTheme(next);
    setTheme(next);
  }, [theme]);
  return { theme, toggle };
}
