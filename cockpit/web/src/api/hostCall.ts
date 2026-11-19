import type { z } from 'zod';
import { HostCallError, readProblem } from './hostError';

type Fetch = typeof fetch;

export interface HostRequest {
  method: 'GET' | 'POST';
  path: string;
  /** Extra headers, for example Idempotency-Key or If-Match. */
  headers?: Readonly<Record<string, string>>;
  body?: { contentType: string; text: string };
  signal?: AbortSignal;
}

export interface HostAnswer<T> {
  status: number;
  etag: string | undefined;
  data: T;
}

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

/** The host's CSRF token, which it keeps in a cookie readable by the page (host-session mode). */
export function csrfToken(cookie: string = typeof document === 'undefined' ? '' : document.cookie): string | undefined {
  for (const part of cookie.split(';')) {
    const [name, ...value] = part.trim().split('=');
    if (name === CSRF_COOKIE) return decodeURIComponent(value.join('='));
  }
  return undefined;
}

/**
 * One call to the host on the same origin with the host's session. A redirect means the session ended; an
 * error answer becomes a typed problem; a body that does not match its schema is an error, never shown partially.
 */
export async function callHost<T>(fetchImpl: Fetch, baseUrl: string, request: HostRequest, schema: z.ZodType<T>): Promise<HostAnswer<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest', ...request.headers };
  if (request.body) headers['Content-Type'] = request.body.contentType;
  if (request.method !== 'GET') {
    const token = csrfToken();
    if (token !== undefined) headers[CSRF_HEADER] = token;
  }
  let response: Response;
  try {
    response = await fetchImpl(`${baseUrl}${request.path}`, {
      method: request.method,
      credentials: 'same-origin',
      redirect: 'manual',
      headers,
      ...(request.body ? { body: request.body.text } : {}),
      ...(request.signal ? { signal: request.signal } : {}),
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new HostCallError({ kind: 'unreachable' });
  }
  if (response.type === 'opaqueredirect' || (response.status >= 300 && response.status < 400)) {
    throw new HostCallError({ kind: 'signed-out' });
  }
  if (!response.ok) throw new HostCallError(await readProblem(response));
  const correlationId = response.headers.get('X-Correlation-Id') ?? undefined;
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new HostCallError({ kind: 'unexpected-response', status: response.status, correlationId });
  }
  const parsed = schema.safeParse(body);
  if (!parsed.success) throw new HostCallError({ kind: 'unexpected-response', status: response.status, correlationId });
  return { status: response.status, etag: response.headers.get('ETag') ?? undefined, data: parsed.data };
}
