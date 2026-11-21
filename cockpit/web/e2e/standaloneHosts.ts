import type { Page, Route } from '@playwright/test';
import type { BpmMeV1 } from '../src/api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../src/api/generated/bpm-meta.v1';
import { meLan } from '../src/mocks/fixtures';
import { createFakeEngine, demoSeed } from '../src/mocks/ruleEngine';
import { CONTENT_SECURITY_POLICY } from '../security-headers';

/*
 * The standalone cockpit against a test identity provider and two hosts on their own origins (CKP-012 to
 * CKP-015). Everything is answered by Playwright routes; the page's Content-Security-Policy lists the two hosts
 * and the provider in connect-src, as a deployment of the standalone cockpit does (cockpit DESIGN section 4).
 */

export const ISSUER = 'https://idp.bank.example/realms/operations';
export const LOANS = 'https://loans.api.bank.example';
export const KYC = 'https://kyc.api.bank.example';

export const standaloneRegistry = {
  schemaVersion: 1,
  auth: { mode: 'oidc', issuer: ISSUER, clientId: 'horus-cockpit', scopes: ['profile'], tokenMode: 'per-host' },
  hosts: [
    { id: 'loans', label: { en: 'Loan origination', vi: 'Khởi tạo khoản vay' }, baseUrl: LOANS, scope: 'loans-bpm', environment: 'production' },
    { id: 'kyc', label: { en: 'KYC onboarding', vi: 'Định danh khách hàng' }, baseUrl: KYC, scope: 'kyc-bpm', environment: 'production' },
  ],
} as const;

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, accept, content-type, x-requested-with, idempotency-key, if-match, x-correlation-id',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
  'Access-Control-Expose-Headers': 'etag, x-correlation-id, content-disposition',
};

function base64Url(value: unknown): string {
  return Buffer.from(JSON.stringify(value)).toString('base64url');
}

function jwt(claims: Record<string, unknown>): string {
  return `${base64Url({ alg: 'none' })}.${base64Url(claims)}.sig`;
}

export interface HostAnswer {
  meta?: Partial<BpmMetaV1>;
  me?: BpmMeV1;
  /** A problem answer to /me instead. */
  meProblem?: { status: number; code: string; correlationId: string };
}

export interface StandaloneRecord {
  /** Every authorization request the browser made to the identity provider. */
  authorizations: URL[];
  /** Grants sent to the token endpoint. */
  grants: URLSearchParams[];
  /** Per host: the Authorization header of each request. */
  tokensSeen: Record<'loans' | 'kyc', string[]>;
  /** Paths requested per host. */
  paths: Record<'loans' | 'kyc', string[]>;
}

const meta = (hostId: string, extra: Partial<BpmMetaV1> = {}): BpmMetaV1 => ({
  schemaVersion: 1,
  hostId,
  modules: [{ module: 'rules', engineVersion: '0.3.0', apiVersion: 'v1', features: [], settings: { 'authoring.cockpit-drafts': true } }],
  tenancy: { switching: false },
  limits: { maxPageSize: 200 },
  ...extra,
});

