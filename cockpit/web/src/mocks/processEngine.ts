import {
  DEMO_DEFINITIONS, FORM_TASK_ID, FORM_TASK_NAME, INSTANCE_ID, MASKED_TASK_ID, RECOMMENDATIONS, REVIEW_CONTEXT, REVIEW_TASK_ID, REVIEW_TASK_NAME,
  VI_DEMO_DEFINITIONS, VI_REVIEW_CONTEXT, type DemoDefinition,
} from './processDemo';
import { DIRECTORY, QUEUE_NOW, QUEUE_SEED } from './taskQueueDemo';

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
  assignee: string;
  businessKey: string;
  dueAt: string | null;
  createdAt: string;
  fourEyes: boolean;
  startedBy: string;
  status: 'OPEN' | 'CLAIMED' | 'COMPLETED' | 'CANCELLED';
  lockVersion: number;
  claimedBy: string | null;
  claimedAt: string | null;
  completedBy: string | null;
  completedAt: string | null;
  review: Record<string, unknown> | undefined;
}

/** A hand-over the engine accepted, as its task history records it (PD-110). */
export interface RecordedHandOver {
  taskId: string;
  command: 'reassign' | 'delegate';
  actor: string;
  from: string | null;
  to: string;
  reason: string;
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
  /** The tenant's language of the demo data: names, reasons and answer words. */
  locale?: 'en' | 'vi';
  /** The loan origination task queue beside the review tasks; on by default. */
  queue?: boolean;
}

