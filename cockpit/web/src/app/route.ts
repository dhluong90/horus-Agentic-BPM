import { useCallback, useSyncExternalStore } from 'react';

/*
 * Where the user is, kept in the URL hash so a link opens the same screen inside any host path
 * (the starter serves the cockpit at /bpm/cockpit/). Only navigation lives here; drafts stay in memory.
 *
 * Rule screens live under #/rules. The older #/rule-sets prefix still parses, so existing links keep working.
 */

/**
 * `edit` is the grid editor of a draft; on any other version it opens the table, read only. `simulate` runs samples
 * against a draft; on any other version it says simulation is for drafts. `replay` runs the logged decisions of a period
 * against a draft, on a host that announces it.
 */
export const LENSES = ['table', 'readings', 'changes', 'report', 'history', 'source', 'edit', 'simulate', 'replay'] as const;
export type Lens = (typeof LENSES)[number];

/** The views of one process definition version: its diagram, its steps as a list, what it changes, and the cases of its key. */
export const PROCESS_LENSES = ['diagram', 'steps', 'changes', 'cases', 'timing'] as const;
export type ProcessLens = (typeof PROCESS_LENSES)[number];

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
  | { name: 'process'; key: string; version: number; lens?: ProcessLens | undefined }
  | { name: 'migrate'; key: string; version: number; from?: number | undefined }
  | { name: 'migration'; migrationId: string }
  | { name: 'calendars' }
  | { name: 'calendar'; calendarKey: string }
  | { name: 'calendar-changes' }
  | { name: 'calendar-change'; changeId: string }
  | { name: 'instance'; instanceId: string }
  | { name: 'tasks'; view?: QueueTab | undefined; taskId?: string | undefined; overdue?: boolean | undefined; scope?: Scope | undefined; on?: string | undefined }
  | { name: 'task'; taskId: string }
  | { name: 'incidents'; view?: IncidentTab | undefined; incidentId?: string | undefined; process?: string | undefined; version?: number | undefined; step?: string | undefined; scope?: Scope | undefined; on?: string | undefined };

/**
 * `all` is the merged list across the hosts the user can reach (CK-F-28, CK-F-29); absent is the host on screen.
 * In a merged list `on` names the host of the selected row, since an id alone does not say where it lives.
 */
export type Scope = 'all';

/** A host id as the registry writes it; anything else in a link is ignored. */
const HOST_ID = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

function scopeOf(params: URLSearchParams): { scope?: Scope; on?: string } {
  if (params.get('scope') !== 'all') return {};
  const on = params.get('on') ?? undefined;
  return { scope: 'all', ...(on !== undefined && HOST_ID.test(on) && on.length <= 40 ? { on } : {}) };
}

function scopeParams(params: URLSearchParams, route: { scope?: Scope | undefined; on?: string | undefined }): void {
  if (route.scope !== 'all') return;
  params.set('scope', 'all');
  if (route.on !== undefined) params.set('on', route.on);
}

/** The tabs of the incident queue (CKP-008): incidents to fix, and resolutions waiting for a second person (PD-126). */
export const INCIDENT_TABS = ['open', 'pending'] as const;
export type IncidentTab = (typeof INCIDENT_TABS)[number];

/** The tabs of the task queue, kept in the URL so a reload or a link opens the same tab and task. */
export const QUEUE_TABS = ['mine', 'candidate', 'all'] as const;
export type QueueTab = (typeof QUEUE_TABS)[number];

/** The menu areas. Every route belongs to exactly one. */
export const AREAS = ['home', 'rules', 'approvals', 'decisions', 'processes', 'tasks', 'incidents'] as const;
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
    case 'migrate':
    case 'migration':
    case 'calendars':
    case 'calendar':
    case 'calendar-changes':
    case 'calendar-change':
    case 'instance':
      return 'processes';
    case 'task':
      return 'tasks';
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

