import type { z } from 'zod';
import { HostCallError, readProblem } from './hostError';

type Fetch = typeof fetch;

export interface HostRequest {
  method: 'GET' | 'POST' | 'PUT';
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

/** A file the host answered with: the bytes as received and the file name it suggested. */
export interface HostFile {
  file: Blob;
  fileName: string | undefined;
  /** A response header by name, for headers that describe the file (for example its digest). */
  header: (name: string) => string | undefined;
}

/** The file name of an `attachment` Content-Disposition (RFC 6266): `filename*` when present, else `filename`. */
export function attachmentName(disposition: string | null): string | undefined {
  if (disposition === null) return undefined;
  const extended = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(disposition);
  if (extended?.[1] !== undefined) {
    try {
      return decodeURIComponent(extended[1].trim());
    } catch {
      return undefined;
    }
  }
  const plain = /filename\s*=\s*(?:"([^"]*)"|([^;\s]+))/.exec(disposition);
  const name = plain?.[1] ?? plain?.[2];
  return name === undefined || name === '' ? undefined : name;
}

/** Sends one request; a redirect or an error answer is thrown as a typed problem, anything else is returned unread. */
async function send(fetchImpl: Fetch, baseUrl: string, request: HostRequest, accept: string): Promise<Response> {
  const headers: Record<string, string> = { Accept: accept, 'X-Requested-With': 'XMLHttpRequest', ...request.headers };
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
  return response;
}

/**
 * One call to the host on the same origin with the host's session. A redirect means the session ended; an
 * error answer becomes a typed problem; a body that does not match its schema is an error, never shown partially.
 */
export async function callHost<T>(fetchImpl: Fetch, baseUrl: string, request: HostRequest, schema: z.ZodType<T>): Promise<HostAnswer<T>> {
  const response = await send(fetchImpl, baseUrl, request, 'application/json');
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

/** A call whose answer is a file: the bytes are kept exactly as the host sent them, never parsed or re-encoded. */
export async function downloadFromHost(fetchImpl: Fetch, baseUrl: string, request: HostRequest, accept: string): Promise<HostFile> {
  const response = await send(fetchImpl, baseUrl, request, `${accept}, application/problem+json`);
  const { headers } = response;
  return { file: await response.blob(), fileName: attachmentName(headers.get('Content-Disposition')), header: (name) => headers.get(name) ?? undefined };
}
