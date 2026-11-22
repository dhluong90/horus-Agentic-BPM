import { z } from 'zod';

/** The names of roles, groups and users a screen shows instead of identifiers (host contract: getDisplayNames, #442). */
export const displayNames = z
  .object({
    language: z.string(),
    roles: z.record(z.string(), z.string()),
    groups: z.record(z.string(), z.string()),
    users: z.record(z.string(), z.string()),
  })
  .loose();
export type DisplayNames = z.infer<typeof displayNames>;

export const DISPLAY_NAMES_PATH = '/api/bpm/display-names';

/** The identifiers to look up, by kind. */
export interface WantedNames {
  roles: readonly string[];
  groups: readonly string[];
  users: readonly string[];
}

export const NO_NAMES: DisplayNames = { language: 'und', roles: {}, groups: {}, users: {} };

/** The host answers for at most 100 identifiers of a kind (and only 1 to 128 letters, digits and . _ : @ -). */
export const NAMES_CHUNK = 100;
const SAFE_ID = /^[A-Za-z0-9._:@-]{1,128}$/;

/** The query string of one call: each kind comma separated, identifiers the host would refuse left out. */
export function namesQuery(wanted: Partial<WantedNames>): string {
  const query = new URLSearchParams();
  for (const kind of ['roles', 'groups', 'users'] as const) {
    const ids = (wanted[kind] ?? []).filter((id) => SAFE_ID.test(id));
    if (ids.length > 0) query.set(kind, ids.join(','));
  }
  return query.toString();
}

export function isEmpty(wanted: WantedNames): boolean {
  return wanted.roles.length + wanted.groups.length + wanted.users.length === 0;
}

/** Splits identifiers into calls of at most NAMES_CHUNK per kind. */
export function chunksOf(wanted: WantedNames): WantedNames[] {
  const count = Math.max(1, Math.ceil(Math.max(wanted.roles.length, wanted.groups.length, wanted.users.length) / NAMES_CHUNK));
  return Array.from({ length: count }, (_, index) => {
    const slice = (ids: readonly string[]) => ids.slice(index * NAMES_CHUNK, (index + 1) * NAMES_CHUNK);
    return { roles: slice(wanted.roles), groups: slice(wanted.groups), users: slice(wanted.users) };
  }).filter((chunk) => !isEmpty(chunk));
}

export function mergeNames(parts: readonly DisplayNames[]): DisplayNames {
  return {
    language: parts[0]?.language ?? 'und',
    roles: Object.assign({}, ...parts.map((part) => part.roles)) as Record<string, string>,
    groups: Object.assign({}, ...parts.map((part) => part.groups)) as Record<string, string>,
    users: Object.assign({}, ...parts.map((part) => part.users)) as Record<string, string>,
  };
}
