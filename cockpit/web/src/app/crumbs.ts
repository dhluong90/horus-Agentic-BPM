import { useSyncExternalStore } from 'react';

/*
 * A label for the last breadcrumb that a screen knows better than the route: the rule editor says
 * "New draft" while editing, or "Version 3 draft" for a numbered draft, never the published version.
 */
let label: string | undefined;
const listeners = new Set<() => void>();

export function setCrumbLabel(next: string | undefined): void {
  if (next === label) return;
  label = next;
  for (const listener of listeners) listener();
}

export function useCrumbLabel(): string | undefined {
  return useSyncExternalStore(
    (onChange) => { listeners.add(onChange); return () => { listeners.delete(onChange); }; },
    () => label,
    () => undefined,
  );
}
