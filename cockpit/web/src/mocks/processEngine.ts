import {
  DEMO_DEFINITIONS, FORM_TASK_ID, FORM_TASK_NAME, INSTANCE_ID, kycDiff, MASKED_TASK_ID, RECOMMENDATIONS, REVIEW_CONTEXT, REVIEW_TASK_ID, REVIEW_TASK_NAME,
  VI_DEMO_DEFINITIONS, VI_REVIEW_CONTEXT, type DemoDefinition,
} from './processDemo';
import { demoCases } from './caseDemo';

/*
 * An in-memory Process Engine for the dev server, Vitest and Playwright, in the response shapes of
 * process-engine/api/openapi.yaml: definitions with their BPMN export, one instance and its review tasks. It keeps
 * the engine's rules the cockpit depends on: four eyes on the review (the starter and the reporter of the refused
 * answer are refused), claim before complete, If-Match and Idempotency-Key on commands, and the output schema check
 * of the reviewer's answer (422 FORM_VALIDATION_FAILED with text violations). Definition versions follow the
 * lifecycle with four eyes: the graph diff from the version in effect, and approve bound to the reviewed content
 * hash (PD-139): the requester and the importer are refused, a missing hash is 400 CONTENT_HASH_REQUIRED, a cut-off
 * change list is 409 CHANGE_SET_TRUNCATED and other content is 409 CONTENT_CHANGED.
 */

export interface ProcessRequest {
  method: string;
  path: string;
  headers: Readonly<Record<string, string>>;
  body: string;
  user: string;
  permissions: readonly string[];
}

export interface ProcessAnswer {
  status: number;
  headers: Record<string, string>;
  body: string;
}

interface StoredTask {
  id: string;
  name: string;
  nodeId: string;
  status: 'OPEN' | 'CLAIMED' | 'COMPLETED' | 'CANCELLED';
  lockVersion: number;
  claimedBy: string | null;
  claimedAt: string | null;
  completedBy: string | null;
  completedAt: string | null;
  review: Record<string, unknown> | undefined;
}

export interface RecordedOverride {
  taskId: string;
  reviewedBy: string;
  reason: string;
  result: Record<string, unknown>;
  idempotencyKey: string | undefined;
}

const BASE = '/api/bpm/processes/v1';
const ERROR_TYPE = 'https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#';
const NOW = '2026-11-20T03:15:00Z';

/** One decision on a definition version, as the engine received it. */
export interface RecordedDecision {
  action: 'approve' | 'reject';
  key: string;
  version: number;
  by: string;
  body: Record<string, unknown>;
  ifMatch: string | undefined;
  idempotencyKey: string | undefined;
}

const DEFAULT_HASH = 'b3f1c2d4e5a6978812ab34cd56ef7890a1b2c3d4e5f60718293a4b5c6d7e8f90';

export interface ProcessSeed {
  definitions?: readonly DemoDefinition[];
  /** The change list of the pending onboarding version is cut off at the engine's limit. */
  truncatedDiff?: boolean;
  startedBy?: string;
  reportedBy?: string;
  /** The tenant's language of the demo data: names, reasons and answer words. */
  locale?: 'en' | 'vi';
  /** No case at all, for the empty states. */
  noCases?: boolean;
  /** How the activities read answers: as the engine does, as an older engine without it (404), or failing (500). */
  activities?: 'ok' | 'missing' | 'failing';
}

