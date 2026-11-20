import { describe, expect, it, vi } from 'vitest';
import { meLan, UNREADABLE_PERMISSION } from '../mocks/fixtures';
import { createHostClient, csrfHeaders, sameOrigin, signOutOfHost } from './hostClient';
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

  it('CKP_001_redirect_status_from_a_non_browser_fetch_is_a_signed_out_session', async () => {
    const response = new Response(null, { status: 302, headers: { Location: '/login' } });
    expect(await failure(respond(response))).toEqual({ kind: 'signed-out' });
  });

  it('CKP_001_401_problem_stays_a_problem_with_code_and_correlation_id', async () => {
    const body = JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', correlationId: 'c-3' });
    expect(await failure(respond(new Response(body, { status: 401, headers: problemHeaders })))).toMatchObject({
      kind: 'problem', status: 401, code: 'UNAUTHENTICATED', correlationId: 'c-3',
    });
  });

  it('CKP_001_me_with_an_unreadable_permission_resolves_without_it_and_warns_once_across_reads', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const body = JSON.stringify({ ...meLan, permissions: [...meLan.permissions, UNREADABLE_PERMISSION] });
    const client = createHostClient('', async () => new Response(body, { status: 200 }));
    const me = await client.getMe();
    await client.getMe();
    await createHostClient('', async () => new Response(body, { status: 200 })).getMe();
    expect(me.permissions).toEqual(meLan.permissions);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0]?.[0]).toContain(UNREADABLE_PERMISSION);
    warn.mockRestore();
  });

  it('CKP_001_well_formed_me_resolves_with_no_console_note', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const me = await createHostClient('', respond(new Response(JSON.stringify(meLan), { status: 200 }))).getMe();
    expect(me).toEqual(meLan);
    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it('CKP_001_me_with_a_bad_user_id_is_still_an_unexpected_response', async () => {
    const body = JSON.stringify({ ...meLan, user: { id: '' }, permissions: [UNREADABLE_PERMISSION] });
    expect(await failure(respond(new Response(body, { status: 200 })))).toEqual({ kind: 'unexpected-response', status: 200, correlationId: undefined });
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

  it('CKP_001_requests_that_change_data_send_the_xsrf_cookie_back_as_x_xsrf_token', () => {
    const cookies = 'JSESSIONID=abc; XSRF-TOKEN=3f2a%2Bb9; theme=dark';
    expect(csrfHeaders('POST', cookies)).toEqual({ 'X-XSRF-TOKEN': '3f2a+b9' });
    expect(csrfHeaders('put', cookies)).toEqual({ 'X-XSRF-TOKEN': '3f2a+b9' });
    expect(csrfHeaders('DELETE', cookies)).toEqual({ 'X-XSRF-TOKEN': '3f2a+b9' });
  });

  it('CKP_001_safe_requests_and_a_missing_cookie_send_no_xsrf_header', () => {
    expect(csrfHeaders('GET', 'XSRF-TOKEN=abc')).toEqual({});
    expect(csrfHeaders('POST', 'JSESSIONID=abc')).toEqual({});
  });
});

describe('sign-out on the host', () => {
  const answer = (init: { type?: ResponseType; status: number }) => {
    const calls: RequestInit[] = [];
    const fetchImpl: typeof fetch = async (_input, request) => {
      calls.push(request ?? {});
      // A browser reports a redirect it did not follow as type opaqueredirect, status 0.
      return { type: init.type ?? 'basic', status: init.status } as unknown as Response;
    };
    return { fetchImpl, calls };
  };

  it('TR_SEC_09_a_redirect_or_a_2xx_is_a_sign_out', async () => {
    expect(await signOutOfHost('', answer({ type: 'opaqueredirect', status: 0 }).fetchImpl)).toBe(true);
    expect(await signOutOfHost('', answer({ status: 204 }).fetchImpl)).toBe(true);
  });

  it('TR_SEC_09_an_error_status_or_no_answer_is_not_a_sign_out', async () => {
    expect(await signOutOfHost('', answer({ status: 403 }).fetchImpl)).toBe(false);
    expect(await signOutOfHost('', answer({ status: 500 }).fetchImpl)).toBe(false);
    expect(await signOutOfHost('', respond(new TypeError('network')))).toBe(false);
  });

  it('TR_SEC_09_the_csrf_token_goes_to_the_page_origin_only', async () => {
    document.cookie = 'XSRF-TOKEN=t-1';
    const local = answer({ type: 'opaqueredirect', status: 0 });
    await signOutOfHost('', local.fetchImpl, 'https://loans.bank.example');
    expect(local.calls[0]?.headers).toMatchObject({ 'X-XSRF-TOKEN': 't-1' });
    const other = answer({ type: 'opaqueredirect', status: 0 });
    await signOutOfHost('https://cards.bank.example', other.fetchImpl, 'https://loans.bank.example');
    expect(other.calls[0]?.headers).not.toHaveProperty('X-XSRF-TOKEN');
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  it('same_origin_compares_the_full_origin', () => {
    expect(sameOrigin('', 'https://a.example')).toBe(true);
    expect(sameOrigin('https://a.example/api', 'https://a.example')).toBe(true);
    expect(sameOrigin('https://a.example:8443', 'https://a.example')).toBe(false);
    expect(sameOrigin('not a url', 'https://a.example')).toBe(true);
  });
});
