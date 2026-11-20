import type { BpmMeV1 } from './generated/bpm-me.v1';
import { bpmMetaV1, type BpmMetaV1 } from './generated/bpm-meta.v1';
import { HostCallError, readProblem } from './hostError';
import { createDroppedPermissionsWarner, readMe } from './meReader';

export interface HostClient {
  readonly baseUrl: string;
  getMeta(signal?: AbortSignal): Promise<BpmMetaV1>;
  getMe(signal?: AbortSignal): Promise<BpmMeV1>;
}

type Fetch = typeof fetch;

/** Turns a response body into the typed value, or undefined when it does not match the contract. */
type Reader<T> = (body: unknown) => T | undefined;

const readMeta: Reader<BpmMetaV1> = (body) => {
  const parsed = bpmMetaV1.safeParse(body);
  return parsed.success ? parsed.data : undefined;
};

/** One warner for the page: the same dropped names are noted once, however often /me is read. */
const warnDroppedPermissions = createDroppedPermissionsWarner();

/** /me is read tolerantly: a permission name outside the known pattern is dropped with one console note. */
const readMeTolerantly: Reader<BpmMeV1> = (body) => {
  const reading = readMe(body);
  if (!reading.ok) return undefined;
  warnDroppedPermissions(reading.dropped);
  return reading.me;
};

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/**
 * CSRF in host-session mode (cockpit DESIGN section 13): the host keeps its token in the readable cookie
 * XSRF-TOKEN, and every request that changes data sends it back in X-XSRF-TOKEN. Safe methods send nothing.
 */
export function csrfHeaders(method: string, cookies: string = document.cookie): Record<string, string> {
  if (SAFE_METHODS.has(method.toUpperCase())) return {};
  const pair = cookies.split(';').map((part) => part.trim()).find((part) => part.startsWith('XSRF-TOKEN='));
  if (pair === undefined) return {};
  return { 'X-XSRF-TOKEN': decodeURIComponent(pair.slice('XSRF-TOKEN='.length)) };
}

/**
 * The one place the cockpit calls a host. This release supports host-session authentication only:
 * the host's own session cookie on the same origin (embedded mode). Every response is validated against the generated schema;
 * a body that does not match is an error, never rendered partially (cockpit DESIGN section 12). The one tolerance is in
 * /me, which drops a permission name it cannot read instead of failing (cockpit DESIGN section 5.2).
 */
export function createHostClient(baseUrl: string, fetchImpl: Fetch = fetch): HostClient {
  async function requestJson<T>(method: string, path: string, read: Reader<T>, signal: AbortSignal | undefined): Promise<T> {
    let response: Response;
    try {
      response = await fetchImpl(`${baseUrl}${path}`, {
        method,
        credentials: 'same-origin',
        // A redirect means the host wants its sign-in page; never follow it into an HTML body.
        redirect: 'manual',
        headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest', ...csrfHeaders(method) },
        ...(signal ? { signal } : {}),
      });
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
      throw new HostCallError({ kind: 'unreachable' });
    }
    // A redirect means the host sends the browser to its sign-in page: the session has ended.
    // Browsers report it as an opaque redirect; other fetch implementations as the 3xx status itself.
    if (response.type === 'opaqueredirect' || (response.status >= 300 && response.status < 400)) {
      throw new HostCallError({ kind: 'signed-out' });
    }
    if (!response.ok) {
      throw new HostCallError(await readProblem(response));
    }
    const correlationId = response.headers.get('X-Correlation-Id') ?? undefined;
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new HostCallError({ kind: 'unexpected-response', status: response.status, correlationId });
    }
    const value = read(body);
    if (value === undefined) {
      throw new HostCallError({ kind: 'unexpected-response', status: response.status, correlationId });
    }
    return value;
  }

  return {
    baseUrl,
    getMeta: (signal) => requestJson('GET', '/api/bpm/meta', readMeta, signal),
    getMe: (signal) => requestJson('GET', '/api/bpm/me', readMeTolerantly, signal),
  };
}

/** True when `baseUrl` is the page's own origin, the only place the host's CSRF token may be sent. */
export function sameOrigin(baseUrl: string, pageOrigin: string = window.location.origin): boolean {
  if (baseUrl === '') return true;
  try {
    return new URL(baseUrl, pageOrigin).origin === pageOrigin;
  } catch {
    return false;
  }
}

/**
 * Signs out of the host in host-session mode: Spring Security's logout endpoint (`POST /logout`). Success is a
 * redirect (the browser reports it as opaque, since redirects are not followed) or a 2xx; anything else, or no
 * answer, is a failure and the session may still be live. The CSRF header goes to the page's own origin only.
 */
export async function signOutOfHost(baseUrl: string, fetchImpl: Fetch = fetch, pageOrigin?: string): Promise<boolean> {
  const csrf = sameOrigin(baseUrl, pageOrigin) ? csrfHeaders('POST') : {};
  try {
    const response = await fetchImpl(`${baseUrl}/logout`, { method: 'POST', credentials: 'same-origin', redirect: 'manual', headers: { 'X-Requested-With': 'XMLHttpRequest', ...csrf } });
    return response.type === 'opaqueredirect' || (response.status >= 200 && response.status < 300);
  } catch {
    return false;
  }
}