export function createFakeProcessEngine(seed: ProcessSeed = {}) {
  const locale = seed.locale ?? 'en';
  // Stored versions, changed in place by approve and reject.
  const definitions: (DemoDefinition & { lockVersion: number; approvedBy: string | null; approvedAt: string | null; decisionComment: string | null })[] =
    (seed.definitions ?? (locale === 'vi' ? VI_DEMO_DEFINITIONS : DEMO_DEFINITIONS)).map((d) => ({ ...d, lockVersion: 2, approvedBy: d.status === 'ACTIVE' ? 'minh' : null, approvedAt: null, decisionComment: null }));
  const decisions: RecordedDecision[] = [];
  const startedBy = seed.startedBy ?? 'lan';
  const review = { ...(locale === 'vi' ? VI_REVIEW_CONTEXT : REVIEW_CONTEXT), ...(seed.reportedBy !== undefined ? { reportedBy: seed.reportedBy } : {}) };
  const recommendations: readonly string[] = RECOMMENDATIONS[locale];
  const tasks = new Map<string, StoredTask>([
    [REVIEW_TASK_ID, { id: REVIEW_TASK_ID, name: REVIEW_TASK_NAME[locale], nodeId: 'assess-documents', status: 'OPEN', lockVersion: 0, claimedBy: null, claimedAt: null, completedBy: null, completedAt: null, review }],
    [MASKED_TASK_ID, { id: MASKED_TASK_ID, name: REVIEW_TASK_NAME[locale], nodeId: 'assess-documents', status: 'CLAIMED', lockVersion: 1, claimedBy: 'hoa', claimedAt: NOW, completedBy: null, completedAt: null,
      review: { ...review, output: { ...review.output, summary: '***' }, outputFields: 4 } }],
    [FORM_TASK_ID, { id: FORM_TASK_ID, name: FORM_TASK_NAME[locale], nodeId: 'capture', status: 'OPEN', lockVersion: 0, claimedBy: null, claimedAt: null, completedBy: null, completedAt: null, review: undefined }],
  ]);
  const overrides: RecordedOverride[] = [];
  const requests: { method: string; path: string; headers: Readonly<Record<string, string>>; body: string }[] = [];

  const json = (status: number, body: unknown, headers: Record<string, string> = {}): ProcessAnswer => ({ status, headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  const problem = (status: number, code: string, extra: Record<string, unknown> = {}): ProcessAnswer => ({
    status,
    headers: { 'Content-Type': 'application/problem+json' },
    body: JSON.stringify({ type: `${ERROR_TYPE}${code.toLowerCase()}`, title: code, status, code, correlationId: '01JD4C0RRE1AT10N0000000PE1', ...extra }),
  });

  const cases = seed.noCases === true ? [] : demoCases(startedBy, { review: REVIEW_TASK_NAME[locale], approve: locale === 'vi' ? 'Duyệt khách hàng rủi ro cao' : 'Approve high-risk customer' });
  const instance = () => cases.find((item) => item.instance.id === INSTANCE_ID)?.instance ?? ({
    id: INSTANCE_ID, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: 'APP-1042', status: 'RUNNING',
    variables: {}, tokens: [], tasks: [], lockVersion: 4, startedBy, startedAt: '2026-11-20T02:58:00Z', endedAt: null,
  });

  const taskBody = (task: StoredTask, request: ProcessRequest) => {
    const fourEyesBlocked = task.review !== undefined && (request.user === startedBy || request.user === task.review['reportedBy']);
    const allowed: string[] = [];
    if (request.permissions.includes('processes:task:work') && !fourEyesBlocked) {
      if (task.status === 'OPEN') allowed.push('claim');
      if (task.status === 'CLAIMED' && task.claimedBy === request.user) allowed.push('unclaim', 'complete');
    }
    return {
      id: task.id, instanceId: INSTANCE_ID, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: 'APP-1042',
      nodeId: task.nodeId, name: task.name, assignee: 'role:COMPLIANCE_REVIEWER', fourEyes: true, status: task.status,
      lockVersion: task.lockVersion, claimedBy: task.claimedBy, claimedAt: task.claimedAt, createdAt: '2026-11-20T03:02:00Z',
      completedBy: task.completedBy, completedAt: task.completedAt, allowedActions: allowed,
      ...(task.review !== undefined ? { review: task.review } : {}),
    };
  };

  /** The step's output schema, as the engine checks the reviewer's answer. */
  const schemaViolations = (result: Record<string, unknown>): string[] => {
    const out: string[] = [];
    const score = result['riskScore'];
    if (typeof score !== 'number' || !Number.isInteger(score)) out.push('$.riskScore: expected integer');
    else if (score < 0 || score > 100) out.push('$.riskScore: must be between 0 and 100');
    if (!recommendations.includes(String(result['recommendation']))) out.push(`$.recommendation: must be one of ${recommendations.join(', ')}`);
    if (typeof result['incomeVerified'] !== 'boolean') out.push('$.incomeVerified: expected boolean');
    for (const [key, value] of Object.entries(result)) if (value === '***') out.push(`$.${key}: masked value`);
    return out;
  };

  function effectiveOf(key: string): number | null {
    const active = definitions.filter((d) => d.key === key && d.status === 'ACTIVE').map((d) => d.version);
    return active.length > 0 ? Math.max(...active) : null;
  }

  function versionBody(found: (typeof definitions)[number]) {
    return {
      id: `01JD4DEF${found.key.toUpperCase().replace(/-/g, '').slice(0, 14).padEnd(14, '0')}${String(found.version).padStart(4, '0')}`,
      key: found.key, version: found.version, status: found.status, name: found.name, definitionHash: found.definitionHash ?? DEFAULT_HASH,
      sourceFormat: 'BPMN_XML', sourceHash: 'c0ffee00', nodeCount: found.nodeCount, approvedBy: found.approvedBy, approvedAt: found.approvedAt,
      createdBy: found.createdBy ?? 'lan', createdAt: found.lastChangedAt, source: 'IMPORT', lockVersion: found.lockVersion,
      effectiveFrom: found.effectiveFrom ?? (found.status === 'ACTIVE' ? '2026-11-01T00:00:00Z' : null),
      requestedBy: found.requestedBy ?? null, requestedAt: found.requestedAt ?? null, decisionComment: found.decisionComment,
    };
  }

  /** The change list from the version in effect; only the demo's pending onboarding version has changes. */
  function diffBody(found: (typeof definitions)[number], base: (typeof definitions)[number]) {
    if (found.key === 'kyc-onboarding' && found.version === 3 && base.version === 2) return kycDiff(locale, seed.truncatedDiff === true);
    const hash = found.definitionHash ?? DEFAULT_HASH;
    return {
      definitionKey: found.key, approvableHash: hash,
      base: { version: base.version, status: base.status, definitionHash: base.definitionHash ?? DEFAULT_HASH },
      target: { version: found.version, status: found.status, definitionHash: hash },
      identical: true, nodesAdded: [], nodesRemoved: [], nodesChanged: [], edgesAdded: [], edgesRemoved: [], truncated: false,
    };
  }

  function lifecycleCommand(request: ProcessRequest, key: string, number: number, command: string, header: (name: string) => string | undefined): ProcessAnswer {
    if (!request.permissions.includes(command === '/diff' ? 'processes:read' : 'processes:approve')) return problem(403, 'PERMISSION_DENIED');
    const found = definitions.find((d) => d.key === key && d.version === number);
    if (!found) return problem(404, 'DEFINITION_NOT_FOUND');
    if (command === '/diff') {
      if (request.method !== 'GET') return problem(400, 'INPUT_INVALID');
      const effective = effectiveOf(key);
      const base = definitions.find((d) => d.key === key && d.version === effective);
      if (!base) return problem(404, 'NO_EFFECTIVE_VERSION');
      return json(200, diffBody(found, base));
    }
    if (request.method !== 'POST') return problem(400, 'INPUT_INVALID');
    const ifMatch = header('If-Match');
    const idempotencyKey = header('Idempotency-Key');
    if (ifMatch === undefined || idempotencyKey === undefined) return problem(428, 'PRECONDITION_REQUIRED');
    let body: Record<string, unknown>;
    try {
      body = JSON.parse(request.body || '{}') as Record<string, unknown>;
    } catch {
      return problem(400, 'INPUT_INVALID');
    }
    const action = command === ':approve' ? 'approve' : 'reject';
    decisions.push({ action, key, version: number, by: request.user, body, ifMatch, idempotencyKey });
    if (found.status !== 'PENDING_APPROVAL') return problem(409, 'INVALID_STATE');
    if (request.user === found.requestedBy || request.user === (found.createdBy ?? 'lan')) return problem(403, 'SELF_APPROVAL_FORBIDDEN');
    if (ifMatch !== `"${String(found.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT');
    if (action === 'reject') {
      const reason = typeof body['reason'] === 'string' ? body['reason'].trim() : '';
      if (reason === '') return problem(400, 'INPUT_INVALID');
      Object.assign(found, { status: 'DRAFT', decisionComment: reason, lockVersion: found.lockVersion + 1, requestedBy: undefined, requestedAt: undefined });
      return json(200, versionBody(found), { ETag: `"${String(found.lockVersion)}"` });
    }
    const expected = body['expectedDefinitionHash'];
    if (typeof expected !== 'string' || expected === '') return problem(400, 'CONTENT_HASH_REQUIRED');
    const effective = definitions.find((d) => d.key === key && d.version === effectiveOf(key));
    if (effective && diffBody(found, effective).truncated) return problem(409, 'CHANGE_SET_TRUNCATED');
    const current = found.definitionHash ?? DEFAULT_HASH;
    if (expected !== current) return problem(409, 'CONTENT_CHANGED', { currentDefinitionHash: current, currentLockVersion: found.lockVersion });
    const comment = typeof body['comment'] === 'string' ? body['comment'] : null;
    Object.assign(found, { status: 'ACTIVE', approvedBy: request.user, approvedAt: '2026-11-21T03:05:00Z', decisionComment: comment, lockVersion: found.lockVersion + 1 });
    return json(200, versionBody(found), { ETag: `"${String(found.lockVersion)}"` });
  }

  /**
   * listPendingApprovals (PRE-190): every pending version of the tenant, oldest request first, with what this caller
   * may send and why approve or reject is not offered. One page holds the demo; `limit` is checked as the engine does.
   */
  function pendingApprovals(url: URL, request: ProcessRequest): ProcessAnswer {
    if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
    const limitText = url.searchParams.get('limit');
    const limit = limitText === null ? 50 : Number(limitText);
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) return problem(400, 'QUERY_INVALID');
    const approver = request.permissions.includes('processes:approve');
    const data = definitions
      .filter((d) => d.status === 'PENDING_APPROVAL')
      .map((d) => {
        const requestedBy = d.requestedBy ?? d.createdBy ?? 'lan';
        const self = request.user === requestedBy || request.user === (d.createdBy ?? 'lan');
        const denial = !approver ? 'PERMISSION_REQUIRED' : self ? 'SELF_APPROVAL_FORBIDDEN' : undefined;
        return {
          key: d.key, name: d.name, version: d.version, definitionHash: d.definitionHash ?? DEFAULT_HASH, lockVersion: d.lockVersion,
          requestedBy, requestedAt: d.requestedAt ?? d.lastChangedAt,
          allowedActions: denial === undefined ? ['approve', 'reject'] : [],
          deniedActions: denial === undefined ? {} : { approve: denial, reject: denial },
        };
      })
      .sort((a, b) => a.requestedAt.localeCompare(b.requestedAt) || a.key.localeCompare(b.key))
      .slice(0, limit);
    return json(200, { data, nextCursor: null, hasNext: false });
  }

  function handle(request: ProcessRequest): ProcessAnswer {
    const url = new URL(request.path, 'http://host.test');
    const path = decodeURIComponent(url.pathname).replace(/^.*?\/api\/bpm\/processes\/v1/, BASE);
    requests.push({ method: request.method, path: `${path}${url.search}`, headers: request.headers, body: request.body });
    const header = (name: string) => request.headers[name] ?? request.headers[name.toLowerCase()];

    if (request.method === 'GET' && path === `${BASE}/definitions`) {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      const latest = new Map<string, (typeof definitions)[number]>();
      for (const d of definitions) if ((latest.get(d.key)?.version ?? 0) < d.version) latest.set(d.key, d);
      const data = [...latest.values()].sort((a, b) => a.key.localeCompare(b.key)).map((d) => ({
        key: d.key, name: d.name, latestVersion: d.version, latestStatus: d.status, effectiveVersion: effectiveOf(d.key), lastChangedAt: d.lastChangedAt,
      }));
      return json(200, { data, nextCursor: null, hasNext: false });
    }
    if (request.method === 'GET' && path === `${BASE}/approvals`) return pendingApprovals(url, request);
    const lifecycle = /^\/api\/bpm\/processes\/v1\/definitions\/([^/]+)\/versions\/(\d+)(\/diff|:approve|:reject)$/.exec(path);
    if (lifecycle) return lifecycleCommand(request, lifecycle[1] ?? '', Number(lifecycle[2]), lifecycle[3] ?? '', header);
    const version = /^\/api\/bpm\/processes\/v1\/definitions\/([^/]+)\/versions\/(\d+)(\/export)?$/.exec(path);
    if (request.method === 'GET' && version) {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      const found = definitions.find((d) => d.key === version[1] && d.version === Number(version[2]));
      if (!found) return problem(404, 'DEFINITION_NOT_FOUND');
      if (version[3] !== undefined) {
        return { status: 200, headers: { 'Content-Type': 'application/bpmn+xml', 'Content-Disposition': `attachment; filename="${found.key}-v${String(found.version)}.bpmn"` }, body: found.bpmn };
      }
      return json(200, versionBody(found), { ETag: `"${found.definitionHash ?? DEFAULT_HASH}"` });
    }
    if (request.method === 'GET' && path === `${BASE}/instances`) {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      const key = url.searchParams.get('definitionKey');
      const statuses = (url.searchParams.get('status') ?? 'RUNNING').split(',');
      const data = cases.map((item) => item.instance).filter((item) => (key === null || item.definitionKey === key) && statuses.includes(item.status))
        .map((item) => ({ id: item.id, definitionKey: item.definitionKey, definitionVersion: item['definitionVersion'], businessKey: item['businessKey'], status: item.status, lockVersion: item['lockVersion'], startedBy: item['startedBy'], startedAt: item.startedAt, endedAt: item['endedAt'] }));
      return json(200, { data, nextCursor: null, hasNext: false });
    }
    const instanceMatch = /^\/api\/bpm\/processes\/v1\/instances\/([^/:]+)(\/activities)?$/.exec(path);
    if (request.method === 'GET' && instanceMatch) {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      const id = instanceMatch[1] ?? '';
      const found = id === INSTANCE_ID ? { instance: instance(), activities: cases.find((item) => item.instance.id === id)?.activities } : cases.find((item) => item.instance.id === id);
      if (!found) return problem(404, 'INSTANCE_NOT_FOUND');
      if (instanceMatch[2] === undefined) return json(200, found.instance, { ETag: `"${String(found.instance['lockVersion'])}"` });
      if (seed.activities === 'missing') return problem(404, 'INPUT_INVALID');
      if (seed.activities === 'failing') return problem(500, 'INTERNAL');
      if (!found.activities) return problem(404, 'INSTANCE_NOT_FOUND');
      return json(200, found.activities, { ETag: '"a1b2c3d4e5f60718293a4b5c6d7e8f90"' });
    }
    const taskMatch = /^\/api\/bpm\/processes\/v1\/tasks\/([^/:]+)(?::(claim|unclaim|complete))?$/.exec(path);
    if (taskMatch) {
      const task = tasks.get(taskMatch[1] ?? '');
      if (!request.permissions.includes('processes:task:work')) return problem(403, 'PERMISSION_DENIED');
      if (!task) return problem(404, 'TASK_NOT_FOUND');
      const command = taskMatch[2];
      if (request.method === 'GET' && command === undefined) return json(200, taskBody(task, request), { ETag: `"${String(task.lockVersion)}"` });
      if (request.method !== 'POST' || command === undefined) return problem(400, 'INPUT_INVALID');
      const ifMatch = header('If-Match');
      if (ifMatch === undefined || header('Idempotency-Key') === undefined) return problem(428, 'PRECONDITION_REQUIRED');
      if (ifMatch !== `"${String(task.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT');
      const fourEyesBlocked = task.review !== undefined && (request.user === startedBy || request.user === task.review['reportedBy']);
      if (fourEyesBlocked) return problem(403, 'SELF_APPROVAL_FORBIDDEN');
      if (command === 'claim') {
        if (task.status !== 'OPEN') return problem(409, 'INVALID_STATE');
        Object.assign(task, { status: 'CLAIMED', claimedBy: request.user, claimedAt: NOW, lockVersion: task.lockVersion + 1 });
        return json(200, taskBody(task, request), { ETag: `"${String(task.lockVersion)}"` });
      }
      if (command === 'unclaim') {
        if (task.status !== 'CLAIMED') return problem(409, 'INVALID_STATE');
        if (task.claimedBy !== request.user) return problem(403, 'TASK_NOT_ASSIGNED');
        Object.assign(task, { status: 'OPEN', claimedBy: null, claimedAt: null, lockVersion: task.lockVersion + 1 });
        return json(200, taskBody(task, request), { ETag: `"${String(task.lockVersion)}"` });
      }
      if (task.status === 'OPEN') return problem(409, 'INVALID_STATE');
      if (task.status !== 'CLAIMED' || task.claimedBy !== request.user) return problem(403, 'TASK_NOT_ASSIGNED');
      let body: { result?: Record<string, unknown>; reason?: string };
      try {
        body = JSON.parse(request.body || '{}') as typeof body;
      } catch {
        return problem(400, 'INPUT_INVALID');
      }
      const result = body.result ?? {};
      if (task.review !== undefined) {
        const violations = schemaViolations(result);
        if (violations.length > 0) return problem(422, 'FORM_VALIDATION_FAILED', { violations });
        overrides.push({ taskId: task.id, reviewedBy: request.user, reason: body.reason ?? '', result, idempotencyKey: header('Idempotency-Key') });
      }
      Object.assign(task, { status: 'COMPLETED', completedBy: request.user, completedAt: '2026-11-20T03:20:00Z', lockVersion: task.lockVersion + 1 });
      return json(200, instance(), { ETag: '"5"' });
    }
    return problem(404, 'INPUT_INVALID');
  }

  return { handle, overrides, requests, decisions };
}
