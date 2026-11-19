import { useCallback, useSyncExternalStore } from 'react';

/*
 * Where the user is, kept in the URL hash so a link opens the same screen inside any host path
 * (the starter serves the cockpit at /bpm/cockpit/). Only navigation lives here; drafts stay in memory.
 *
 * Rule screens live under #/rules. The older #/rule-sets prefix still parses, so existing links keep working.
 */

export const LENSES = ['table', 'changes', 'source'] as const;
export type Lens = (typeof LENSES)[number];

export type Route =
  | { name: 'home' }
  | { name: 'rule-sets' }
  | { name: 'rule-set'; ruleSet: string; version: number | undefined; lens: Lens | undefined }
  | { name: 'approvals' }
  | { name: 'decisions' }
  | { name: 'processes' };

/** The menu areas. Every route belongs to exactly one. */
export const AREAS = ['home', 'rules', 'approvals', 'decisions', 'processes'] as const;
export type Area = (typeof AREAS)[number];

export function isArea(value: unknown): value is Area {
  return typeof value === 'string' && (AREAS as readonly string[]).includes(value);
}

export function areaOf(route: Route): Area {
  switch (route.name) {
    case 'rule-sets':
    case 'rule-set':
      return 'rules';
    default:
      return route.name;
  }
}

/** The landing screen of an area. */
export function areaRoute(area: Area): Route {
  return area === 'rules' ? { name: 'rule-sets' } : { name: area };
}

function isLens(value: string | undefined): value is Lens {
  return value !== undefined && (LENSES as readonly string[]).includes(value);
}

/**
 * The route a hash names. An empty hash gives `fallback`, the landing area; an unknown hash gives Home,
 * never a blank screen.
 */
export function parseRoute(hash: string, fallback: Route = { name: 'home' }): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(safeDecode);
  const [area, ruleSet, version, lens] = parts;
  if (area === undefined) return fallback;
  if (area === 'rules' || area === 'rule-sets') {
    if (ruleSet === undefined) return { name: 'rule-sets' };
    const number = version !== undefined && /^\d+$/.test(version) ? Number(version) : undefined;
    return { name: 'rule-set', ruleSet, version: number, lens: isLens(lens) ? lens : undefined };
  }
  if (area === 'home' || area === 'approvals' || area === 'decisions' || area === 'processes') return { name: area };
  return { name: 'home' };
}

function safeDecode(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

export function formatRoute(route: Route): string {
  switch (route.name) {
    case 'rule-sets':
      return '#/rules';
    case 'rule-set': {
      const parts = ['rules', encodeURIComponent(route.ruleSet)];
      if (route.version !== undefined) parts.push(String(route.version));
      if (route.version !== undefined && route.lens !== undefined) parts.push(route.lens);
      return `#/${parts.join('/')}`;
    }
    default:
      return `#/${route.name}`;
  }
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener('hashchange', onChange);
  return () => {
    window.removeEventListener('hashchange', onChange);
  };
}

export function useRoute(fallback?: Route): [Route, (route: Route) => void] {
  const hash = useSyncExternalStore(subscribe, () => window.location.hash, () => '');
  const navigate = useCallback((route: Route) => {
    window.location.hash = formatRoute(route);
  }, []);
  return [parseRoute(hash, fallback), navigate];
}
