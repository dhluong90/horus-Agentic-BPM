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
  hosts: [{ id: 'loans', label: { en: 'Retail banking', vi: 'Ngân hàng bán lẻ' }, baseUrl: '', environment: 'demo' }],
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

/** A reader: may open rule sets and export them, and may not author or approve. */
export const meDan: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'dan', displayName: 'Đỗ Văn Dân' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['rules:read'],
  roles: ['PRICING_READER'],
};

export const meMinh: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'minh', displayName: 'Trần Văn Minh' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['rules:read', 'rules:approve', 'rules:log:read'],
  roles: ['PRICING_APPROVER'],
};

/** A compliance reviewer of escalated agent steps on a host that runs both engines. */
export const meHoa: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'hoa', displayName: 'Phạm Thị Hoa' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['rules:read', 'processes:read', 'processes:task:work'],
  roles: ['COMPLIANCE_REVIEWER'],
};

/** A back-office operator of the loan queue: works her own and available tasks (CKP-046, CKP-047). */
export const meAn: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'an', displayName: 'Lê Thị An' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['processes:task:work'],
  roles: ['credit-officer'],
  groups: ['loans-operations'],
};

/** A supervisor of the loan queue: sees every task and hands tasks on with a reason (CKP-048, PD-110). */
export const meSau: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'sau', displayName: 'Võ Thị Sáu' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['processes:task:work', 'processes:task:manage'],
  roles: ['credit-officer', 'credit-approver', 'operations-supervisor'],
  groups: ['loans-operations'],
};

/** An operations officer of the loan service: reads incidents, retries them and proposes results (CKP-008). */
export const meTuan: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'tuan', displayName: 'Trần Minh Tuấn' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['processes:read', 'processes:incident:manage'],
  roles: ['operations-officer'],
};

/** A second operations officer: proposed the pending result of the demo, so a colleague approves it (PD-126). */
export const meLinh: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'linh', displayName: 'Đặng Thùy Linh' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['processes:read', 'processes:incident:manage'],
  roles: ['operations-officer'],
};

/** The example host with the Process Engine beside the Rule Engine. */
export const metaWithProcesses: BpmMetaV1 = {
  ...metaRulesOnly,
  modules: [...metaRulesOnly.modules, { module: 'processes', engineVersion: '0.2.0', apiVersion: 'v1', features: [] }],
};

/** An auditor: reads rule sets and the decision log, exports evidence; neither authors nor approves. */
export const meHa: BpmMeV1 = {
  schemaVersion: 1,
  user: { id: 'ha', displayName: 'Phạm Thu Hà' },
  tenant: { current: 'bank-a', allowed: ['bank-a'] },
  permissions: ['rules:read', 'rules:log:read'],
  roles: ['INTERNAL_AUDITOR'],
};

