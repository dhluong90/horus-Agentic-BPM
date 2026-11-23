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

/*
 * A whole trail for a screen whose parents only it knows, such as a case: its process name and version come from
 * the instance it loads. Until the screen sets it, the breadcrumb shows the area alone.
 */
export interface Crumb { label: string; href?: string }
let trail: readonly Crumb[] | undefined;
const trailListeners = new Set<() => void>();

export function setCrumbTrail(next: readonly Crumb[] | undefined): void {
  if (next === trail || (next !== undefined && trail !== undefined && JSON.stringify(next) === JSON.stringify(trail))) return;
  trail = next;
  for (const listener of trailListeners) listener();
}

export function useCrumbTrail(): readonly Crumb[] | undefined {
  return useSyncExternalStore(
    (onChange) => { trailListeners.add(onChange); return () => { trailListeners.delete(onChange); }; },
    () => trail,
    () => undefined,
  );
}

/** The display name of the rule set on screen, which only its page knows; the breadcrumb shows it instead of the key (#510). */
let ruleSetName: string | undefined;
const nameListeners = new Set<() => void>();

export function setCrumbRuleSetName(next: string | undefined): void {
  if (next === ruleSetName) return;
  ruleSetName = next;
  for (const listener of nameListeners) listener();
}

export function useCrumbRuleSetName(): string | undefined {
  return useSyncExternalStore(
    (onChange) => { nameListeners.add(onChange); return () => { nameListeners.delete(onChange); }; },
    () => ruleSetName,
    () => undefined,
  );
}
