import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../api/generated/bpm-meta.v1';

/**
 * Seed data for the dev server, Vitest and Playwright. It follows the host contract of
 * cockpit/api/schema and the example of the host metadata issue: user lan in tenant bank-a
 * with rules:read and rules:author.
 */
export const registryEmbedded = {
  schemaVersion: 1,
  auth: { mode: 'host-session' },
  hosts: [{ id: 'loans', label: { en: 'Loan origination', vi: 'Khởi tạo khoản vay' }, baseUrl: '', environment: 'demo' }],
} as const;

export const metaRulesOnly: BpmMetaV1 = {
  schemaVersion: 1,
  hostId: 'loans',
  // Like the example host: cockpit draft editing turned on (PD-094 makes it off when the setting is missing).
  modules: [{ module: 'rules', engineVersion: '0.1.0', apiVersion: 'v1', features: [], settings: { 'authoring.cockpit-drafts': true } }],
  tenancy: { switching: false },
  limits: { maxPageSize: 200 },
};

/** A module name no cockpit of this release knows: what a newer host with a third engine module reports. */
export const UNKNOWN_MODULE = 'cases';

const rulesModule = { module: 'rules', engineVersion: '0.1.0', apiVersion: 'v1', features: [], settings: { 'authoring.cockpit-drafts': true } } as const;
const processesModule = { module: 'processes', engineVersion: '0.1.0', apiVersion: 'v1', features: [] } as const;
const unknownModule = { module: UNKNOWN_MODULE, engineVersion: '0.3.0', apiVersion: 'v1', features: ['cases.read'] } as const;

/** A newer host: rules, processes and a module this cockpit does not know (cockpit DESIGN 5.2). */
export const metaWithUnknownModule = {
  ...metaRulesOnly,
  modules: [rulesModule, processesModule, unknownModule],
};

/** The same host with a malformed known module: the strict schema still rejects it. */
export const metaWithBadKnownModule = {
  ...metaRulesOnly,
  modules: [{ ...rulesModule, engineVersion: '0.1' }, processesModule, unknownModule],
};

export const META_MODULES = { rules: rulesModule, processes: processesModule, unknown: unknownModule } as const;

export const meLan: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'lan', displayName: 'Nguyễn Thị Lan' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['rules:read', 'rules:author'],
  roles: ['PRICING_AUTHOR'],
};

export const meMinh: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'minh', displayName: 'Trần Văn Minh' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['rules:read', 'rules:approve', 'rules:log:read'],
  roles: ['PRICING_APPROVER'],
};

const ERROR_TYPE = 'https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#';

export const problemPermissionDenied = {
  type: `${ERROR_TYPE}permission_denied`,
  title: 'Permission denied',
  status: 403,
  code: 'PERMISSION_DENIED',
  correlationId: '01JD3A7Q2M5N8P1R4S6T9V0W2X',
} as const;

export const problemInternal = {
  type: `${ERROR_TYPE}internal_error`,
  title: 'Internal error',
  status: 500,
  code: 'INTERNAL_ERROR',
  correlationId: '01JD3B2C4D6E8F0G2H4J6K8M0N',
} as const;

export const problemUnauthenticated = {
  type: `${ERROR_TYPE}unauthenticated`,
  title: 'Unauthenticated',
  status: 401,
  code: 'UNAUTHENTICATED',
  correlationId: '01JD3C5D7E9F1G3H5J7K9M1N3P',
} as const;

/** The longest values the starter accepts: host label 80, environment 40, banner 40 characters. */
export const LONGEST = {
  label: 'Hệ thống khởi tạo, thẩm định và phê duyệt khoản vay tiêu dùng cho khách hàng cá n'.slice(0, 80),
  environment: 'Môi trường kiểm thử chấp nhận người dùng',
  banner: 'MÔI TRƯỜNG KIỂM THỬ, KHÔNG DÙNG DỮ LIỆU THẬT'.slice(0, 40),
  userName: 'Nguyễn Hoàng Phương Thảo Nguyên',
} as const;

/** Named situations a test or the dev server can select. */
export const SCENARIOS = ['ready', 'ready-approver', 'me-forbidden', 'me-internal-error', 'meta-internal-error', 'signed-out', 'me-bad-shape', 'me-unauthenticated', 'meta-not-found', 'registry-invalid', 'registry-duplicate-id', 'ready-longest-labels', 'registry-oidc', 'rules-author', 'rules-approver', 'rules-author-approver', 'rules-author-drafts-off', 'rules-author-drafts-default', 'meta-unknown-module', 'meta-bad-known-module'] as const;
export type Scenario = (typeof SCENARIOS)[number];

export interface MockResponse {
  status: number;
  contentType: string;
  body: unknown;
  location?: string;
}

const json = (body: unknown, status = 200): MockResponse => ({ status, contentType: 'application/json', body });
const problem = (body: { status: number }): MockResponse => ({ status: body.status, contentType: 'application/problem+json', body });

