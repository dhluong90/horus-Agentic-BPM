import { z } from 'zod';

/**
 * Why a call to a host did not give a usable answer. Every variant the shell can show:
 * - problem: the host answered with an RFC 9457 problem (ADR-0010: `code` and `correlationId`);
 * - signed-out: the host redirected the call to its sign-in page, so the session really ended and a
 *   reload goes through the host's sign-in. A 401 problem is not this: the page was served, so the
 *   session works for the page and the API refused it for another reason; it stays a `problem`;
 * - unreachable: no answer at all (network, timeout);
 * - unexpected-response: an answer that is neither the documented body nor a problem.
 */
export type HostError =
  | {
      kind: 'problem';
      status: number;
      code: string | undefined;
      correlationId: string | undefined;
      title: string | undefined;
      detail: string | undefined;
    }
  | { kind: 'signed-out' }
  | { kind: 'unreachable' }
  | { kind: 'unexpected-response'; status: number; correlationId: string | undefined };

export class HostCallError extends Error {
  readonly hostError: HostError;

  constructor(hostError: HostError) {
    super(`Host call failed: ${hostError.kind}`);
    this.name = 'HostCallError';
    this.hostError = hostError;
  }
}

const problemBody = z
  .object({
    type: z.string().optional(),
    title: z.string().optional(),
    status: z.number().int().optional(),
    detail: z.string().optional(),
    code: z.string().optional(),
    correlationId: z.string().optional(),
  })
  .loose();

const CORRELATION_HEADER = 'X-Correlation-Id';

/** Reads an error response. Never throws; a body that is not a problem becomes unexpected-response. */
export async function readProblem(response: Response): Promise<HostError> {
  const headerCorrelationId = response.headers.get(CORRELATION_HEADER) ?? undefined;
  const contentType = response.headers.get('Content-Type') ?? '';
  if (!/^application\/(problem\+)?json\b/i.test(contentType)) {
    return { kind: 'unexpected-response', status: response.status, correlationId: headerCorrelationId };
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { kind: 'unexpected-response', status: response.status, correlationId: headerCorrelationId };
  }
  const parsed = problemBody.safeParse(body);
  if (!parsed.success) {
    return { kind: 'unexpected-response', status: response.status, correlationId: headerCorrelationId };
  }
  return {
    kind: 'problem',
    status: response.status,
    code: parsed.data.code,
    correlationId: parsed.data.correlationId ?? headerCorrelationId,
    title: parsed.data.title,
    detail: parsed.data.detail,
  };
}

export function toHostError(error: unknown): HostError {
  if (error instanceof HostCallError) return error.hostError;
  return { kind: 'unreachable' };
}
