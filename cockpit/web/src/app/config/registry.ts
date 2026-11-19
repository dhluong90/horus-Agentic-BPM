import type { z } from 'zod';
import { cockpitHostsV1, type CockpitHostsV1 } from '../../api/generated/cockpit-hosts.v1';

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

/** Rules of the schema the generator cannot express, plus the duplicate rules of DESIGN section 4. */
function checkCrossFieldRules(registry: HostRegistry): RegistryProblem | undefined {
  if (registry.auth.mode === 'oidc') {
    if (registry.auth.issuer === undefined) return { reason: 'invalid', pointer: '/auth/issuer', message: 'Required with mode oidc' };
    if (registry.auth.clientId === undefined) return { reason: 'invalid', pointer: '/auth/clientId', message: 'Required with mode oidc' };
  }
  const ids = new Set<string>();
  const baseUrls = new Set<string>();
  for (const [index, host] of registry.hosts.entries()) {
    if (typeof host.label !== 'string') {
      if (host.label['en'] === undefined) return { reason: 'invalid', pointer: `/hosts/${index}/label`, message: 'A label map needs an en entry' };
      const badLocale = Object.keys(host.label).find((locale) => !/^[a-z]{2}(-[A-Z]{2})?$/.test(locale));
      if (badLocale !== undefined) return { reason: 'invalid', pointer: `/hosts/${index}/label/${badLocale}`, message: 'Not a locale' };
    }
    if (ids.has(host.id)) return { reason: 'duplicate-id', pointer: `/hosts/${index}/id`, value: host.id };
    ids.add(host.id);
    if (baseUrls.has(host.baseUrl)) return { reason: 'duplicate-base-url', pointer: `/hosts/${index}/baseUrl`, value: host.baseUrl };
    baseUrls.add(host.baseUrl);
  }
  if (!registry.hosts.some((host) => host.enabled)) return { reason: 'no-enabled-host' };
  return undefined;
}

export function parseRegistry(body: unknown): RegistryResult {
  const parsed = cockpitHostsV1.safeParse(body);
  if (!parsed.success) return { ok: false, problem: { reason: 'invalid', ...firstIssue(parsed.error) } };
  const problem = checkCrossFieldRules(parsed.data);
  return problem ? { ok: false, problem } : { ok: true, registry: parsed.data };
}

/** Loads the registry served next to index.html. Never throws. */
export async function loadRegistry(fetchImpl: typeof fetch = fetch): Promise<RegistryResult> {
  let response: Response;
  try {
    response = await fetchImpl(REGISTRY_FILE, { cache: 'no-store', credentials: 'same-origin', redirect: 'manual' });
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

/** In this release the cockpit works with one host: the first enabled one (host switching comes later). */
export function selectHost(registry: HostRegistry): RegistryHost | undefined {
  return registry.hosts.find((host) => host.enabled);
}

export function hostLabel(label: HostLabel, locale: string): string {
  if (typeof label === 'string') return label;
  return label[locale] ?? label['en'] ?? '';
}
