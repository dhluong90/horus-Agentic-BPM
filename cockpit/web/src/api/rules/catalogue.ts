import { HostCallError } from '../hostError';
import type { RulesClient } from './rulesClient';
import type { RuleSetVersion } from './schemas';

/*
 * Interim rule set discovery. The engine API of this release has no GET /rule-sets and no list of versions
 * (both are in rule-engine DESIGN section 4.2). Until they ship, the cockpit remembers the rule sets that
 * this user opened or imported in this browser, per host, tenant and user, and finds versions by reading
 * 1, 2, 3 ... and stopping at the first 404: a rule set with n versions costs n + 1 reads. Replace this
 * module, and nothing else, when the list endpoints arrive.
 */

/** A guard against a misbehaving host, never reached in practice: reading always stops at the first 404. */
const MAX_VERSIONS = 1000;

/** Whose list it is: the remembered names are kept per host, tenant and user, never shared between users. */
export interface ShelfScope {
  hostId: string;
  tenant: string;
  userId: string;
}
const NAME = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

export function isRuleSetName(value: string): boolean {
  return NAME.test(value) && value.length <= 100;
}

export function shelfKey({ hostId, tenant, userId }: ShelfScope): string {
  return ['horus.cockpit.ruleSets', hostId, tenant, userId].map(encodeURIComponent).join('.');
}

export function knownRuleSets(scope: ShelfScope, storage: Storage = window.localStorage): string[] {
  try {
    const parsed: unknown = JSON.parse(storage.getItem(shelfKey(scope)) ?? '[]');
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((value): value is string => typeof value === 'string' && isRuleSetName(value)).sort();
  } catch {
    return [];
  }
}

export function rememberRuleSet(scope: ShelfScope, name: string, storage: Storage = window.localStorage): void {
  if (!isRuleSetName(name)) return;
  const names = new Set(knownRuleSets(scope, storage));
  names.add(name);
  try {
    storage.setItem(shelfKey(scope), JSON.stringify([...names].sort()));
  } catch {
    // Storage full or disabled: the rule set still opens, it is only not listed next time.
  }
}

function isNotFound(error: unknown): boolean {
  return error instanceof HostCallError && error.hostError.kind === 'problem' && error.hostError.status === 404;
}

/** Every version of a rule set, oldest first. An unknown rule set gives an empty list. */
export async function listVersions(client: RulesClient, name: string, signal?: AbortSignal): Promise<RuleSetVersion[]> {
  const versions: RuleSetVersion[] = [];
  for (let version = 1; version <= MAX_VERSIONS; version += 1) {
    try {
      versions.push(await client.getVersion(name, version, signal ? { signal } : {}));
    } catch (error) {
      if (isNotFound(error)) break;
      throw error;
    }
  }
  return versions;
}

export interface RuleSetSummary {
  name: string;
  latest: RuleSetVersion;
  published: RuleSetVersion | undefined;
  pending: RuleSetVersion | undefined;
}

/** The newest version with the given status, or undefined. */
export function newestWith(versions: readonly RuleSetVersion[], status: RuleSetVersion['status']): RuleSetVersion | undefined {
  return [...versions].reverse().find((v) => v.status === status);
}

export function summarise(name: string, versions: readonly RuleSetVersion[]): RuleSetSummary | undefined {
  const latest = versions.at(-1);
  if (!latest) return undefined;
  return { name, latest, published: newestWith(versions, 'PUBLISHED'), pending: newestWith(versions, 'PENDING_APPROVAL') };
}
