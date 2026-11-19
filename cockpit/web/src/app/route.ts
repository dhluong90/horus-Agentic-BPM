import { useCallback, useSyncExternalStore } from 'react';

/*
 * Where the user is, kept in the URL hash so a link opens the same screen inside any host path
 * (the starter serves the cockpit at /bpm/cockpit/). Only navigation lives here; drafts stay in memory.
 */

/** The app's areas. The rules area keeps its own sub-path (features/rules/routes.ts), as under /rules. */
export type Route = { name: 'rules'; path: string } | { name: 'service' };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, '');
  if (/^\/?service\b/.test(path)) return { name: 'service' };
  // "#/rules/card-fee/2"; "#/rules/..." is the earlier form and still opens.
  const rules = /^\/?(?:rules|rule-sets)(\/.*)?$/.exec(path);
  return { name: 'rules', path: rules?.[1] ?? '/' };
}

export function formatRoute(route: Route): string {
  return route.name === 'service' ? '#/service' : `#/rules${route.path === '/' ? '' : route.path}`;
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
