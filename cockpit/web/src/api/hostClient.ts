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

/**
 * The one place the cockpit calls a host. This release supports host-session authentication only:
 * the host's own session cookie on the same origin (embedded mode). Every response is validated against the generated schema;
 * a body that does not match is an error, never rendered partially (cockpit DESIGN section 12).
 */
export function createHostClient(baseUrl: string, fetchImpl: Fetch = fetch): HostClient {
  async function getJson<T>(path: string, schema: z.ZodType<T>, signal: AbortSignal | undefined): Promise<T> {
    let response: Response;
    try {
      response = await fetchImpl(`${baseUrl}${path}`, {
        method: 'GET',
        credentials: 'same-origin',
        // A redirect means the host wants its sign-in page; never follow it into an HTML body.
        redirect: 'manual',
        headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        ...(signal ? { signal } : {}),
      });
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
      throw new HostCallError({ kind: 'unreachable' });
    }
    if (response.type === 'opaqueredirect') {
      throw new HostCallError({ kind: 'signed-out', code: undefined, correlationId: undefined });
    }
    if (response.status === 401) {
      const problem = await readProblem(response);
      throw new HostCallError({
        kind: 'signed-out',
        code: problem.kind === 'problem' ? problem.code : undefined,
        correlationId: problem.kind === 'problem' || problem.kind === 'unexpected-response' ? problem.correlationId : undefined,
      });
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
    getMeta: (signal) => getJson('/api/bpm/meta', bpmMetaV1, signal),
    getMe: (signal) => getJson('/api/bpm/me', bpmMeV1, signal),
  };
}
