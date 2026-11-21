import { useCallback, useSyncExternalStore } from 'react';

/*
 * Where the user is, kept in the URL hash so a link opens the same screen inside any host path
 * (the starter serves the cockpit at /bpm/cockpit/). Only navigation lives here; drafts stay in memory.
 *
 * Rule screens live under #/rules. The older #/rule-sets prefix still parses, so existing links keep working.
 */

/** `edit` is the grid editor of a draft; on any other version it opens the table, read only. */
export const LENSES = ['table', 'changes', 'history', 'source', 'edit'] as const;
export type Lens = (typeof LENSES)[number];

/**
 * A decision search kept in the URL, so a reload or a shared link shows the same list: one rule set and a
 * calendar month (`2026-11`), or one case reference. Nothing personal is ever put here (PD-108).
 */
export interface DecisionQuery {
  ruleSet?: string | undefined;
  month?: string | undefined;
  caseRef?: string | undefined;
}

export type Route =
  | { name: 'home' }
  | { name: 'rule-sets' }
  | { name: 'rule-set'; ruleSet: string; version: number | undefined; lens: Lens | undefined }
  | { name: 'approvals' }
  | { name: 'decisions'; query?: DecisionQuery | undefined }
  | { name: 'decision'; decisionId: string }
  | { name: 'processes' }
  | { name: 'process'; key: string; version: number }
  | { name: 'task'; taskId: string };

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
    case 'process':
    case 'task':
      return 'processes';
    case 'decision':
      return 'decisions';
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
  const [path = '', search = ''] = splitHost(hash).rest.replace(/^#\/?/, '').split('?');
  const parts = path.split('/').filter(Boolean).map(safeDecode);
  const [area, ruleSet, version, lens] = parts;
  if (area === undefined) return fallback;
  if (area === 'decisions') {
    if (ruleSet !== undefined) return { name: 'decision', decisionId: ruleSet };
    const query = decisionQuery(new URLSearchParams(search));
    return Object.values(query).some((value) => value !== undefined) ? { name: 'decisions', query } : { name: 'decisions' };
  }
  if (area === 'rules' || area === 'rule-sets') {
    if (ruleSet === undefined) return { name: 'rule-sets' };
    const number = version !== undefined && /^\d+$/.test(version) ? Number(version) : undefined;
    return { name: 'rule-set', ruleSet, version: number, lens: isLens(lens) ? lens : undefined };
  }
  if (area === 'processes' && ruleSet !== undefined) {
    // #/processes/<key>/<version>: one version's diagram. A key without a version number opens the list.
    return version !== undefined && /^[1-9]\d*$/.test(version) ? { name: 'process', key: ruleSet, version: Number(version) } : { name: 'processes' };
  }
  if (area === 'tasks' && ruleSet !== undefined) return { name: 'task', taskId: ruleSet };
  if (area === 'home' || area === 'approvals' || area === 'decisions' || area === 'processes') return { name: area };
  return { name: 'home' };
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

function decisionQuery(params: URLSearchParams): DecisionQuery {
  const read = (name: string) => {
    const value = params.get(name)?.trim();
    return value === undefined || value === '' ? undefined : value;
  };
  const month = read('month');
  return { ruleSet: read('ruleSet'), month: month !== undefined && MONTH.test(month) ? month : undefined, caseRef: read('caseRef') };
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
    case 'process':
      return `#/processes/${encodeURIComponent(route.key)}/${String(route.version)}`;
    case 'task':
      return `#/tasks/${encodeURIComponent(route.taskId)}`;
    case 'decisions': {
      const params = new URLSearchParams();
      const query = route.query ?? {};
      if (query.caseRef !== undefined) params.set('caseRef', query.caseRef);
      else if (query.ruleSet !== undefined) params.set('ruleSet', query.ruleSet);
      if (query.month !== undefined) params.set('month', query.month);
      const text = params.toString();
      return text === '' ? '#/decisions' : `#/decisions?${text}`;
    }
    case 'decision':
      return `#/decisions/${encodeURIComponent(route.decisionId)}`;
    default:
      return `#/${route.name}`;
  }
}

const HOST_PREFIX = /^#\/hosts\/([a-z][a-z0-9]*(?:-[a-z0-9]+)*)(?=\/|\?|$)\/?/;

/**
 * Standalone mode names the host first: `#/hosts/loans/rules` (cockpit DESIGN section 4). The rest is the route
 * within that host, so every screen parses and links as in embedded mode.
 */
export function splitHost(hash: string): { hostId: string | undefined; rest: string } {
  const match = HOST_PREFIX.exec(hash);
  if (!match) return { hostId: undefined, rest: hash === '' ? '' : hash };
  return { hostId: match[1], rest: `#/${hash.slice(match[0].length)}` };
}

/** The hash of `rest` within host `hostId`; without a host, `rest` unchanged. */
export function withHost(rest: string, hostId: string | undefined): string {
  if (hostId === undefined) return rest;
  const route = rest.replace(/^#\/?/, '');
  return `#/hosts/${hostId}${route === '' ? '' : `/${route}`}`;
}

/**
 * The host a link from a host screen names with `?host=` (PD-108: `#/decisions/{id}?host=loans`). Only a
 * well-formed id is read; nothing else in the link is.
 */
export function linkedHost(hash: string): string | undefined {
  const search = hash.split('?')[1];
  if (search === undefined) return undefined;
  const host = new URLSearchParams(search).get('host');
  return host !== null && /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/.test(host) && host.length <= 40 ? host : undefined;
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
    window.location.hash = withHost(formatRoute(route), splitHost(window.location.hash).hostId);
  }, []);
  return [parseRoute(hash, fallback), navigate];
}
