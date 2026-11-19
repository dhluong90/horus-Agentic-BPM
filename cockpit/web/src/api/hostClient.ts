import type { z } from 'zod';
import { bpmMeV1, type BpmMeV1 } from './generated/bpm-me.v1';
import { bpmMetaV1, type BpmMetaV1 } from './generated/bpm-meta.v1';
import { HostCallError, readProblem } from './hostError';

export interface HostClient {
  readonly baseUrl: string;
  getMeta(signal?: AbortSignal): Promise<BpmMetaV1>;
  getMe(signal?: AbortSignal): Promise<BpmMeV1>;
}

type Fetch = typeof fetch;

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
 * a body that does not match is an error, never rendered partially (cockpit DESIGN section 12).
 */
export function createHostClient(baseUrl: string, fetchImpl: Fetch = fetch): HostClient {
  async function requestJson<T>(method: string, path: string, schema: z.ZodType<T>, signal: AbortSignal | undefined): Promise<T> {
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
    const parsed = schema.safeParse(body);
    if (!parsed.success) {
      throw new HostCallError({ kind: 'unexpected-response', status: response.status, correlationId });
    }
    return parsed.data;
  }

  return {
    baseUrl,
    getMeta: (signal) => requestJson('GET', '/api/bpm/meta', bpmMetaV1, signal),
    getMe: (signal) => requestJson('GET', '/api/bpm/me', bpmMeV1, signal),
  };
}

/**
 * Signs out of the host in host-session mode: Spring Security's logout endpoint (`POST /logout`) with the CSRF
 * header. Never throws; the caller reloads, and the host's security sends the browser to its sign-in page.
 */
export async function signOutOfHost(baseUrl: string, fetchImpl: Fetch = fetch): Promise<void> {
  try {
    await fetchImpl(`${baseUrl}/logout`, { method: 'POST', credentials: 'same-origin', redirect: 'manual', headers: { 'X-Requested-With': 'XMLHttpRequest', ...csrfHeaders('POST') } });
  } catch {
    // The reload that follows shows the host's answer either way.
  }
}