export function createFakeProcessEngine(seed: ProcessSeed = {}) {
  const locale = seed.locale ?? 'en';
  const definitions = seed.definitions ?? (locale === 'vi' ? VI_DEMO_DEFINITIONS : DEMO_DEFINITIONS);
  const startedBy = seed.startedBy ?? 'lan';
  const review = { ...(locale === 'vi' ? VI_REVIEW_CONTEXT : REVIEW_CONTEXT), ...(seed.reportedBy !== undefined ? { reportedBy: seed.reportedBy } : {}) };
  const recommendations: readonly string[] = RECOMMENDATIONS[locale];
  const reviewTask = (id: string, name: string, nodeId: string, extra: Partial<StoredTask>): StoredTask => ({
    id, name, nodeId, assignee: 'role:COMPLIANCE_REVIEWER', businessKey: 'APP-1042', dueAt: null, createdAt: '2026-11-20T03:02:00Z', fourEyes: true, startedBy,
    status: 'OPEN', lockVersion: 0, claimedBy: null, claimedAt: null, completedBy: null, completedAt: null, review, ...extra,
  });
  const tasks = new Map<string, StoredTask>([
    [REVIEW_TASK_ID, reviewTask(REVIEW_TASK_ID, REVIEW_TASK_NAME[locale], 'assess-documents', {})],
    [MASKED_TASK_ID, reviewTask(MASKED_TASK_ID, REVIEW_TASK_NAME[locale], 'assess-documents', { status: 'CLAIMED', lockVersion: 1, claimedBy: 'hoa', claimedAt: NOW,
      review: { ...review, output: { ...review.output, summary: '***' }, outputFields: 4 } })],
    [FORM_TASK_ID, reviewTask(FORM_TASK_ID, FORM_TASK_NAME[locale], 'capture', { review: undefined, fourEyes: false })],
    ...(seed.queue === false ? [] : QUEUE_SEED.map((item): [string, StoredTask] => [item.id, {
      id: item.id, name: item.name[locale], nodeId: item.nodeId, assignee: item.assignee, businessKey: item.businessKey, dueAt: item.dueAt, createdAt: item.createdAt,
      fourEyes: item.fourEyes, startedBy: item.startedBy, status: item.status, lockVersion: item.status === 'CLAIMED' ? 1 : 0, claimedBy: item.claimedBy,
      claimedAt: item.claimedBy !== null ? item.createdAt : null, completedBy: null, completedAt: null, review: undefined,
    }])),
  ]);
  const handOvers: RecordedHandOver[] = [];
  const replies = new Map<string, ProcessAnswer>();
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

  /** Four eyes: the starter of the case, and on a review the reporter of the refused answer, may not work the task. */
  const fourEyesRefuses = (task: StoredTask, user: string) =>
    task.fourEyes && (user === task.startedBy || (task.review !== undefined && user === task.review['reportedBy']));
  /** Whether the user is the task's assignee, holds its role or belongs to its group, as the host's directory says. */
  const mayWork = (task: StoredTask, user: string) => {
    const [kind, name] = task.assignee.split(/:(.*)/s);
    const entry = DIRECTORY[user] ?? { roles: [], groups: [] };
    if (kind === 'user') return name === user;
    if (kind === 'role') return entry.roles.includes(name ?? '');
    if (kind === 'group') return entry.groups.includes(name ?? '');
    return false;
  };

  const taskBody = (task: StoredTask, request: ProcessRequest) => {
    const allowed: string[] = [];
    if (request.permissions.includes('processes:task:work') && !fourEyesRefuses(task, request.user)) {
      if (task.status === 'OPEN' && mayWork(task, request.user)) allowed.push('claim');
      if (task.status === 'CLAIMED' && task.claimedBy === request.user) allowed.push('unclaim', 'complete');
    }
    if (request.permissions.includes('processes:task:manage')) {
      if (task.status === 'OPEN') allowed.push('reassign');
      if (task.status === 'CLAIMED') allowed.push('delegate');
    }
    return {
      id: task.id, instanceId: INSTANCE_ID, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: task.businessKey,
      nodeId: task.nodeId, name: task.name, assignee: task.assignee, fourEyes: task.fourEyes, status: task.status,
      lockVersion: task.lockVersion, claimedBy: task.claimedBy, claimedAt: task.claimedAt, createdAt: task.createdAt, dueAt: task.dueAt,
      completedBy: task.completedBy, completedAt: task.completedAt, allowedActions: allowed,
      ...(task.review !== undefined ? { review: task.review } : {}),
    };
  };

  const summaryBody = (task: StoredTask) => ({
    id: task.id, instanceId: INSTANCE_ID, definitionKey: 'loan-origination', definitionVersion: 3, businessKey: task.businessKey, nodeId: task.nodeId,
    name: task.name, assignee: task.assignee, status: task.status, lockVersion: task.lockVersion, claimedBy: task.claimedBy, createdAt: task.createdAt, dueAt: task.dueAt,
  });

  /** listTasks: by assignment and status, due time first and tasks without one last, then id. */
  const listTasks = (url: URL, request: ProcessRequest): ProcessAnswer => {
    if (!request.permissions.includes('processes:task:work') && !request.permissions.includes('processes:task:manage')) return problem(403, 'PERMISSION_DENIED');
    const assignment = (url.searchParams.get('assignment') ?? 'mine,candidate').split(',');
    if (assignment.includes('any') && !request.permissions.includes('processes:task:manage')) return problem(403, 'PERMISSION_DENIED');
    const statuses = (url.searchParams.get('status') ?? 'OPEN,CLAIMED').split(',');
    const breached = url.searchParams.get('slaBreached') === 'true';
    const limit = Number(url.searchParams.get('limit') ?? 50);
    const user = request.user;
    const matches = (task: StoredTask) => {
      if (task.status !== 'OPEN' && task.status !== 'CLAIMED') return false;
      if (!statuses.includes(task.status)) return false;
      if (breached && !(task.dueAt !== null && task.dueAt < QUEUE_NOW)) return false;
      const mine = task.assignee === `user:${user}` || task.claimedBy === user;
      const candidate = task.status === 'OPEN' && !task.assignee.startsWith('user:') && mayWork(task, user);
      return assignment.includes('any') || (assignment.includes('mine') && mine) || (assignment.includes('candidate') && candidate);
    };
    const all = [...tasks.values()].filter(matches).sort((a, b) => {
      if (a.dueAt !== b.dueAt) return a.dueAt === null ? 1 : b.dueAt === null ? -1 : a.dueAt.localeCompare(b.dueAt);
      return a.id.localeCompare(b.id);
    });
    const data = all.slice(0, limit).map(summaryBody);
    return json(200, { data, nextCursor: all.length > limit ? 'eyJhZnRlciI6Im5leHQifQ' : null, hasNext: all.length > limit });
  };

  /** reassign and delegate (PD-110): manage permission, a user id and a reason, and the target must be allowed to work the task. */
  const handOver = (task: StoredTask, command: 'reassign' | 'delegate', request: ProcessRequest): ProcessAnswer => {
    if (!request.permissions.includes('processes:task:manage')) return problem(403, 'PERMISSION_DENIED');
    let body: { assignee?: unknown; reason?: unknown };
    try {
      body = JSON.parse(request.body || '{}') as typeof body;
    } catch {
      return problem(400, 'INPUT_INVALID');
    }
    const assignee = typeof body.assignee === 'string' ? body.assignee : '';
    const reason = typeof body.reason === 'string' ? body.reason : '';
    if (assignee.length < 1 || assignee.length > 200 || reason.length < 1 || reason.length > 500) return problem(400, 'INPUT_INVALID');
    if (task.status !== (command === 'reassign' ? 'OPEN' : 'CLAIMED')) return problem(409, 'INVALID_STATE');
    const directUser = task.assignee.startsWith('user:');
    if (fourEyesRefuses(task, assignee) || task.claimedBy === assignee || (!directUser && !mayWork(task, assignee))) {
      return problem(409, 'ASSIGNEE_NOT_ALLOWED', { detail: 'The named user may not work this task' });
    }
    handOvers.push({ taskId: task.id, command, actor: request.user, from: task.claimedBy, to: assignee, reason });
    Object.assign(task, { status: 'CLAIMED', claimedBy: assignee, claimedAt: QUEUE_NOW, lockVersion: task.lockVersion + 1 });
    return json(200, taskBody(task, request), { ETag: `"${String(task.lockVersion)}"` });
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
    if (request.method === 'GET' && path === `${BASE}/tasks`) return listTasks(url, request);
    const taskMatch = /^\/api\/bpm\/processes\/v1\/tasks\/([^/:]+)(?::(claim|unclaim|complete|reassign|delegate))?$/.exec(path);
    if (taskMatch) {
      const task = tasks.get(taskMatch[1] ?? '');
      const command = taskMatch[2];
      const handingOver = command === 'reassign' || command === 'delegate';
      if (!handingOver && !request.permissions.includes('processes:task:work')) return problem(403, 'PERMISSION_DENIED');
      if (!task) return problem(404, 'TASK_NOT_FOUND');
      if (request.method === 'GET' && command === undefined) return json(200, taskBody(task, request), { ETag: `"${String(task.lockVersion)}"` });
      if (request.method !== 'POST' || command === undefined) return problem(400, 'INPUT_INVALID');
      const ifMatch = header('If-Match');
      const idempotencyKey = header('Idempotency-Key');
      if (ifMatch === undefined || idempotencyKey === undefined) return problem(428, 'PRECONDITION_REQUIRED');
      const replayKey = `${request.user}:${idempotencyKey}`;
      const replay = replies.get(replayKey);
      if (replay !== undefined) return replay;
      const answer = (() => {
        if (ifMatch !== `"${String(task.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT');
        if (handingOver) return handOver(task, command, request);
        if (fourEyesRefuses(task, request.user)) return problem(403, 'SELF_APPROVAL_FORBIDDEN');
        return undefined;
      })();
      if (answer !== undefined) {
        if (answer.status === 200) replies.set(replayKey, answer);
        return answer;
      }
      if (command === 'claim') {
        if (task.status !== 'OPEN') return problem(409, 'INVALID_STATE');
        if (!mayWork(task, request.user)) return problem(403, 'TASK_NOT_ASSIGNED');
        Object.assign(task, { status: 'CLAIMED', claimedBy: request.user, claimedAt: NOW, lockVersion: task.lockVersion + 1 });
        const claimed = json(200, taskBody(task, request), { ETag: `"${String(task.lockVersion)}"` });
        replies.set(replayKey, claimed);
        return claimed;
      }
      if (command === 'unclaim') {
        if (task.status !== 'CLAIMED') return problem(409, 'INVALID_STATE');
        if (task.claimedBy !== request.user) return problem(403, 'TASK_NOT_ASSIGNED');
        Object.assign(task, { status: 'OPEN', claimedBy: null, claimedAt: null, lockVersion: task.lockVersion + 1 });
        const released = json(200, taskBody(task, request), { ETag: `"${String(task.lockVersion)}"` });
        replies.set(replayKey, released);
        return released;
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

  /** A colleague claims the task first, as another browser would (CKP-046). */
  const claimAs = (taskId: string, user: string) => {
    const task = tasks.get(taskId);
    if (task?.status === 'OPEN') Object.assign(task, { status: 'CLAIMED', claimedBy: user, claimedAt: QUEUE_NOW, lockVersion: task.lockVersion + 1 });
  };

  return { handle, overrides, requests, handOvers, claimAs };
}
