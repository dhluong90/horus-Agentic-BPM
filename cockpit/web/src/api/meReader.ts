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

/** The console note for the host's operators when /me lists permission names this cockpit cannot read. */
export function droppedPermissionsNote(dropped: readonly string[]): string {
  return `/api/bpm/me lists ${dropped.length} permission name(s) this cockpit does not read; ignored: ${dropped.join(', ')}`;
}
