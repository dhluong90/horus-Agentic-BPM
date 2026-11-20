import { DEMO_DEFINITIONS, FORM_TASK_ID, INSTANCE_ID, MASKED_TASK_ID, REVIEW_CONTEXT, REVIEW_TASK_ID, type DemoDefinition } from './processDemo';

/*
 * An in-memory Process Engine for the dev server, Vitest and Playwright, in the response shapes of
 * process-engine/api/openapi.yaml: definitions with their BPMN export, one instance and its review tasks. It keeps
 * the engine's rules the cockpit depends on: four eyes on the review (the starter and the reporter of the refused
 * answer are refused), claim before complete, If-Match and Idempotency-Key on commands, and the output schema check
 * of the reviewer's answer (422 FORM_VALIDATION_FAILED with text violations).
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

export interface ProcessSeed {
  definitions?: readonly DemoDefinition[];
  startedBy?: string;
  reportedBy?: string;
}

export function createFakeProcessEngine(seed: ProcessSeed = {}) {
  const definitions = seed.definitions ?? DEMO_DEFINITIONS;
  const startedBy = seed.startedBy ?? 'lan';
  const review = { ...REVIEW_CONTEXT, ...(seed.reportedBy !== undefined ? { reportedBy: seed.reportedBy } : {}) };
  const tasks = new Map<string, StoredTask>([
    [REVIEW_TASK_ID, { id: REVIEW_TASK_ID, name: 'Assess documents: review', nodeId: 'assess-documents', status: 'OPEN', lockVersion: 0, claimedBy: null, claimedAt: null, completedBy: null, completedAt: null, review }],
    [MASKED_TASK_ID, { id: MASKED_TASK_ID, name: 'Assess documents: review', nodeId: 'assess-documents', status: 'CLAIMED', lockVersion: 1, claimedBy: 'hoa', claimedAt: NOW, completedBy: null, completedAt: null,
      review: { ...review, output: { ...review.output, summary: '***' }, outputFields: 4 } }],
    [FORM_TASK_ID, { id: FORM_TASK_ID, name: 'Capture application', nodeId: 'capture', status: 'OPEN', lockVersion: 0, claimedBy: null, claimedAt: null, completedBy: null, completedAt: null, review: undefined }],
  ]);
  const overrides: RecordedOverride[] = [];
  const requests: { method: string; path: string; headers: Readonly<Record<string, string>>; body: string }[] = [];

  const json = (status: number, body: unknown, headers: Record<string, string> = {}): ProcessAnswer => ({ status, headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  const problem = (status: number, code: string, extra: Record<string, unknown> = {}): ProcessAnswer => ({
    status,
    headers: { 'Content-Type': 'application/problem+json' },
    body: JSON.stringify({ type: `${ERROR_TYPE}${code.toLowerCase()}`, title: code, status, code, correlationId: '01JD4C0RRE1AT10N0000000PE1', ...extra }),
  });

  const instance = () => ({
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
    if (!['APPROVE', 'REFER', 'DECLINE'].includes(String(result['recommendation']))) out.push('$.recommendation: must be one of APPROVE, REFER, DECLINE');
    if (typeof result['incomeVerified'] !== 'boolean') out.push('$.incomeVerified: expected boolean');
    for (const [key, value] of Object.entries(result)) if (value === '***') out.push(`$.${key}: masked value`);
    return out;
  };

  function handle(request: ProcessRequest): ProcessAnswer {
    const url = new URL(request.path, 'http://host.test');
    const path = decodeURIComponent(url.pathname).replace(/^.*?\/api\/bpm\/processes\/v1/, BASE);
    requests.push({ method: request.method, path: `${path}${url.search}`, headers: request.headers, body: request.body });
    const header = (name: string) => request.headers[name] ?? request.headers[name.toLowerCase()];

    if (request.method === 'GET' && path === `${BASE}/definitions`) {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      const data = [...definitions].sort((a, b) => a.key.localeCompare(b.key)).map((d) => ({
        key: d.key, name: d.name, latestVersion: d.version, latestStatus: d.status, effectiveVersion: d.effectiveVersion, lastChangedAt: d.lastChangedAt,
      }));
      return json(200, { data, nextCursor: null, hasNext: false });
    }
    const version = /^\/api\/bpm\/processes\/v1\/definitions\/([^/]+)\/versions\/(\d+)(\/export)?$/.exec(path);
    if (request.method === 'GET' && version) {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      const found = definitions.find((d) => d.key === version[1] && d.version === Number(version[2]));
      if (!found) return problem(404, 'DEFINITION_NOT_FOUND');
      if (version[3] !== undefined) {
        return { status: 200, headers: { 'Content-Type': 'application/bpmn+xml', 'Content-Disposition': `attachment; filename="${found.key}-v${String(found.version)}.bpmn"` }, body: found.bpmn };
      }
      return json(200, {
        id: `01JD4DEF${found.key.toUpperCase().replace(/-/g, '').slice(0, 14).padEnd(14, '0')}${String(found.version).padStart(4, '0')}`,
        key: found.key, version: found.version, status: found.status, name: found.name, definitionHash: 'b3f1c2d4e5a6978812ab34cd56ef7890a1b2c3d4e5f60718293a4b5c6d7e8f90',
        sourceFormat: 'BPMN_XML', sourceHash: 'c0ffee00', nodeCount: found.nodeCount, approvedBy: 'minh', createdBy: 'lan', createdAt: found.lastChangedAt,
        source: 'IMPORT', lockVersion: 2, effectiveFrom: found.status === 'ACTIVE' ? '2026-11-01T00:00:00Z' : null,
      });
    }
    if (request.method === 'GET' && path === `${BASE}/instances/${INSTANCE_ID}`) {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      return json(200, instance(), { ETag: '"4"' });
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

  return { handle, overrides, requests };
}
