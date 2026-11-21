import { z } from 'zod';

/*
 * Sign-in of the standalone cockpit (cockpit DESIGN section 5.1): OIDC authorization code with PKCE (S256), state
 * and nonce, as a public client with no secret. Tokens live in this module's memory only, never in browser
 * storage or a cookie. The one thing kept across the redirect to the identity provider is the pending sign-in
 * (state, nonce, code verifier and the cockpit route to return to), removed as soon as the callback arrives.
 */

export interface OidcSettings {
  issuer: string;
  clientId: string;
  /** Every scope of the sign-in request; `openid` is added when missing. */
  scopes: readonly string[];
  redirectUri: string;
  postLogoutRedirectUri: string;
}

/** Why sign-in did not give a session. Each one is shown in words; none is retried by itself. */
export type SignInProblem =
  | { reason: 'provider-unreachable' }
  | { reason: 'provider-refused'; error: string }
  | { reason: 'state-mismatch' }
  | { reason: 'token-refused'; error: string | undefined; status: number }
  | { reason: 'id-token-invalid' };

export class SignInError extends Error {
  readonly problem: SignInProblem;

  constructor(problem: SignInProblem) {
    super(`Sign-in failed: ${problem.reason}`);
    this.name = 'SignInError';
    this.problem = problem;
  }
}

/** Why a token for one host could not be had. `no-access`: the identity provider refuses the host's scope. */
export type TokenProblem = { reason: 'no-access'; error: string } | { reason: 'session-ended' } | { reason: 'provider-unreachable' };

export class TokenError extends Error {
  readonly problem: TokenProblem;

  constructor(problem: TokenProblem) {
    super(`Token refused: ${problem.reason}`);
    this.name = 'TokenError';
    this.problem = problem;
  }
}

/** The small part of the browser this module needs, so tests can supply their own. */
export interface OidcDependencies {
  fetchImpl: typeof fetch;
  /** Keeps the pending sign-in across the redirect. sessionStorage in the browser; never holds a token. */
  pending: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
  navigate: (url: string) => void;
  randomBytes: (length: number) => Uint8Array;
  sha256: (text: string) => Promise<ArrayBuffer>;
  now: () => number;
}

export function browserDependencies(): OidcDependencies {
  return {
    fetchImpl: (input, init) => fetch(input, init),
    pending: window.sessionStorage,
    navigate: (url) => {
      window.location.assign(url);
    },
    randomBytes: (length) => crypto.getRandomValues(new Uint8Array(length)),
    sha256: (text) => crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)),
    now: () => Date.now(),
  };
}

const PENDING_KEY = 'horus.cockpit.pending-sign-in';
/** A pending sign-in older than this is ignored: the user left the identity provider's page long ago. */
const PENDING_MAX_AGE_MS = 10 * 60 * 1000;
/** A token is renewed this long before it expires (DESIGN section 5.1). */
export const RENEW_BEFORE_MS = 60 * 1000;

const discoverySchema = z
  .object({
    issuer: z.string(),
    authorization_endpoint: z.url(),
    token_endpoint: z.url(),
    end_session_endpoint: z.url().optional(),
  })
  .loose();
export type Discovery = z.infer<typeof discoverySchema>;

const tokenSchema = z
  .object({
    access_token: z.string().min(1),
    token_type: z.string(),
    expires_in: z.number().positive().optional(),
    refresh_token: z.string().min(1).optional(),
    id_token: z.string().min(1).optional(),
  })
  .loose();
type TokenAnswer = z.infer<typeof tokenSchema>;

const pendingSchema = z.object({ state: z.string(), nonce: z.string(), verifier: z.string(), returnTo: z.string(), createdAt: z.number() });
type PendingSignIn = z.infer<typeof pendingSchema>;

const errorSchema = z.object({ error: z.string() }).loose();

