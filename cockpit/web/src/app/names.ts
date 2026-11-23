import { z } from 'zod';

/*
 * Human names for the keys screens used to show (#510). A name the API returned wins; without one the key is
 * written in words ("card-fee" as "Card fee") and the gap is logged once, so a host operator can fix the data.
 * The key itself stays one click away (a copy chip or the tenant menu), never the title.
 */

/** A key in words: separators become spaces and the first letter is a capital. */
export function titleFromKey(key: string): string {
  const words = key.replace(/[-_.]+/gu, ' ').replace(/\s+/gu, ' ').trim();
  return words === '' ? key : words.charAt(0).toLocaleUpperCase() + words.slice(1);
}

const warned = new Set<string>();

/** Logs once per kind and key that an API answered no display name. */
export function warnMissingName(kind: string, key: string, warn: (note: string) => void = (note) => { console.warn(note); }): void {
  const id = `${kind}\u0000${key}`;
  if (warned.has(id)) return;
  warned.add(id);
  warn(`No display name for ${kind} "${key}"; showing the key in words. Data warning for the host operator.`);
}

/** Test hook: forgets which gaps were already logged. */
export function resetNameWarnings(): void {
  warned.clear();
}

const supplied = z.object({ displayName: z.string().trim().min(1) }).loose();

/** The name to show for a key: the one the API supplied on `source` (as `displayName`), else the key in words. */
export function nameOf(kind: string, key: string, source?: unknown): string {
  const parsed = supplied.safeParse(source);
  if (parsed.success) return parsed.data.displayName;
  warnMissingName(kind, key);
  return titleFromKey(key);
}

/** A tenant's name from /me `tenant.names`, else its id in words (logged once). */
export function tenantName(names: Readonly<Record<string, string>> | undefined, id: string): string {
  const name = names?.[id];
  if (name !== undefined && name.trim() !== '') return name;
  warnMissingName('tenant', id);
  return titleFromKey(id);
}