function isProcessLens(value: string | undefined): value is ProcessLens {
  return value !== undefined && (PROCESS_LENSES as readonly string[]).includes(value);
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
    // #/processes/<key>/<version>[/<lens>]: one version, or the cases of its key. A key without a version number opens the list.
    if (version === undefined || !/^[1-9]\d*$/.test(version)) return { name: 'processes' };
    // #/processes/<key>/<target version>/move[?from=<version>]: plan moving the running cases of an older version here.
    if (lens === 'move') {
      const from = new URLSearchParams(search).get('from') ?? '';
      return /^[1-9]\d*$/.test(from) ? { name: 'migrate', key: ruleSet, version: Number(version), from: Number(from) } : { name: 'migrate', key: ruleSet, version: Number(version) };
    }
    return isProcessLens(lens) ? { name: 'process', key: ruleSet, version: Number(version), lens } : { name: 'process', key: ruleSet, version: Number(version) };
  }
  if (area === 'migrations' && ruleSet !== undefined) return { name: 'migration', migrationId: ruleSet };
  if (area === 'calendars') return ruleSet !== undefined ? { name: 'calendar', calendarKey: ruleSet } : { name: 'calendars' };
  if (area === 'calendar-changes') return ruleSet !== undefined ? { name: 'calendar-change', changeId: ruleSet } : { name: 'calendar-changes' };
  if (area === 'instances' && ruleSet !== undefined) return { name: 'instance', instanceId: ruleSet };
  if (area === 'tasks' && ruleSet !== undefined) return { name: 'task', taskId: ruleSet };
  if (area === 'tasks') return queueRoute(new URLSearchParams(search));
  if (area === 'incidents') return incidentRoute(new URLSearchParams(search));
  if (area === 'home' || area === 'approvals' || area === 'decisions' || area === 'processes') return { name: area };
  return { name: 'home' };
}

/** A task id is 26 upper-case letters and digits; anything else in the link is ignored, never requested. */
const TASK_ID = /^[0-9A-Z]{26}$/;

function queueRoute(params: URLSearchParams): Route {
  const view = params.get('view') ?? undefined;
  const task = params.get('task') ?? undefined;
  return {
    name: 'tasks',
    ...(view !== undefined && (QUEUE_TABS as readonly string[]).includes(view) ? { view: view as QueueTab } : {}),
    ...(task !== undefined && TASK_ID.test(task) ? { taskId: task } : {}),
    // The overdue filter (CKP-050) is kept in the link, so a shared link opens the same view.
    ...(params.get('overdue') === '1' ? { overdue: true } : {}),
    ...scopeOf(params),
  };
}

/** An incident id is a ULID like a task id; anything else in the link is ignored, never requested. */
function incidentRoute(params: URLSearchParams): Route {
  const view = params.get('view') ?? undefined;
  const incident = params.get('incident') ?? undefined;
  return {
    name: 'incidents',
    ...(view !== undefined && (INCIDENT_TABS as readonly string[]).includes(view) ? { view: view as IncidentTab } : {}),
    ...(incident !== undefined && TASK_ID.test(incident) ? { incidentId: incident } : {}),
    ...incidentFilter(params),
    ...scopeOf(params),
  };
}

/** A process key or step id as the engine writes it; anything else in a link is ignored, never requested. */
const NAME = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;

/** The process, version and step an incident list is narrowed to (#91); a version needs its process. */
function incidentFilter(params: URLSearchParams): { process?: string; version?: number; step?: string } {
  const process = params.get('process') ?? undefined;
  if (process === undefined || !NAME.test(process)) return {};
  const version = params.get('version') ?? '';
  const step = params.get('step') ?? undefined;
  return { process, ...(/^[1-9]\d{0,8}$/.test(version) ? { version: Number(version) } : {}), ...(step !== undefined && NAME.test(step) ? { step } : {}) };
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
      return `#/processes/${encodeURIComponent(route.key)}/${String(route.version)}${route.lens !== undefined ? `/${route.lens}` : ''}`;
    case 'migrate':
      return `#/processes/${encodeURIComponent(route.key)}/${String(route.version)}/move${route.from !== undefined ? `?from=${String(route.from)}` : ''}`;
    case 'migration':
      return `#/migrations/${encodeURIComponent(route.migrationId)}`;
    case 'calendar':
      return `#/calendars/${encodeURIComponent(route.calendarKey)}`;
    case 'calendar-change':
      return `#/calendar-changes/${encodeURIComponent(route.changeId)}`;
    case 'instance':
      return `#/instances/${encodeURIComponent(route.instanceId)}`;
    case 'task':
      return `#/tasks/${encodeURIComponent(route.taskId)}`;
    case 'tasks': {
      const params = new URLSearchParams();
      if (route.view !== undefined) params.set('view', route.view);
      if (route.overdue === true) params.set('overdue', '1');
      if (route.taskId !== undefined) params.set('task', route.taskId);
      scopeParams(params, route);
      const text = params.toString();
      return text === '' ? '#/tasks' : `#/tasks?${text}`;
    }
    case 'incidents': {
      const params = new URLSearchParams();
      if (route.view !== undefined) params.set('view', route.view);
      if (route.incidentId !== undefined) params.set('incident', route.incidentId);
      if (route.process !== undefined) {
        params.set('process', route.process);
        if (route.version !== undefined) params.set('version', String(route.version));
        if (route.step !== undefined) params.set('step', route.step);
      }
      scopeParams(params, route);
      const text = params.toString();
      return text === '' ? '#/incidents' : `#/incidents?${text}`;
    }
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
