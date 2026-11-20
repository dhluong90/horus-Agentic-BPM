import { bpmMetaV1, type BpmMetaV1 } from './generated/bpm-meta.v1';

export type MetaReading =
  | { ok: true; meta: BpmMetaV1; skipped: string[] }
  | { ok: false };

/** The modules this cockpit knows, taken from the generated schema so the two never drift apart. */
const KNOWN_MODULES: ReadonlySet<string> = new Set(bpmMetaV1.shape.modules.element.shape.module.options);

/** True for a module entry the cockpit can skip: an object whose `module` is a string it does not know. */
function unknownModule(entry: unknown): entry is { module: string } {
  return typeof entry === 'object' && entry !== null && 'module' in entry && typeof entry.module === 'string' && !KNOWN_MODULES.has(entry.module);
}

/**
 * Reads a /api/bpm/meta body as a tolerant reader (cockpit DESIGN section 5.2). A host can be newer than the
 * cockpit and report an engine module this cockpit does not know; that entry is skipped, so the engines the
 * cockpit knows still show. Every other entry, including one without a string `module`, is validated by the
 * generated schema exactly as published, and a host left with no known module is still an unexpected response.
 */
export function readMeta(body: unknown): MetaReading {
  let candidate = body;
  let skipped: string[] = [];
  if (typeof body === 'object' && body !== null && 'modules' in body && Array.isArray(body.modules)) {
    const modules: unknown[] = body.modules;
    skipped = modules.filter(unknownModule).map((entry) => entry.module);
    candidate = { ...body, modules: modules.filter((entry) => !unknownModule(entry)) };
  }
  const parsed = bpmMetaV1.safeParse(candidate);
  return parsed.success ? { ok: true, meta: parsed.data, skipped } : { ok: false };
}

/** At most this many skipped names are printed, each cut to {@link NOTE_NAME_LENGTH} characters. */
export const NOTE_MAX_NAMES = 10;
export const NOTE_NAME_LENGTH = 64;

/**
 * A host-supplied name as a quoted literal: cut to {@link NOTE_NAME_LENGTH} code points, then JSON-escaped, with
 * the control and format characters JSON leaves alone (bidi overrides, zero-width marks) escaped as well.
 */
function literal(name: string): string {
  const points = Array.from(name);
  const cut = points.length > NOTE_NAME_LENGTH ? `${points.slice(0, NOTE_NAME_LENGTH).join('')}…` : name;
  const escapeUnits = (char: string) => Array.from({ length: char.length }, (_, i) => `\\u${char.charCodeAt(i).toString(16).padStart(4, '0')}`).join('');
  return JSON.stringify(cut).replace(/[\p{Cc}\p{Cf}]/gu, escapeUnits);
}

/** The console note for the host's operators when /meta reports modules this cockpit does not know. */
export function skippedModulesNote(skipped: readonly string[]): string {
  const shown = skipped.slice(0, NOTE_MAX_NAMES).map(literal).join(', ');
  const more = skipped.length > NOTE_MAX_NAMES ? ` and ${skipped.length - NOTE_MAX_NAMES} more` : '';
  return `/api/bpm/meta reports ${skipped.length} module(s) this cockpit does not know; skipped: ${shown}${more}`;
}

/**
 * Warns once per page load for each distinct set of skipped modules: /meta is read again on a refresh, and a
 * host that always reports the same unknown module must not fill the console.
 */
export function createSkippedModulesWarner(warn: (note: string) => void = (note) => { console.warn(note); }): (skipped: readonly string[]) => void {
  const warned = new Set<string>();
  return (skipped) => {
    if (skipped.length === 0) return;
    const key = JSON.stringify([...skipped].sort());
    if (warned.has(key)) return;
    warned.add(key);
    warn(skippedModulesNote(skipped));
  };
}
