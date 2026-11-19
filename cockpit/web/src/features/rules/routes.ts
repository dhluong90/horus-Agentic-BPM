import { createContext, useContext } from 'react';

/*
 * Routes inside the rules area, as a path below the area's mount point: "/", "/card-fee",
 * "/card-fee/2", "/card-fee/2/changes". The area does not know where it is mounted; the host shell
 * (hash routing in this app, or a path router at /rules) passes `navigate` and `href` for these paths.
 */

export const LENSES = ['table', 'changes', 'history', 'source'] as const;
export type Lens = (typeof LENSES)[number];

export type RulesRoute =
  | { name: 'list' }
  | { name: 'rule-set'; ruleSet: string; version: number | undefined; lens: Lens | undefined };

function isLens(value: string | undefined): value is Lens {
  return value !== undefined && (LENSES as readonly string[]).includes(value);
}

export function parseRulesPath(path: string): RulesRoute {
  const [ruleSet, version, lens] = path.split('/').filter(Boolean).map(decodeURIComponent);
  if (ruleSet === undefined) return { name: 'list' };
  const number = version !== undefined && /^\d+$/.test(version) ? Number(version) : undefined;
  return { name: 'rule-set', ruleSet, version: number, lens: number !== undefined && isLens(lens) ? lens : undefined };
}

export function formatRulesPath(route: RulesRoute): string {
  if (route.name === 'list') return '/';
  const parts = [encodeURIComponent(route.ruleSet)];
  if (route.version !== undefined) parts.push(String(route.version));
  if (route.version !== undefined && route.lens !== undefined) parts.push(route.lens);
  return `/${parts.join('/')}`;
}

export interface RulesNavigation {
  go: (route: RulesRoute) => void;
  href: (route: RulesRoute) => string;
}

export const RulesNavigationContext = createContext<RulesNavigation | undefined>(undefined);

export function useRulesNavigation(): RulesNavigation {
  const value = useContext(RulesNavigationContext);
  if (!value) throw new Error('useRulesNavigation must be used inside RulesArea');
  return value;
}