export function base64Url(bytes: Uint8Array): string {
  let text = '';
  for (const byte of bytes) text += String.fromCharCode(byte);
  return btoa(text).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

/** The claims of a JWT, unverified: the ID token comes straight from the token endpoint over TLS (OIDC Core 3.1.3.7). */
export function jwtClaims(token: string): Record<string, unknown> | undefined {
  const payload = token.split('.')[1];
  if (payload === undefined) return undefined;
  try {
    const padded = payload.replaceAll('-', '+').replaceAll('_', '/');
    const bytes = Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
    const claims: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return typeof claims === 'object' && claims !== null && !Array.isArray(claims) ? (claims as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}

function withOpenId(scopes: readonly string[]): string {
  return [...new Set(['openid', ...scopes])].join(' ');
}

/** The cockpit route the user asked for, kept through sign-in. Only a cockpit hash route is accepted. */
export function safeReturnTo(hash: string): string {
  return /^#\/[^\s<>"'`]{0,300}$/.test(hash) ? hash : '';
}

async function postForm(fetchImpl: typeof fetch, url: string, form: Record<string, string>): Promise<Response> {
  return fetchImpl(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams(form).toString(),
    credentials: 'omit',
  });
}

async function errorCode(response: Response): Promise<string | undefined> {
  try {
    const parsed = errorSchema.safeParse(await response.json());
    return parsed.success ? parsed.data.error : undefined;
  } catch {
    return undefined;
  }
}

/** One sign-in client per page. `discover` runs once; every later call reuses its answer. */
export function createOidcClient(settings: OidcSettings, dependencies: OidcDependencies) {
  let discovery: Promise<Discovery> | undefined;

  function discover(): Promise<Discovery> {
    discovery ??= (async () => {
      let response: Response;
      try {
        response = await dependencies.fetchImpl(`${settings.issuer.replace(/\/$/, '')}/.well-known/openid-configuration`, { credentials: 'omit' });
      } catch {
        throw new SignInError({ reason: 'provider-unreachable' });
      }
      if (!response.ok) throw new SignInError({ reason: 'provider-unreachable' });
      const parsed = discoverySchema.safeParse(await response.json().catch(() => undefined));
      // The issuer must name itself exactly as configured (OIDC Discovery 4.3).
      if (!parsed.success || parsed.data.issuer.replace(/\/$/, '') !== settings.issuer.replace(/\/$/, '')) {
        throw new SignInError({ reason: 'provider-unreachable' });
      }
      return parsed.data;
    })();
    discovery.catch(() => {
      discovery = undefined;
    });
    return discovery;
  }

  /** Sends the browser to the identity provider. `returnTo` is the cockpit route to open afterwards. */
  async function beginSignIn(returnTo: string): Promise<void> {
    const config = await discover();
    const pending: PendingSignIn = {
      state: base64Url(dependencies.randomBytes(32)),
      nonce: base64Url(dependencies.randomBytes(32)),
      verifier: base64Url(dependencies.randomBytes(32)),
      returnTo: safeReturnTo(returnTo),
      createdAt: dependencies.now(),
    };
    const challenge = base64Url(new Uint8Array(await dependencies.sha256(pending.verifier)));
    dependencies.pending.setItem(PENDING_KEY, JSON.stringify(pending));
    const url = new URL(config.authorization_endpoint);
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('client_id', settings.clientId);
    url.searchParams.set('redirect_uri', settings.redirectUri);
    url.searchParams.set('scope', withOpenId(settings.scopes));
    url.searchParams.set('state', pending.state);
    url.searchParams.set('nonce', pending.nonce);
    url.searchParams.set('code_challenge', challenge);
    url.searchParams.set('code_challenge_method', 'S256');
    dependencies.navigate(url.toString());
  }

  function takePending(): PendingSignIn | undefined {
    const text = dependencies.pending.getItem(PENDING_KEY);
    dependencies.pending.removeItem(PENDING_KEY);
    if (text === null) return undefined;
    try {
      const parsed = pendingSchema.safeParse(JSON.parse(text));
      if (!parsed.success || dependencies.now() - parsed.data.createdAt > PENDING_MAX_AGE_MS) return undefined;
      return parsed.data;
    } catch {
      return undefined;
    }
  }

  function checkIdToken(idToken: string | undefined, nonce: string): void {
    if (idToken === undefined) throw new SignInError({ reason: 'id-token-invalid' });
    const claims = jwtClaims(idToken);
    const audience = claims?.['aud'];
    const audiences = Array.isArray(audience) ? audience : [audience];
    const expires = claims?.['exp'];
    const valid =
      claims !== undefined &&
      claims['nonce'] === nonce &&
      typeof claims['iss'] === 'string' &&
      claims['iss'].replace(/\/$/, '') === settings.issuer.replace(/\/$/, '') &&
      audiences.includes(settings.clientId) &&
      typeof expires === 'number' &&
      expires * 1000 > dependencies.now();
    if (!valid) throw new SignInError({ reason: 'id-token-invalid' });
  }

  /** Finishes sign-in from the callback's query. The pending sign-in is used once, whatever the outcome. */
  async function completeSignIn(query: URLSearchParams): Promise<{ returnTo: string; session: TokenSession }> {
    const pending = takePending();
    const error = query.get('error');
    if (error !== null) throw new SignInError({ reason: 'provider-refused', error: error.slice(0, 64) });
    const code = query.get('code');
    if (pending === undefined || code === null || query.get('state') !== pending.state) throw new SignInError({ reason: 'state-mismatch' });
    const config = await discover();
    let response: Response;
    try {
      response = await postForm(dependencies.fetchImpl, config.token_endpoint, {
        grant_type: 'authorization_code',
        code,
        redirect_uri: settings.redirectUri,
        client_id: settings.clientId,
        code_verifier: pending.verifier,
      });
    } catch {
      throw new SignInError({ reason: 'provider-unreachable' });
    }
    if (!response.ok) throw new SignInError({ reason: 'token-refused', error: await errorCode(response), status: response.status });
    const parsed = tokenSchema.safeParse(await response.json().catch(() => undefined));
    if (!parsed.success) throw new SignInError({ reason: 'token-refused', error: undefined, status: response.status });
    checkIdToken(parsed.data.id_token, pending.nonce);
    return { returnTo: pending.returnTo, session: createTokenSession(config, settings, dependencies, parsed.data) };
  }

  /** Where Sign out sends the browser: the provider's end-session endpoint, or the cockpit when it has none. */
  async function signOutUrl(session: TokenSession | undefined): Promise<string> {
    const config = await discover().catch(() => undefined);
    if (config?.end_session_endpoint === undefined) return settings.postLogoutRedirectUri;
    const url = new URL(config.end_session_endpoint);
    url.searchParams.set('client_id', settings.clientId);
    url.searchParams.set('post_logout_redirect_uri', settings.postLogoutRedirectUri);
    const idToken = session?.idToken();
    if (idToken !== undefined) url.searchParams.set('id_token_hint', idToken);
    return url.toString();
  }

  return { beginSignIn, completeSignIn, signOutUrl, hasPendingSignIn: () => dependencies.pending.getItem(PENDING_KEY) !== null };
}

export type OidcClient = ReturnType<typeof createOidcClient>;

interface HeldToken {
  value: string;
  expiresAt: number;
}

/**
 * The signed-in session, in memory only. `accessToken(scope)` gives a token for one host: in per-host mode one
 * refresh grant per scope, so each token is valid at one host only; with no scope (shared mode) the sign-in token.
 * Grants run one at a time, because a provider that rotates refresh tokens accepts each one once.
 */
export function createTokenSession(config: Discovery, settings: OidcSettings, dependencies: OidcDependencies, first: TokenAnswer) {
  let refreshToken = first.refresh_token;
  let idToken = first.id_token;
  const held = new Map<string, HeldToken>();
  held.set('', hold(first));
  let queue: Promise<unknown> = Promise.resolve();

  function hold(answer: TokenAnswer): HeldToken {
    // Without expires_in, assume the short lifetime DESIGN recommends rather than forever.
    return { value: answer.access_token, expiresAt: dependencies.now() + (answer.expires_in ?? 300) * 1000 };
  }

  function fresh(token: HeldToken | undefined): token is HeldToken {
    return token !== undefined && token.expiresAt - dependencies.now() > RENEW_BEFORE_MS;
  }

  async function grant(scope: string): Promise<HeldToken> {
    if (refreshToken === undefined) throw new TokenError({ reason: 'session-ended' });
    let response: Response;
    try {
      response = await postForm(dependencies.fetchImpl, config.token_endpoint, {
        grant_type: 'refresh_token',
        refresh_token: refreshToken,
        client_id: settings.clientId,
        ...(scope === '' ? {} : { scope }),
      });
    } catch {
      throw new TokenError({ reason: 'provider-unreachable' });
    }
    // A provider that is down or busy (5xx, 429) has not ended the session: keep the refresh token and try later.
    if (response.status >= 500 || response.status === 429) throw new TokenError({ reason: 'provider-unreachable' });
    if (!response.ok) {
      const error = await errorCode(response);
      if (error === 'invalid_scope' || error === 'access_denied' || error === 'unauthorized_client') throw new TokenError({ reason: 'no-access', error });
      throw new TokenError({ reason: 'session-ended' });
    }
    const parsed = tokenSchema.safeParse(await response.json().catch(() => undefined));
    if (!parsed.success) throw new TokenError({ reason: 'session-ended' });
    refreshToken = parsed.data.refresh_token ?? refreshToken;
    idToken = parsed.data.id_token ?? idToken;
    const token = hold(parsed.data);
    held.set(scope, token);
    return token;
  }

  function accessToken(scope: string | undefined): Promise<string> {
    const key = scope ?? '';
    const current = held.get(key);
    if (fresh(current)) return Promise.resolve(current.value);
    const next = queue.then(async () => {
      const again = held.get(key);
      return fresh(again) ? again.value : (await grant(key)).value;
    });
    queue = next.catch(() => undefined);
    return next;
  }

  return {
    accessToken,
    idToken: () => idToken,
    /** For tests and sign-out: forget every token. */
    clear: () => {
      held.clear();
      refreshToken = undefined;
      idToken = undefined;
    },
  };
}

export type TokenSession = ReturnType<typeof createTokenSession>;
