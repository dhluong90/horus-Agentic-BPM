import { z } from 'zod';

/**
 * Why a call to a host did not give a usable answer. Every variant the shell can show:
 * - problem: the host answered with an RFC 9457 problem (ADR-0010: `code` and `correlationId`);
 * - signed-out: the host redirected the call to its sign-in page, so the session really ended and a
 *   reload goes through the host's sign-in. A 401 problem is not this: the page was served, so the
 *   session works for the page and the API refused it for another reason; it stays a `problem`;
 * - no-access: standalone mode only; the identity provider gives the user no token for this service;
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
      /** Located validation problems of a 422 VALIDATION_FAILED; absent for every other problem. */
      violations?: readonly Violation[];
      /** Members beyond the standard ones that a screen may read (for example `warnings` of a 409); absent when none. */
      members?: Readonly<Record<string, unknown>>;
    }
  | { kind: 'signed-out' }
  /** Standalone mode: the identity provider refuses this service's scope to the user. */
  | { kind: 'no-access' }
  | { kind: 'unreachable' }
  | { kind: 'unexpected-response'; status: number; correlationId: string | undefined };

/** One located problem of a rejected document: the row (rule id) and column (input or output name), when known. */
export interface Violation {
  ruleId: string | null;
  column: string | null;
  message: string;
  /** The engine's stable code of the problem (for a spreadsheet file, `CSV_*`); absent when the engine sent none. */
  code?: string;
}

const violation = z.object({ ruleId: z.string().nullable().optional(), column: z.string().nullable().optional(), message: z.string(), code: z.string().optional() }).loose();

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
    violations: z.array(z.unknown()).optional(),
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
  const violations = (parsed.data.violations ?? []).flatMap((item) => {
    const read = violation.safeParse(item);
    return read.success ? [{ ruleId: read.data.ruleId ?? null, column: read.data.column ?? null, message: read.data.message, ...(read.data.code !== undefined ? { code: read.data.code } : {}) }] : [];
  });
  const STANDARD = new Set(['type', 'title', 'status', 'detail', 'violations', 'code', 'correlationId', 'instance']);
  const extra: Record<string, unknown> = Object.fromEntries(Object.entries(parsed.data).filter(([key]) => !STANDARD.has(key)));
  // The Process Engine lists FORM_VALIDATION_FAILED violations as text ("$.riskScore: expected integer"); keep them.
  const texts = (parsed.data.violations ?? []).filter((item): item is string => typeof item === 'string');
  if (texts.length > 0) extra['violationTexts'] = texts;
  return {
    ...(violations.length > 0 ? { violations } : {}),
    ...(Object.keys(extra).length > 0 ? { members: extra } : {}),
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
