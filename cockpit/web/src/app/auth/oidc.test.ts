import { describe, expect, it } from 'vitest';
import { authorizedFetch } from './authorizedFetch';
import { base64Url, createOidcClient, safeReturnTo, SignInError, TokenError, type OidcDependencies } from './oidc';
import { isCallbackPath, oidcSettings, tokenScope } from './standalone';
import { HostCallError } from '../../api/hostError';
import { parseRegistry, type HostRegistry } from '../config/registry';

const ISSUER = 'https://idp.bank.example/realms/operations';

function idToken(claims: Record<string, unknown>): string {
  const encode = (value: unknown) => base64Url(new TextEncoder().encode(JSON.stringify(value)));
  return `${encode({ alg: 'RS256' })}.${encode(claims)}.signature`;
}

/** A test identity provider: discovery, the token endpoint for both grants, and a record of every form sent. */
function provider(options: { refuseScope?: string; nonce?: () => string; refreshFailures?: number[] } = {}) {
  const forms: URLSearchParams[] = [];
  const pending = new Map<string, string>();
  const navigated: string[] = [];
  let now = 1_000_000;
  let issued = 0;
  const fetchImpl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = input instanceof Request ? input.url : input.toString();
    if (url.endsWith('/.well-known/openid-configuration')) {
      return Response.json({ issuer: ISSUER, authorization_endpoint: `${ISSUER}/auth`, token_endpoint: `${ISSUER}/token`, end_session_endpoint: `${ISSUER}/logout` });
    }
    const form = new URLSearchParams(typeof init?.body === 'string' ? init.body : '');
    forms.push(form);
    if (form.get('scope') === options.refuseScope) return Response.json({ error: 'invalid_scope' }, { status: 400 });
    const failure = form.get('grant_type') === 'refresh_token' ? options.refreshFailures?.shift() : undefined;
    if (failure !== undefined) return Response.json({ error: 'temporarily_unavailable' }, { status: failure });
    issued += 1;
    const scope = form.get('scope') ?? 'sign-in';
    return Response.json({
      access_token: `token-${scope}-${String(issued)}`,
      token_type: 'Bearer',
      expires_in: 300,
      refresh_token: `refresh-${String(issued)}`,
      id_token: idToken({ iss: ISSUER, aud: 'horus-cockpit', exp: now / 1000 + 300, nonce: options.nonce?.() ?? new URL(navigated.at(-1) ?? 'https://x/').searchParams.get('nonce') }),
    });
  }) as typeof fetch;
  let byte = 0;
  const dependencies: OidcDependencies = {
    fetchImpl,
    pending: { getItem: (key) => pending.get(key) ?? null, setItem: (key, value) => { pending.set(key, value); }, removeItem: (key) => { pending.delete(key); } },
    navigate: (url) => { navigated.push(url); },
    randomBytes: (length) => new Uint8Array(length).fill((byte += 1)),
    sha256: (text) => crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)),
    now: () => now,
  };
  return { dependencies, forms, pending, navigated, advance: (ms: number) => { now += ms; } };
}

const registry: HostRegistry = (() => {
  const result = parseRegistry({
    schemaVersion: 1,
    auth: { mode: 'oidc', issuer: ISSUER, clientId: 'horus-cockpit', scopes: ['profile'], tokenMode: 'per-host' },
    hosts: [
      { id: 'loans', label: 'Loans', baseUrl: 'https://loans.api.bank.example', scope: 'loans-bpm' },
      { id: 'kyc', label: 'KYC', baseUrl: 'https://kyc.api.bank.example', scope: 'kyc-bpm' },
    ],
  });
  if (!result.ok) throw new Error('fixture registry');
  return result.registry;
})();

const settings = oidcSettings(registry, 'https://cockpit.bank.example', '/ops/');

async function signedIn(test = provider()) {
  const client = createOidcClient(settings, test.dependencies);
  await client.beginSignIn('#/rules');
  const request = new URL(test.navigated[0] ?? '');
  const { session, returnTo } = await client.completeSignIn(new URLSearchParams({ code: 'abc', state: request.searchParams.get('state') ?? '' }));
  return { client, session, returnTo, request, ...test };
}

