import type { z } from 'zod';
import { cockpitHostsV1, cockpitHostsV1Refinements, type CockpitHostsV1 } from '../../api/generated/cockpit-hosts.v1';

export type HostRegistry = CockpitHostsV1;
export type RegistryHost = HostRegistry['hosts'][number];
export type HostLabel = RegistryHost['label'];

/** Why the registry cannot be used. `pointer` is the JSON Pointer of the first problem (DESIGN section 4). */
export type RegistryProblem =
  | { reason: 'unavailable'; status: number | undefined }
  | { reason: 'not-json' }
  | { reason: 'invalid'; pointer: string; message: string }
  | { reason: 'duplicate-id'; pointer: string; value: string }
  | { reason: 'duplicate-base-url'; pointer: string; value: string }
  | { reason: 'no-enabled-host' };

export type RegistryResult = { ok: true; registry: HostRegistry } | { ok: false; problem: RegistryProblem };

export const REGISTRY_FILE = 'cockpit-hosts.json';

/** RFC 6901 JSON Pointer; the whole document is the empty string. */
function toPointer(path: readonly PropertyKey[]): string {
  return path.map((segment) => `/${String(segment).replaceAll('~', '~0').replaceAll('/', '~1')}`).join('');
}

function firstIssue(error: z.ZodError): { pointer: string; message: string } {
  const issue = error.issues[0];
  if (!issue) return { pointer: '', message: 'Invalid value' };
  // An unknown key is reported on its parent; point at the key itself.
  const path = issue.code === 'unrecognized_keys' && issue.keys[0] !== undefined ? [...issue.path, issue.keys[0]] : issue.path;
  return { pointer: toPointer(path), message: issue.message };
}

export type SchemaRefinement = (typeof cockpitHostsV1Refinements)[number];

/**
 * The schema rules the generator cannot express (scripts/generate-schemas.mjs, KNOWN_GAPS), restored by
 * hand. The record type makes a new gap without an implementation a compile error; each one is proved
 * against the JSON Schema in src/api/contract.test.ts.
 */
export const SCHEMA_REFINEMENTS: Record<SchemaRefinement, (registry: HostRegistry) => RegistryProblem | undefined> = {
  'oidc-requires-issuer-and-client-id': (registry) => {
    if (registry.auth.mode !== 'oidc') return undefined;
    if (registry.auth.issuer === undefined) return { reason: 'invalid', pointer: '/auth/issuer', message: 'Required with mode oidc' };
    if (registry.auth.clientId === undefined) return { reason: 'invalid', pointer: '/auth/clientId', message: 'Required with mode oidc' };
    return undefined;
  },
  'label-map-requires-en': (registry) => {
    const index = registry.hosts.findIndex((host) => typeof host.label !== 'string' && host.label['en'] === undefined);
    return index < 0 ? undefined : { reason: 'invalid', pointer: `/hosts/${index}/label`, message: 'A label map needs an en entry' };
  },
  'label-map-locale-names': (registry) => {
    for (const [index, host] of registry.hosts.entries()) {
      if (typeof host.label === 'string') continue;
      const badLocale = Object.keys(host.label).find((locale) => !/^[a-z]{2}(-[A-Z]{2})?$/.test(locale));
      if (badLocale !== undefined) return { reason: 'invalid', pointer: `/hosts/${index}/label/${badLocale}`, message: 'Not a locale' };
    }
    return undefined;
  },
};

/** Rules beyond the schema: the duplicate rules of DESIGN section 4 and at least one enabled host. */
function checkDesignRules(registry: HostRegistry): RegistryProblem | undefined {
  const ids = new Set<string>();
  const baseUrls = new Set<string>();
  for (const [index, host] of registry.hosts.entries()) {
    if (ids.has(host.id)) return { reason: 'duplicate-id', pointer: `/hosts/${index}/id`, value: host.id };
    ids.add(host.id);
    if (baseUrls.has(host.baseUrl)) return { reason: 'duplicate-base-url', pointer: `/hosts/${index}/baseUrl`, value: host.baseUrl };
    baseUrls.add(host.baseUrl);
  }
  if (!registry.hosts.some((host) => host.enabled)) return { reason: 'no-enabled-host' };
  // Per-host tokens need each host's scope, or that host's token could not be requested (DESIGN section 4).
  if (registry.auth.mode === 'oidc' && registry.auth.tokenMode === 'per-host') {
    const index = registry.hosts.findIndex((host) => host.enabled && host.scope === undefined);
    if (index >= 0) return { reason: 'invalid', pointer: `/hosts/${String(index)}/scope`, message: 'Required with tokenMode per-host' };
  }
  return undefined;
}

/** Schema validation only: generated zod plus the refinements. Exported for the contract test. */
export function validateRegistrySchema(body: unknown): RegistryResult {
  const parsed = cockpitHostsV1.safeParse(body);
  if (!parsed.success) return { ok: false, problem: { reason: 'invalid', ...firstIssue(parsed.error) } };
  for (const name of cockpitHostsV1Refinements) {
    const problem = SCHEMA_REFINEMENTS[name](parsed.data);
    if (problem) return { ok: false, problem };
  }
  return { ok: true, registry: parsed.data };
}

export function parseRegistry(body: unknown): RegistryResult {
  const result = validateRegistrySchema(body);
  if (!result.ok) return result;
  const problem = checkDesignRules(result.registry);
  return problem ? { ok: false, problem } : result;
}

/**
 * The cockpit's own directory as an absolute URL. Every page of the build names it in a meta element (the sign-in
 * callback page sits two levels down), so the registry is read from the same place on every page.
 */
export function cockpitBase(doc: Document = document): URL {
  const root = doc.querySelector('meta[name="horus-cockpit-root"]')?.getAttribute('content') ?? './';
  return new URL(root, doc.baseURI);
}

/** Loads the registry served next to index.html. Never throws. */
export async function loadRegistry(fetchImpl: typeof fetch = fetch): Promise<RegistryResult> {
  let response: Response;
  try {
    response = await fetchImpl(new URL(REGISTRY_FILE, cockpitBase()).toString(), { cache: 'no-store', credentials: 'same-origin', redirect: 'manual' });
  } catch {
    return { ok: false, problem: { reason: 'unavailable', status: undefined } };
  }
  if (!response.ok) return { ok: false, problem: { reason: 'unavailable', status: response.status || undefined } };
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { ok: false, problem: { reason: 'not-json' } };
  }
  return parseRegistry(body);
}

/** Embedded mode works with one host: the first enabled one. */
export function selectHost(registry: HostRegistry): RegistryHost | undefined {
  return registry.hosts.find((host) => host.enabled);
}

export function hostLabel(label: HostLabel, locale: string): string {
  if (typeof label === 'string') return label;
  return label[locale] ?? label['en'] ?? '';
}
