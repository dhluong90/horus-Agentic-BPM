import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';
import type { Area } from './route';

/**
 * Which menu areas this user may use on this host. Each area needs its engine module in /meta and a
 * permission in /me; nothing depends on a role name. An area the user cannot use is not shown, so the
 * menu never offers a dead link.
 */
type Module = BpmMetaV1['modules'][number]['module'];

interface AreaRule {
  module: Module | undefined;
  /** Every permission in `all` and at least one in `any` (when given). */
  all: readonly string[];
  any?: readonly string[];
}

const AREA_RULES: Record<Area, AreaRule> = {
  home: { module: undefined, all: [] },
  rules: { module: 'rules', all: ['rules:read'] },
  // Approvers review others' requests; authors follow and withdraw their own.
  approvals: { module: 'rules', all: ['rules:read'], any: ['rules:approve', 'rules:author'] },
  decisions: { module: 'rules', all: ['rules:log:read'] },
  processes: { module: 'processes', all: ['processes:read'] },
};

/** Menu order. */
const ORDER: readonly Area[] = ['home', 'rules', 'approvals', 'decisions', 'processes'];

export function availableAreas(meta: BpmMetaV1, me: BpmMeV1): Area[] {
  const modules = new Set(meta.modules.map((module) => module.module));
  const granted = new Set(me.permissions);
  return ORDER.filter((area) => {
    const rule = AREA_RULES[area];
    if (rule.module !== undefined && !modules.has(rule.module)) return false;
    if (!rule.all.every((permission) => granted.has(permission))) return false;
    return rule.any === undefined || rule.any.some((permission) => granted.has(permission));
  });
}

/**
 * Areas shown with a plain "Preview" label: Processes, whose engine is not released, and Decisions, whose
 * read API arrives with input masking in 0.2.0 (decision records can hold personal data).
 */
export const PREVIEW_AREAS: ReadonlySet<Area> = new Set<Area>(['decisions', 'processes']);

/** Values of the host setting `cockpit.landing` (CKP-031) that name one area. */
const FIXED_LANDING: Readonly<Record<string, Area>> = { home: 'home', changes: 'approvals', 'rule-sets': 'rules', decisions: 'decisions', processes: 'processes' };

/** The host setting `cockpit.landing`, from the settings of any module in /meta. */
export function landingSetting(meta: BpmMetaV1): string | undefined {
  for (const module of meta.modules) {
    const value = module.settings?.['cockpit.landing'];
    if (typeof value === 'string') return value;
  }
  return undefined;
}

/** `by-role` (CKP-031): approvers and authors open on Approvals, auditors on Decisions, readers on Rules. */
function byRole(me: BpmMeV1, available: readonly Area[]): Area {
  const granted = new Set(me.permissions);
  const order: [boolean, Area][] = [
    [granted.has('rules:approve') || granted.has('rules:author'), 'approvals'],
    [granted.has('rules:log:read'), 'decisions'],
    [granted.has('rules:read'), 'rules'],
  ];
  return order.find(([applies, area]) => applies && available.includes(area))?.[1] ?? 'home';
}

/**
 * The area the cockpit opens on when no screen is linked. Home unless the host sets `cockpit.landing` in /meta:
 * `by-role`, or one fixed area. An area this user may not use falls back to Home. A link always wins (PD-078).
 */
export function landingArea(meta: BpmMetaV1, me: BpmMeV1, available: readonly Area[]): Area {
  const setting = landingSetting(meta);
  if (setting === undefined) return 'home';
  if (setting === 'by-role') return byRole(me, available);
  const area = FIXED_LANDING[setting];
  return area !== undefined && available.includes(area) ? area : 'home';
}
