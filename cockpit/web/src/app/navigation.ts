import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import type { Area } from './route';

/**
 * Permissions that make the rule areas useful in the cockpit (PD-093). `rules:evaluate` is for the host's own
 * calls, such as a teller application, and opens nothing here.
 */
const RULE_PERMISSIONS: ReadonlySet<string> = new Set(['rules:read', 'rules:author', 'rules:approve', 'rules:log:read']);

/** Permissions that open the task queue: working tasks, or managing every task of the tenant (PD-110). */
const TASK_PERMISSIONS: ReadonlySet<string> = new Set(['processes:task:work', 'processes:task:manage']);

/** The permission to read the decision log; Decisions holds its data, so the area shows only with it. */
const DECISION_LOG = 'rules:log:read';

/**
 * The menu (PD-093): Home always; Rules and Approvals for anyone with a rule permission on a host that runs the
 * Rule Engine; Decisions only for those who may read the decision log (an area is hidden, never shown disabled,
 * when it holds data the user may not read); Processes only where a Process Engine is registered. A user with no
 * rule permission sees Home only. Nothing depends on a role name.
 */
export function availableAreas(meta: BpmMetaV1, me: BpmMeV1): Area[] {
  const modules = new Set(meta.modules.map((module) => module.module));
  const rules = modules.has('rules') && me.permissions.some((permission) => RULE_PERMISSIONS.has(permission));
  const processes = modules.has('processes') && (rules || me.permissions.some((permission) => permission.startsWith('processes:')));
  // Tasks holds the queue: shown where a Process Engine runs and the user may work or manage tasks (#71).
  const tasks = modules.has('processes') && me.permissions.some((permission) => TASK_PERMISSIONS.has(permission));
  return [
    'home',
    ...(rules ? (['rules', 'approvals'] as const) : []),
    ...(rules && me.permissions.includes(DECISION_LOG) ? (['decisions'] as const) : []),
    ...(processes ? (['processes'] as const) : []),
    ...(tasks ? (['tasks'] as const) : []),
  ];
}

/**
 * Areas shown with a plain "Preview" label: Processes and Tasks, whose engine is not released, and Decisions, whose
 * read API arrives with input masking in 0.2.0 (decision records can hold personal data).
 */
export const PREVIEW_AREAS: ReadonlySet<Area> = new Set<Area>(['decisions', 'processes', 'tasks']);

/**
 * Values of the host setting `cockpit.landing` (PD-092): `home` (the default), `rules` or `approvals`.
 * `decisions` becomes a value in 0.2.0, when Decisions has data.
 */
const LANDING: Readonly<Record<string, Area>> = { home: 'home', rules: 'rules', approvals: 'approvals' };

/** The host setting `cockpit.landing`, from the settings of any module in /meta. */
export function landingSetting(meta: BpmMetaV1): string | undefined {
  for (const module of meta.modules) {
    const value = module.settings?.['cockpit.landing'];
    if (typeof value === 'string') return value;
  }
  return undefined;
}

/**
 * The area the cockpit opens on when no screen is linked: Home for every role, whose content depends on the
 * role (PD-092), unless the host sets `cockpit.landing`. An area this user may not use, or an unknown value,
 * falls back to Home. A link into a screen always wins.
 */
export function landingArea(meta: BpmMetaV1, available: readonly Area[]): Area {
  const setting = landingSetting(meta);
  const area = setting === undefined ? undefined : LANDING[setting];
  return area !== undefined && available.includes(area) ? area : 'home';
}

/** A note for the browser console when the host's `cockpit.landing` is retired (`by-role`) or unknown. */
export function landingNote(meta: BpmMetaV1): string | undefined {
  const setting = landingSetting(meta);
  if (setting === undefined || setting in LANDING) return undefined;
  return `cockpit.landing "${setting}" is ${setting === 'by-role' ? 'retired' : 'not a known value'}; the cockpit opens on Home (PD-092).`;
}
