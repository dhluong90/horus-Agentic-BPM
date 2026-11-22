import { useCallback, useSyncExternalStore } from 'react';

/*
 * Where the user is, kept in the URL hash so a link opens the same screen inside any host path
 * (the starter serves the cockpit at /bpm/cockpit/). Only navigation lives here; drafts stay in memory.
 *
 * Rule screens live under #/rules. The older #/rule-sets prefix still parses, so existing links keep working.
 */

/**
 * `edit` is the grid editor of a draft; on any other version it opens the table, read only. `simulate` runs samples
 * against a draft; on any other version it says simulation is for drafts.
 */
export const LENSES = ['table', 'readings', 'changes', 'history', 'source', 'edit', 'simulate'] as const;
export type Lens = (typeof LENSES)[number];

/** The views of one process definition version: its diagram, its steps as a list, what it changes, and the cases of its key. */
export const PROCESS_LENSES = ['diagram', 'steps', 'changes', 'cases'] as const;
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
  | { name: 'instance'; instanceId: string }
  | { name: 'tasks'; view?: QueueTab | undefined; taskId?: string | undefined; overdue?: boolean | undefined }
  | { name: 'task'; taskId: string }
  | { name: 'incidents'; view?: IncidentTab | undefined; incidentId?: string | undefined };

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
  const [path = '', search = ''] = hash.replace(/^#\/?/, '').split('?');
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
    return isProcessLens(lens) ? { name: 'process', key: ruleSet, version: Number(version), lens } : { name: 'process', key: ruleSet, version: Number(version) };
  }
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
  };
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
    case 'instance':
      return `#/instances/${encodeURIComponent(route.instanceId)}`;
    case 'task':
      return `#/tasks/${encodeURIComponent(route.taskId)}`;
    case 'tasks': {
      const params = new URLSearchParams();
      if (route.view !== undefined) params.set('view', route.view);
      if (route.overdue === true) params.set('overdue', '1');
      if (route.taskId !== undefined) params.set('task', route.taskId);
      const text = params.toString();
      return text === '' ? '#/tasks' : `#/tasks?${text}`;
    }
    case 'incidents': {
      const params = new URLSearchParams();
      if (route.view !== undefined) params.set('view', route.view);
      if (route.incidentId !== undefined) params.set('incident', route.incidentId);
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