describe('standalone sign-in (CKP-013, TR-SEC-12)', () => {
  it('CKP_013_one_authorization_request_with_s256_state_nonce_and_every_host_scope', async () => {
    const { request, forms, returnTo } = await signedIn();

    expect(request.origin + request.pathname).toBe(`${ISSUER}/auth`);
    expect(request.searchParams.get('code_challenge_method')).toBe('S256');
    expect(request.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(request.searchParams.get('state')).not.toBe('');
    expect(request.searchParams.get('nonce')).not.toBe('');
    expect(request.searchParams.get('scope')).toBe('openid profile loans-bpm kyc-bpm');
    expect(request.searchParams.get('redirect_uri')).toBe('https://cockpit.bank.example/ops/auth/callback/');
    // The code is redeemed with the verifier, not a secret: a public client
    expect(forms[0]?.get('grant_type')).toBe('authorization_code');
    expect(forms[0]?.get('code_verifier')).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(forms[0]?.has('client_secret')).toBe(false);
    expect(returnTo).toBe('#/rules');
  });

  it('CKP_013_each_host_gets_its_own_token_by_a_refresh_grant_with_its_scope_and_reuses_it', async () => {
    const { session, forms } = await signedIn();

    const [loansHost, kycHost] = registry.hosts;
    if (!loansHost || !kycHost) throw new Error('fixture hosts');
    const loans = await session.accessToken(tokenScope(registry, loansHost));
    const kyc = await session.accessToken(tokenScope(registry, kycHost));
    const loansAgain = await session.accessToken('loans-bpm');

    expect(loans).toMatch(/^token-loans-bpm-/);
    expect(kyc).toMatch(/^token-kyc-bpm-/);
    expect(loansAgain).toBe(loans);
    expect(forms.slice(1).map((form) => [form.get('grant_type'), form.get('scope')])).toEqual([
      ['refresh_token', 'loans-bpm'],
      ['refresh_token', 'kyc-bpm'],
    ]);
  });

  it('CKP_013_a_token_is_renewed_60_seconds_before_it_expires_with_the_latest_refresh_token', async () => {
    const test = provider();
    const { session, forms, advance } = await signedIn(test);
    const first = await session.accessToken('loans-bpm');

    advance(239_000);
    expect(await session.accessToken('loans-bpm')).toBe(first);
    advance(2_000);
    const renewed = await session.accessToken('loans-bpm');

    expect(renewed).not.toBe(first);
    expect(forms.at(-1)?.get('refresh_token')).toBe('refresh-2');
  });

  it('CKP_013_the_pending_sign_in_is_used_once_and_holds_no_token', async () => {
    const test = provider();
    const client = createOidcClient(settings, test.dependencies);
    await client.beginSignIn('#/rules');
    const stored = test.pending.get('horus.cockpit.pending-sign-in') ?? '';
    expect(Object.keys(JSON.parse(stored) as object).sort()).toEqual(['createdAt', 'nonce', 'returnTo', 'state', 'verifier']);
    const state = new URL(test.navigated[0] ?? '').searchParams.get('state') ?? '';

    await client.completeSignIn(new URLSearchParams({ code: 'abc', state }));

    expect(test.pending.size).toBe(0);
    await expect(client.completeSignIn(new URLSearchParams({ code: 'abc', state }))).rejects.toMatchObject({ problem: { reason: 'state-mismatch' } });
  });

  it('CKP_013_failure_paths_state_mismatch_provider_error_and_wrong_nonce_are_refused', async () => {
    const test = provider({ nonce: () => 'someone-else' });
    const client = createOidcClient(settings, test.dependencies);

    await client.beginSignIn('#/');
    await expect(client.completeSignIn(new URLSearchParams({ code: 'abc', state: 'forged' }))).rejects.toMatchObject({ problem: { reason: 'state-mismatch' } });
    await client.beginSignIn('#/');
    await expect(client.completeSignIn(new URLSearchParams({ error: 'access_denied' }))).rejects.toMatchObject({ problem: { reason: 'provider-refused', error: 'access_denied' } });
    await client.beginSignIn('#/');
    const state = new URL(test.navigated.at(-1) ?? '').searchParams.get('state') ?? '';
    await expect(client.completeSignIn(new URLSearchParams({ code: 'abc', state }))).rejects.toBeInstanceOf(SignInError);
  });

  it('TR_SEC_12_a_scope_the_provider_refuses_means_no_access_to_that_host_only', async () => {
    const { session } = await signedIn(provider({ refuseScope: 'kyc-bpm' }));

    await expect(session.accessToken('kyc-bpm')).rejects.toMatchObject({ problem: { reason: 'no-access' } });
    await expect(session.accessToken('loans-bpm')).resolves.toMatch(/^token-loans-bpm-/);
  });

  for (const status of [503, 429] as const) {
    it(`CKP_013_a_refresh_answered_${String(status)}_keeps_the_session_and_succeeds_on_the_next_try`, async () => {
      const { session, forms } = await signedIn(provider({ refreshFailures: [status] }));

      await expect(session.accessToken('loans-bpm')).rejects.toMatchObject({ problem: { reason: 'provider-unreachable' } });
      // The host call reads as unreachable, never as signed out.
      const fetchOnce = authorizedFetch(() => Promise.resolve(new Response()), () => Promise.reject(new TokenError({ reason: 'provider-unreachable' })));
      await expect(fetchOnce('https://loans.api.bank.example/api/bpm/me')).rejects.toMatchObject({ hostError: { kind: 'unreachable' } });

      await expect(session.accessToken('loans-bpm')).resolves.toMatch(/^token-loans-bpm-/);
      // The same refresh token is used again: the failure did not drop it.
      expect(forms.slice(1).map((form) => form.get('refresh_token'))).toEqual(['refresh-1', 'refresh-1']);
    });
  }

  it('TR_SEC_12_each_host_request_carries_only_its_token_and_no_cookie', async () => {
    const seen: RequestInit[] = [];
    const base = (async (_input: RequestInfo | URL, init?: RequestInit) => {
      seen.push(init ?? {});
      return new Response('{}');
    }) as typeof fetch;
    const loans = authorizedFetch(base, () => Promise.resolve('loans-token'));
    const refused = authorizedFetch(base, () => Promise.reject(new TokenError({ reason: 'no-access', error: 'invalid_scope' })));

    await loans('https://loans.api.bank.example/api/bpm/me', { headers: { Accept: 'application/json', 'X-XSRF-TOKEN': 'x' }, credentials: 'same-origin' });

    const headers = new Headers(seen[0]?.headers);
    expect(headers.get('Authorization')).toBe('Bearer loans-token');
    expect(headers.has('X-XSRF-TOKEN')).toBe(false);
    expect(seen[0]?.credentials).toBe('omit');
    await expect(refused('https://kyc.api.bank.example/api/bpm/me')).rejects.toEqual(new HostCallError({ kind: 'no-access' }));
  });

  it('CKP_013_sign_out_goes_to_the_provider_with_the_id_token_and_returns_to_the_cockpit', async () => {
    const { client, session } = await signedIn();

    const url = new URL(await client.signOutUrl(session));

    expect(url.origin + url.pathname).toBe(`${ISSUER}/logout`);
    expect(url.searchParams.get('post_logout_redirect_uri')).toBe('https://cockpit.bank.example/ops/');
    expect(url.searchParams.get('id_token_hint')).not.toBeNull();
  });
});

describe('cockpit paths', () => {
  it('CKP_013_the_callback_page_is_found_with_or_without_its_trailing_slash', () => {
    expect(isCallbackPath('/ops/auth/callback', 'auth/callback')).toBe(true);
    expect(isCallbackPath('/ops/auth/callback/', 'auth/callback')).toBe(true);
    expect(isCallbackPath('/ops/', 'auth/callback')).toBe(false);
  });

  it('CKP_041_only_a_cockpit_route_is_kept_through_sign_in', () => {
    expect(safeReturnTo('#/decisions/01JD2X3Y4Z5A6B7C8D9E0F1G2H?host=loans')).toBe('#/decisions/01JD2X3Y4Z5A6B7C8D9E0F1G2H?host=loans');
    expect(safeReturnTo('#/decisions/<script>')).toBe('');
    expect(safeReturnTo('https://evil.example/')).toBe('');
    expect(safeReturnTo('')).toBe('');
  });
});