/** The answer of the mock host for each scenario: registry, /api/bpm/meta and /api/bpm/me. */
export function scenarioResponses(scenario: Scenario): { registry: MockResponse; meta: MockResponse; me: MockResponse } {
  const ok = { registry: json(registryEmbedded), meta: json(metaRulesOnly), me: json(meLan) };
  switch (scenario) {
    case 'ready':
      return ok;
    case 'ready-approver':
      return { ...ok, me: json(meMinh) };
    case 'rules-author':
      return { ...ok, me: json({ ...meLan, permissions: [...meLan.permissions, 'rules:evaluate'] }) };
    case 'rules-approver':
      return { ...ok, me: json({ ...meMinh, permissions: [...meMinh.permissions, 'rules:evaluate'] }) };
    case 'rules-author-drafts-default':
      // A host that does not report authoring.cockpit-drafts at all: off by default in 0.1.0 (PD-094).
      return {
        ...ok,
        meta: json({ ...metaRulesOnly, modules: [{ module: 'rules', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }] }),
        me: json({ ...meLan, permissions: [...meLan.permissions, 'rules:evaluate'] }),
      };
    case 'rules-author-drafts-off':
      return {
        ...ok,
        meta: json({ ...metaRulesOnly, modules: [{ ...metaRulesOnly.modules[0], settings: { 'authoring.cockpit-drafts': false } }] }),
        me: json({ ...meLan, permissions: [...meLan.permissions, 'rules:evaluate'] }),
      };
    case 'rules-author-approver':
      return { ...ok, me: json({ ...meLan, permissions: ['rules:read', 'rules:author', 'rules:approve', 'rules:evaluate'] }) };
    case 'me-forbidden':
      return { ...ok, me: problem(problemPermissionDenied) };
    case 'me-internal-error':
      return { ...ok, me: problem(problemInternal) };
    case 'meta-internal-error':
      return { ...ok, meta: problem(problemInternal) };
    case 'meta-unknown-module':
      return { ...ok, meta: json(metaWithUnknownModule) };
    case 'meta-bad-known-module':
      // The unknown module is skipped, but a malformed known module still fails the strict schema.
      return { ...ok, meta: json(metaWithBadKnownModule) };
    case 'signed-out': {
      // The host's sign-in redirect for an ended session.
      const toSignIn: MockResponse = { status: 302, contentType: 'text/html', body: '', location: '/login' };
      return { ...ok, meta: toSignIn, me: toSignIn };
    }
    case 'me-unauthenticated':
      return { ...ok, meta: problem(problemUnauthenticated), me: problem(problemUnauthenticated) };
    case 'me-bad-shape':
      return { ...ok, me: json({ schemaVersion: 1, user: { id: '' }, permissions: [] }) };
    case 'meta-not-found':
      // What a host without the metadata endpoints answers: Spring Boot's default error body.
      return { ...ok, meta: json({ timestamp: '2026-11-19T02:00:00.000+00:00', status: 404, error: 'Not Found', path: '/api/bpm/meta' }, 404) };
    case 'registry-invalid':
      return { ...ok, registry: json({ ...registryEmbedded, hosts: [{ id: 'loans', label: 'Loans', baseUrl: 'http://loans.bank.example' }] }) };
    case 'ready-longest-labels':
      return {
        registry: json({
          schemaVersion: 1,
          auth: { mode: 'host-session' },
          hosts: [{ id: 'loans', label: { en: LONGEST.label, vi: LONGEST.label }, baseUrl: '', environment: LONGEST.environment }],
          ui: { defaultLocale: 'vi', environmentBanner: LONGEST.banner },
        }),
        meta: json(metaRulesOnly),
        me: json({ ...meLan, user: { id: 'thao.nguyen', displayName: LONGEST.userName }, tenant: { current: 'ngan-hang-sao-mai-chi-nhanh-ha-noi', allowed: ['ngan-hang-sao-mai-chi-nhanh-ha-noi'] } }),
      };
    case 'registry-duplicate-id':
      return {
        ...ok,
        registry: json({
          schemaVersion: 1,
          auth: { mode: 'host-session' },
          hosts: [
            { id: 'loans', label: 'Loans', baseUrl: '' },
            { id: 'loans', label: 'Loans again', baseUrl: 'https://loans.api.bank.example' },
          ],
        }),
      };
    case 'registry-oidc':
      return {
        ...ok,
        registry: json({
          schemaVersion: 1,
          auth: { mode: 'oidc', issuer: 'https://idp.bank.example/realms/operations', clientId: 'horus-cockpit' },
          hosts: [{ id: 'loans', label: 'Loans', baseUrl: 'https://loans.api.bank.example', scope: 'loans-bpm' }],
        }),
      };
  }
}

/** The signed-in user of a scenario, for the mock Rule Engine's four-eyes check. */
export function scenarioUser(scenario: Scenario): string {
  return scenario === 'ready-approver' || scenario === 'rules-approver' ? meMinh.user.id : meLan.user.id;
}

/** The permissions /me lists in a scenario, which the mock Rule Engine uses for allowedActions and checks. */
export function scenarioPermissions(scenario: Scenario): string[] {
  const me = scenarioResponses(scenario).me.body;
  if (typeof me !== 'object' || me === null || !('permissions' in me) || !Array.isArray(me.permissions)) return [];
  return me.permissions.filter((p): p is string => typeof p === 'string');
}


export function isScenario(value: string | null | undefined): value is Scenario {
  return SCENARIOS.some((scenario) => scenario === value);
}