/** Serves the registry, the identity provider and the two hosts; returns what was asked for. */
export async function mockStandalone(page: Page, options: { registry?: unknown; loans?: HostAnswer; kyc?: HostAnswer } = {}): Promise<StandaloneRecord> {
  const record: StandaloneRecord = { authorizations: [], grants: [], tokensSeen: { loans: [], kyc: [] }, paths: { loans: [], kyc: [] } };
  const connect = ["connect-src 'self'", LOANS, KYC, 'https://idp.bank.example'].join(' ');
  const policy = CONTENT_SECURITY_POLICY.replace("connect-src 'self'", connect);

  // The cockpit's own pages, with the policy a standalone deployment sends.
  await page.route((url) => url.hostname === '127.0.0.1', async (route) => {
    const request = route.request();
    if (request.resourceType() !== 'document') return route.fallback();
    const response = await route.fetch();
    await route.fulfill({ response, headers: { ...response.headers(), 'content-security-policy': policy } });
  });
  await page.route('**/cockpit-hosts.json', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(options.registry ?? standaloneRegistry) }));

  let nonce = '';
  await page.route(`${ISSUER}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
    if (url.pathname.endsWith('/.well-known/openid-configuration')) {
      return route.fulfill({ status: 200, contentType: 'application/json', headers: CORS, body: JSON.stringify({
        issuer: ISSUER, authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`, token_endpoint: `${ISSUER}/protocol/openid-connect/token`, end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
      }) });
    }
    if (url.pathname.endsWith('/auth')) {
      // The provider's session already knows the user: straight back to the cockpit with a code.
      record.authorizations.push(url);
      nonce = url.searchParams.get('nonce') ?? '';
      const back = new URL(url.searchParams.get('redirect_uri') ?? '');
      back.searchParams.set('code', `code-${String(record.authorizations.length)}`);
      back.searchParams.set('state', url.searchParams.get('state') ?? '');
      // A page of the provider that moves on, as a sign-in form does after submit: a new navigation, which the
      // cockpit route below serves with its policy (a redirect would bypass it).
      return route.fulfill({ status: 200, contentType: 'text/html', body: `<!doctype html><meta http-equiv="refresh" content="0;url=${back.toString().replaceAll('&', '&amp;')}"><title>Sign in</title>` });
    }
    if (url.pathname.endsWith('/logout')) {
      return route.fulfill({ status: 200, contentType: 'text/html', body: `<!doctype html><meta http-equiv="refresh" content="0;url=${url.searchParams.get('post_logout_redirect_uri') ?? '/'}"><title>Signed out</title>` });
    }
    if (url.pathname.endsWith('/token')) {
      const form = new URLSearchParams(request.postData() ?? '');
      record.grants.push(form);
      const scope = form.get('scope') ?? 'sign-in';
      const audience = scope === 'loans-bpm' ? 'loans-bpm' : scope === 'kyc-bpm' ? 'kyc-bpm' : 'horus-cockpit';
      const now = Math.floor(Date.now() / 1000);
      return route.fulfill({ status: 200, contentType: 'application/json', headers: CORS, body: JSON.stringify({
        access_token: jwt({ aud: audience, sub: 'lan', exp: now + 300 }),
        token_type: 'Bearer',
        expires_in: 300,
        refresh_token: `refresh-${String(record.grants.length)}`,
        id_token: jwt({ iss: ISSUER, aud: 'horus-cockpit', sub: 'lan', nonce, exp: now + 300 }),
      }) });
    }
    return route.fulfill({ status: 404, headers: CORS, body: '' });
  });

  const seeds = { loans: demoSeed().filter((version) => version.ruleSet === 'card-fee'), kyc: demoSeed().filter((version) => version.ruleSet === 'kyc-risk') };
  for (const id of ['loans', 'kyc'] as const) {
    const answer = options[id] ?? {};
    const engine = createFakeEngine(seeds[id]);
    const me = answer.me ?? meLan;
    await page.route(`${id === 'loans' ? LOANS : KYC}/**`, async (route: Route) => {
      const request = route.request();
      if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
      const url = new URL(request.url());
      record.paths[id].push(url.pathname);
      record.tokensSeen[id].push(request.headers()['authorization'] ?? '');
      if (url.pathname === '/api/bpm/meta') return route.fulfill({ status: 200, contentType: 'application/json', headers: CORS, body: JSON.stringify(meta(id, answer.meta)) });
      if (url.pathname === '/api/bpm/me') {
        if (answer.meProblem) {
          return route.fulfill({ status: answer.meProblem.status, contentType: 'application/problem+json', headers: CORS, body: JSON.stringify({ title: 'Refused', ...answer.meProblem }) });
        }
        return route.fulfill({ status: 200, contentType: 'application/json', headers: CORS, body: JSON.stringify(me) });
      }
      const result = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: me.user.id, permissions: [...me.permissions] });
      return route.fulfill({ status: result.status, headers: { ...CORS, ...result.headers }, body: result.body });
    });
  }
  return record;
}

/** The audience of a bearer token header. */
export function audienceOf(header: string): unknown {
  const payload = header.replace(/^Bearer /, '').split('.')[1] ?? '';
  try {
    return (JSON.parse(Buffer.from(payload, 'base64url').toString()) as { aud?: unknown }).aud;
  } catch {
    return undefined;
  }
}
