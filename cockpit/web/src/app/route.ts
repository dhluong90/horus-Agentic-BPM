import { useCallback, useSyncExternalStore } from 'react';

/*
 * Where the user is, kept in the URL hash so a link opens the same screen inside any host path
 * (the starter serves the cockpit at /bpm/cockpit/). Only navigation lives here; drafts stay in memory.
 */

export const LENSES = ['table', 'changes', 'source'] as const;
export type Lens = (typeof LENSES)[number];

export type Route =
  | { name: 'rule-sets' }
  | { name: 'rule-set'; ruleSet: string; version: number | undefined; lens: Lens | undefined }
  | { name: 'service' };

function isLens(value: string | undefined): value is Lens {
  return value !== undefined && (LENSES as readonly string[]).includes(value);
}

export function parseRoute(hash: string): Route {
  const parts = hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  const [area, ruleSet, version, lens] = parts;
  if (area === 'service') return { name: 'service' };
  if (area === 'rule-sets' && ruleSet !== undefined) {
    const number = version !== undefined && /^\d+$/.test(version) ? Number(version) : undefined;
    return { name: 'rule-set', ruleSet, version: number, lens: isLens(lens) ? lens : undefined };
  }
  return { name: 'rule-sets' };
}

export function formatRoute(route: Route): string {
  switch (route.name) {
    case 'service':
      return '#/service';
    case 'rule-sets':
      return '#/rule-sets';
    case 'rule-set': {
      const parts = ['rule-sets', encodeURIComponent(route.ruleSet)];
      if (route.version !== undefined) parts.push(String(route.version));
      if (route.version !== undefined && route.lens !== undefined) parts.push(route.lens);
      return `#/${parts.join('/')}`;
    }
  }
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener('hashchange', onChange);
  return () => {
    window.removeEventListener('hashchange', onChange);
  };
}

export function useRoute(): [Route, (route: Route) => void] {
  const hash = useSyncExternalStore(subscribe, () => window.location.hash, () => '');
  const navigate = useCallback((route: Route) => {
    window.location.hash = formatRoute(route);
  }, []);
  return [parseRoute(hash), navigate];
}
