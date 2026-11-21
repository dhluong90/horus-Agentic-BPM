import { useCallback, useSyncExternalStore } from 'react';
import { readStoredReadingStyle, storeReadingStyle, type ReadingStyle } from './storage';

/*
 * The viewer's reading style, shared by every reading on screen and remembered in this browser. Until the
 * viewer chooses, the host's `rules.readings.style` setting decides (PD-077).
 */

const listeners = new Set<() => void>();
let chosen: ReadingStyle | undefined = readStoredReadingStyle();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Forgets the in-memory choice and reads the browser again; for tests. */
export function reloadReadingStyle(): void {
  chosen = readStoredReadingStyle();
  for (const listener of listeners) listener();
}

export function useReadingStyle(hostDefault: ReadingStyle): [ReadingStyle, (style: ReadingStyle) => void] {
  const stored = useSyncExternalStore(subscribe, () => chosen, () => chosen);
  const choose = useCallback((style: ReadingStyle) => {
    chosen = style;
    storeReadingStyle(style);
    for (const listener of listeners) listener();
  }, []);
  return [stored ?? hostDefault, choose];
}

/** The host's `rules.readings.style` (`sentence` or `shorthand`) as a reading style; anything else is the sentence. */
export function hostReadingStyle(settings: Readonly<Record<string, unknown>>): ReadingStyle {
  const value = settings['readings.style'] ?? settings['rules.readings.style'];
  return value === 'shorthand' ? 'short' : 'full';
}
