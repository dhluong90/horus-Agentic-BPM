import { bpmMeV1, type BpmMeV1 } from './generated/bpm-me.v1';

export type MeReading =
  | { ok: true; me: BpmMeV1; dropped: string[] }
  | { ok: false };

/** True when the published pattern accepts this one permission name; checked with the generated schema itself. */
function wellFormed(permission: string): boolean {
  return bpmMeV1.shape.permissions.safeParse([permission]).success;
}

/**
 * Reads a /api/bpm/me body as a tolerant reader (cockpit DESIGN section 5.2). A host can be newer than the
 * cockpit and list a permission name outside the pattern this cockpit knows; such a name is dropped, so the
 * user still signs in and the cockpit simply offers nothing for it. Everything else, including a permission
 * that is not a string and a repeated permission, is validated by the generated schema exactly as published.
 */
export function readMe(body: unknown): MeReading {
  let candidate = body;
  let dropped: string[] = [];
  if (typeof body === 'object' && body !== null && 'permissions' in body && Array.isArray(body.permissions)) {
    const permissions: unknown[] = body.permissions;
    dropped = permissions.filter((item): item is string => typeof item === 'string' && !wellFormed(item));
    candidate = { ...body, permissions: permissions.filter((item) => typeof item !== 'string' || wellFormed(item)) };
  }
  const parsed = bpmMeV1.safeParse(candidate);
  return parsed.success ? { ok: true, me: parsed.data, dropped } : { ok: false };
}

/** At most this many dropped names are printed, each cut to {@link NOTE_NAME_LENGTH} characters. */
export const NOTE_MAX_NAMES = 10;
export const NOTE_NAME_LENGTH = 64;

/**
 * A host-supplied name as a quoted literal: cut to {@link NOTE_NAME_LENGTH} code points, then JSON-escaped, with
 * the control and format characters JSON leaves alone (C1 controls, bidi overrides and isolates, zero-width
 * marks) escaped as well, so the note reads exactly as what it contains.
 */
function literal(name: string): string {
  const points = Array.from(name);
  const cut = points.length > NOTE_NAME_LENGTH ? `${points.slice(0, NOTE_NAME_LENGTH).join('')}…` : name;
  const escapeUnits = (char: string) => Array.from({ length: char.length }, (_, i) => `\\u${char.charCodeAt(i).toString(16).padStart(4, '0')}`).join('');
  return JSON.stringify(cut).replace(/[\p{Cc}\p{Cf}]/gu, escapeUnits);
}

/** The console note for the host's operators when /me lists permission names this cockpit cannot read. */
export function droppedPermissionsNote(dropped: readonly string[]): string {
  const shown = dropped.slice(0, NOTE_MAX_NAMES).map(literal).join(', ');
  const more = dropped.length > NOTE_MAX_NAMES ? ` and ${dropped.length - NOTE_MAX_NAMES} more` : '';
  return `/api/bpm/me lists ${dropped.length} permission name(s) this cockpit does not read; ignored: ${shown}${more}`;
}

/**
 * Warns once per page load for each distinct set of dropped names: /me is read again on a tenant switch and a
 * refresh, and a host that always lists the same unknown name must not fill the console.
 */
export function createDroppedPermissionsWarner(warn: (note: string) => void = (note) => { console.warn(note); }): (dropped: readonly string[]) => void {
  const warned = new Set<string>();
  return (dropped) => {
    if (dropped.length === 0) return;
    const key = JSON.stringify([...dropped].sort());
    if (warned.has(key)) return;
    warned.add(key);
    warn(droppedPermissionsNote(dropped));
  };
}