/** A permission name from a newer host that does not match this cockpit's pattern (a hyphen in the action). */
export const UNREADABLE_PERMISSION = 'rules:log:search-masked';

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
export const SCENARIOS = ['ready', 'ready-approver', 'me-forbidden', 'me-internal-error', 'meta-internal-error', 'signed-out', 'me-bad-shape', 'me-unauthenticated', 'meta-not-found', 'registry-invalid', 'registry-duplicate-id', 'ready-longest-labels', 'registry-oidc', 'rules-author', 'rules-approver', 'rules-author-approver', 'rules-author-drafts-off', 'rules-author-drafts-default', 'rules-reader', 'me-unknown-permission', 'meta-unknown-module', 'meta-bad-known-module', 'processes-reviewer', 'processes-starter', 'processes-task-only', 'processes-approver', 'processes-requester', 'rules-auditor', 'rules-auditor-no-log', 'tasks-operator', 'tasks-supervisor', 'rules-author-simulate', 'incidents-operator', 'incidents-proposer', 'migration-operator', 'migration-approver', 'migration-reader'] as const;
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
    case 'rules-author-simulate':
      // An author who may also simulate her drafts (CKP-004).
      return { ...ok, me: json({ ...meLan, permissions: [...meLan.permissions, 'rules:evaluate', 'rules:simulate'] }) };
    case 'rules-approver':
      return { ...ok, me: json({ ...meMinh, permissions: [...meMinh.permissions, 'rules:evaluate', 'rules:simulate'] }) };
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
    case 'rules-reader':
      return { ...ok, me: json(meDan) };
    case 'processes-reviewer':
      return { ...ok, meta: json(metaWithProcesses), me: json(meHoa) };
    case 'processes-starter':
      // Lan started case APP-1042: four eyes keeps her from deciding its review.
      return { ...ok, meta: json(metaWithProcesses), me: json({ ...meLan, permissions: [...meLan.permissions, 'processes:read', 'processes:task:work'] }) };
    case 'processes-task-only':
      return { ...ok, meta: json(metaWithProcesses), me: json({ ...meHoa, permissions: ['processes:task:work'] }) };
    case 'processes-approver':
      // Minh approves process changes as the second person (CKP-024).
      return { ...ok, meta: json(metaWithProcesses), me: json({ ...meMinh, permissions: [...meMinh.permissions, 'processes:read', 'processes:approve'] }) };
    case 'processes-requester':
      // Lan imported and requested version 3 of the onboarding process; she also holds the approve permission.
      return { ...ok, meta: json(metaWithProcesses), me: json({ ...meLan, permissions: [...meLan.permissions, 'processes:read', 'processes:author', 'processes:approve'] }) };
    case 'migration-operator':
      // Lan plans, checks and proposes a migration; a second holder of the permission approves it.
      return { ...ok, meta: json(metaWithProcesses), me: json({ ...meLan, permissions: [...meLan.permissions, 'processes:read', 'processes:migration:manage'] }) };
    case 'migration-approver':
      return { ...ok, meta: json(metaWithProcesses), me: json({ ...meMinh, permissions: [...meMinh.permissions, 'processes:read', 'processes:migration:manage'] }) };
    case 'migration-reader':
      return { ...ok, meta: json(metaWithProcesses), me: json({ ...meHoa, permissions: ['processes:read'] }) };
    case 'rules-auditor':
      return { ...ok, me: json(meHa) };
    case 'tasks-operator':
      return { ...ok, meta: json(metaWithProcesses), me: json(meAn) };
    case 'tasks-supervisor':
      return { ...ok, meta: json(metaWithProcesses), me: json(meSau) };
    case 'incidents-operator':
      return { ...ok, meta: json(metaWithProcesses), me: json(meTuan) };
    case 'incidents-proposer':
      return { ...ok, meta: json(metaWithProcesses), me: json(meLinh) };
    case 'rules-auditor-no-log':
      // The auditor without the decision log: history and versions-only evidence (CKP-073).
      return { ...ok, me: json({ ...meHa, permissions: ['rules:read'] }) };
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
    case 'me-unknown-permission':
      // A newer host lists a permission name this cockpit cannot read; the cockpit drops it and signs Lan in.
      return { ...ok, me: json({ ...meLan, permissions: [...meLan.permissions, UNREADABLE_PERMISSION] }) };
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
  if (scenario === 'rules-reader') return meDan.user.id;
  if (scenario === 'processes-reviewer' || scenario === 'processes-task-only' || scenario === 'migration-reader') return meHoa.user.id;
  if (scenario === 'rules-auditor' || scenario === 'rules-auditor-no-log') return meHa.user.id;
  if (scenario === 'tasks-operator') return meAn.user.id;
  if (scenario === 'tasks-supervisor') return meSau.user.id;
  if (scenario === 'incidents-operator') return meTuan.user.id;
  if (scenario === 'incidents-proposer') return meLinh.user.id;
  return scenario === 'ready-approver' || scenario === 'rules-approver' || scenario === 'processes-approver' || scenario === 'migration-approver' ? meMinh.user.id : meLan.user.id;
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
