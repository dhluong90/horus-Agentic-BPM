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
  modules: [{ module: 'rules', engineVersion: '0.1.0', apiVersion: 'v1', features: [] }],
  tenancy: { switching: false },
  limits: { maxPageSize: 200 },
};

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

/** Named situations a test or the dev server can select. */
export const SCENARIOS = ['ready', 'ready-approver', 'me-forbidden', 'me-internal-error', 'meta-internal-error', 'signed-out', 'me-bad-shape', 'meta-not-found', 'registry-invalid', 'registry-oidc'] as const;
export type Scenario = (typeof SCENARIOS)[number];

export interface MockResponse {
  status: number;
  contentType: string;
  body: unknown;
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
    case 'me-forbidden':
      return { ...ok, me: problem(problemPermissionDenied) };
    case 'me-internal-error':
      return { ...ok, me: problem(problemInternal) };
    case 'meta-internal-error':
      return { ...ok, meta: problem(problemInternal) };
    case 'signed-out':
      return { ...ok, meta: problem(problemUnauthenticated), me: problem(problemUnauthenticated) };
    case 'me-bad-shape':
      return { ...ok, me: json({ schemaVersion: 1, user: { id: '' }, permissions: [] }) };
    case 'meta-not-found':
      // What a host without the metadata endpoints answers: Spring Boot's default error body.
      return { ...ok, meta: json({ timestamp: '2026-11-19T02:00:00.000+00:00', status: 404, error: 'Not Found', path: '/api/bpm/meta' }, 404) };
    case 'registry-invalid':
      return { ...ok, registry: json({ ...registryEmbedded, hosts: [{ id: 'loans', label: 'Loans', baseUrl: 'http://loans.bank.example' }] }) };
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

export function isScenario(value: string | null | undefined): value is Scenario {
  return SCENARIOS.some((scenario) => scenario === value);
}
