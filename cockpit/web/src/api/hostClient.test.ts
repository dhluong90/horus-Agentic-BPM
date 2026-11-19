import { describe, expect, it } from 'vitest';
import { createHostClient } from './hostClient';
import { HostCallError, type HostError } from './hostError';

function respond(response: Response | Error): typeof fetch {
  return async () => {
    if (response instanceof Error) throw response;
    return response;
  };
}

async function failure(fetchImpl: typeof fetch): Promise<HostError> {
  try {
    await createHostClient('', fetchImpl).getMe();
  } catch (error) {
    if (error instanceof HostCallError) return error.hostError;
    throw error;
  }
  throw new Error('expected a failure');
}

const problemHeaders = { 'Content-Type': 'application/problem+json' };

describe('host client', () => {
  it('CKP_001_problem_body_gives_code_and_correlation_id', async () => {
    const body = JSON.stringify({ title: 'Permission denied', status: 403, code: 'PERMISSION_DENIED', correlationId: 'c-1' });
    expect(await failure(respond(new Response(body, { status: 403, headers: problemHeaders })))).toEqual({
      kind: 'problem', status: 403, code: 'PERMISSION_DENIED', correlationId: 'c-1', title: 'Permission denied', detail: undefined,
    });
  });

  it('CKP_001_correlation_id_header_is_used_when_the_body_has_none', async () => {
    const response = new Response(JSON.stringify({ code: 'INTERNAL_ERROR' }), {
      status: 500, headers: { ...problemHeaders, 'X-Correlation-Id': 'c-2' },
    });
    expect(await failure(respond(response))).toMatchObject({ kind: 'problem', code: 'INTERNAL_ERROR', correlationId: 'c-2' });
  });

  it('CKP_001_html_error_page_is_an_unexpected_response', async () => {
    const response = new Response('<html>Bad gateway</html>', { status: 502, headers: { 'Content-Type': 'text/html' } });
    expect(await failure(respond(response))).toEqual({ kind: 'unexpected-response', status: 502, correlationId: undefined });
  });

  it('CKP_001_redirect_to_the_host_sign_in_is_a_signed_out_session', async () => {
    const redirected = { type: 'opaqueredirect', status: 0, ok: false, headers: new Headers() } as unknown as Response;
    expect(await failure(async () => redirected)).toMatchObject({ kind: 'signed-out' });
  });

  it('CKP_001_network_failure_is_unreachable', async () => {
    expect(await failure(respond(new TypeError('Failed to fetch')))).toEqual({ kind: 'unreachable' });
  });

  it('CKP_001_requests_use_the_same_origin_session_and_never_follow_redirects', async () => {
    let seen: RequestInit | undefined;
    let url = '';
    const fetchImpl: typeof fetch = async (input, init) => {
      url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      seen = init;
      return new Response('{}', { status: 500, headers: problemHeaders });
    };
    await failure(fetchImpl);
    expect(url).toBe('/api/bpm/me');
    expect(seen).toMatchObject({ method: 'GET', credentials: 'same-origin', redirect: 'manual' });
  });
});
