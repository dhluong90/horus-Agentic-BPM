import { DEMO_NAMES } from './displayNames';
import {
  DEMO_DEFINITIONS, FORM_TASK_ID, FORM_TASK_NAME, INSTANCE_ID, kycDiff, MASKED_TASK_ID, RECOMMENDATIONS, REVIEW_CONTEXT, REVIEW_TASK_ID, REVIEW_TASK_NAME,
  localiseDemo, VI_DEMO_DEFINITIONS, VI_REVIEW_CONTEXT, type DemoDefinition,
} from './processDemo';
import { demoCases } from './caseDemo';
import { DIRECTORY, QUEUE_NOW, QUEUE_SEED, QUEUE_FORMS, type QueueSeedTask } from './taskQueueDemo';
import { fakeHash, MIGRATION_DEFINITIONS } from './migrationDemo';
import { createMigrationApi, type MigrationSeed } from './migrationEngine';
import { demoIncidents, type StoredIncident } from './incidentDemo';

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

/** A task completed through its declared form, as the engine received it (CKP-021). */
export interface RecordedCompletion {
  taskId: string;
  actor: string;
  result: Record<string, unknown>;
  ifMatch: string | undefined;
  idempotencyKey: string | undefined;
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
  action: 'approve' | 'reject' | 'withdraw';
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
  /** Stored layouts (the diagram of an imported BPMN file) by `<key>:<version>`; a version not listed has none (204). */
  layouts?: Readonly<Record<string, unknown>>;
  startedBy?: string;
  reportedBy?: string;
  /** The tenant's language of the demo data: names, reasons and answer words. */
  locale?: 'en' | 'vi';
  /** No case at all, for the empty states. */
  noCases?: boolean;
  /** How the activities read answers: as the engine does, as an older engine without it (404), or failing (500). */
  activities?: 'ok' | 'missing' | 'failing';
  /** The loan origination task queue beside the review tasks; on by default. */
  queue?: boolean;
  /** More queue tasks after the demo ones, for a demo user with their own work (Home). */
  extraTasks?: readonly QueueSeedTask[];
  /** The onboarding migration demo: version 3 in effect, 52 cases on version 2, and the plan in the given state. */
  migration?: MigrationSeed;
  /** The demo incidents (CKP-008); on by default. */
  incidents?: boolean;
  /** The host's `incidents.resolve-approval` (PD-126): `four-eyes` by default, `single` on the dev profile only. */
  resolveApproval?: 'four-eyes' | 'single';
  /** The pending onboarding version lists agent step fields with no classification (PD-146), as the engine does. */
  unclassified?: boolean;
  /** A pending change of the `vn-banking-hours` calendar, proposed by lan (#473): in the approvals list and under Calendar changes. */
  calendar?: boolean;
}

/** A command on an incident, as the engine received it. */
export interface RecordedIncidentCommand {
  incidentId: string;
  command: 'retry' | 'resolve' | 'approve-resolution' | 'reject-resolution';
  actor: string;
  body: Record<string, unknown>;
  ifMatch: string | undefined;
  idempotencyKey: string | undefined;
}

/** What the engine lists for the pending onboarding version when fields are left unclassified: names only (PRE-205). */
const UNCLASSIFIED_DEMO = [
  { code: 'UNCLASSIFIED_FIELD', step: 'verify-docs', field: 'documentType', direction: 'INPUT', message: 'Input documentType of step verify-docs is not classified: it declares neither mask true nor mask false. This does not block approval.' },
  { code: 'UNCLASSIFIED_FIELD', step: 'verify-docs', field: 'summary', direction: 'OUTPUT', message: 'Output summary of step verify-docs is not classified: it declares neither mask true nor mask false. This does not block approval.' },
];

/**
 * The engine's deadline fields on the engine clock QUEUE_NOW (#472, PD-111): `slaState` and `overdueBy`, an ISO 8601
 * duration in whole minutes, null unless the task is breached.
 */
function slaOn(dueAt: string | null): { slaState: 'NONE' | 'ON_TIME' | 'BREACHED'; overdueBy: string | null } {
  if (dueAt === null) return { slaState: 'NONE', overdueBy: null };
  const late = Math.floor((Date.parse(QUEUE_NOW) - Date.parse(dueAt)) / 60_000);
  if (late <= 0) return { slaState: 'ON_TIME', overdueBy: null };
  return { slaState: 'BREACHED', overdueBy: `PT${String(Math.floor(late / 60))}H${String(late % 60)}M` };
}

export function createFakeProcessEngine(seed: ProcessSeed = {}) {
  const locale = seed.locale ?? 'en';
  // Stored versions, changed in place by approve and reject.
  const definitions: (DemoDefinition & { lockVersion: number; approvedBy: string | null; approvedAt: string | null; decisionComment: string | null })[] =
    (seed.definitions ?? (seed.migration !== undefined ? localiseDemo(MIGRATION_DEFINITIONS, locale) : locale === 'vi' ? VI_DEMO_DEFINITIONS : DEMO_DEFINITIONS)).map((d) => ({ ...d, lockVersion: 2, approvedBy: d.status === 'ACTIVE' ? 'minh' : null, approvedAt: null, decisionComment: null }));
  const decisions: RecordedDecision[] = [];
  const migrations = createMigrationApi(() => definitions, seed.migration === undefined ? undefined : { ...seed.migration, locale });
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
    ...(seed.queue === false ? [] : [...QUEUE_SEED, ...(seed.extraTasks ?? [])].map((item): [string, StoredTask] => [item.id, {
      id: item.id, name: item.name[locale], nodeId: item.nodeId, assignee: item.assignee, businessKey: item.businessKey, dueAt: item.dueAt, createdAt: item.createdAt,
      fourEyes: item.fourEyes, startedBy: item.startedBy, status: item.status, lockVersion: item.status === 'CLAIMED' ? 1 : 0, claimedBy: item.claimedBy,
      claimedAt: item.claimedBy !== null ? item.createdAt : null, completedBy: null, completedAt: null, review: undefined,
    }])),
  ]);
  const handOvers: RecordedHandOver[] = [];
  const replies = new Map<string, ProcessAnswer>();
  const overrides: RecordedOverride[] = [];
  const completions: RecordedCompletion[] = [];
  const submittedData = new Map<string, Record<string, unknown>>();
  /** What the engine's history keeps of a completed form task (#663): the stored submission and the paths it did not store. */
  const recordedSubmissions = new Map<string, { instanceId: string; nodeId: string; actor: string; submission: Record<string, unknown>; unrecordedFields: string[] }>();
  const requests: { method: string; path: string; headers: Readonly<Record<string, string>>; body: string }[] = [];
  const incidents: StoredIncident[] = seed.incidents === false ? [] : demoIncidents(locale);
  const incidentCommands: RecordedIncidentCommand[] = [];
  // A retried step is accepted after the list has been read once more: the row says Retrying, then leaves.
  const settling = new Map<string, number>();

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
    ...slaOn(task.dueAt),
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
    // The refusals say which rule applied (PRE-206): reason, and for a missing role or group, what the target lacks.
    const refuse = (reason: string, required?: string) => problem(409, 'ASSIGNEE_NOT_ALLOWED', { detail: 'The named user may not work this task', reason, ...(required !== undefined ? { required } : {}) });
    if (task.claimedBy === assignee) return refuse('ALREADY_ASSIGNED');
    if (fourEyesRefuses(task, assignee)) return refuse('FOUR_EYES_PARTICIPANT');
    if (!directUser && !mayWork(task, assignee)) return refuse(task.assignee.startsWith('group:') ? 'MISSING_GROUP' : 'MISSING_ROLE', task.assignee);
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

  /** The checks of a declared form the demo engine makes: required fields, the conditional rule, enums, integers and the mask. */
  const formViolations = (schema: Record<string, unknown>, result: Record<string, unknown>) => {
    const properties = (schema['properties'] ?? {}) as Record<string, Record<string, unknown>>;
    const required = new Set((schema['required'] ?? []) as string[]);
    const rule = schema['if'] as { properties?: Record<string, { const?: unknown }> } | undefined;
    if (rule?.properties !== undefined && Object.entries(rule.properties).every(([name, check]) => result[name] === check.const)) {
      for (const name of ((schema['then'] as { required?: string[] } | undefined)?.required ?? [])) required.add(name);
    }
    const out: { pointer: string; rule: string }[] = [];
    for (const [name, property] of Object.entries(properties)) {
      const value = result[name];
      if (value === undefined || value === null) {
        if (required.has(name)) out.push({ pointer: `/${name}`, rule: 'required' });
      } else if (property['mask'] === true && value === '***') out.push({ pointer: `/${name}`, rule: 'masked value' });
      else if (Array.isArray(property['enum']) && !property['enum'].includes(value)) out.push({ pointer: `/${name}`, rule: `must be one of ${property['enum'].join(', ')}` });
      else if (property['type'] === 'integer' && !Number.isInteger(value)) out.push({ pointer: `/${name}`, rule: 'expected integer' });
      else if (property['type'] === 'string' && typeof value === 'string' && typeof property['maxLength'] === 'number' && value.length > property['maxLength']) out.push({ pointer: `/${name}`, rule: `must be at most ${String(property['maxLength'])} characters` });
    }
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
      warnings: seed.unclassified === true && found.key === 'kyc-onboarding' && found.version === 3 ? UNCLASSIFIED_DEMO : [],
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
    if (!request.permissions.includes(command === '/diff' ? 'processes:read' : command === ':withdraw' ? 'processes:author' : 'processes:approve')) return problem(403, 'PERMISSION_DENIED');
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
    const action = command === ':approve' ? 'approve' : command === ':withdraw' ? 'withdraw' : 'reject';
    decisions.push({ action, key, version: number, by: request.user, body, ifMatch, idempotencyKey });
    if (found.status !== 'PENDING_APPROVAL') return problem(409, 'INVALID_STATE');
    if (action === 'withdraw') {
      // PRE-052: only the requester, and back to draft with no rejection recorded
      if (request.user !== (found.requestedBy ?? found.createdBy ?? 'lan')) return problem(403, 'PERMISSION_DENIED');
      if (ifMatch !== `"${String(found.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT');
      Object.assign(found, { status: 'DRAFT', decisionComment: null, lockVersion: found.lockVersion + 1, requestedBy: undefined, requestedAt: undefined });
      return json(200, versionBody(found), { ETag: `"${String(found.lockVersion)}"` });
    }
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
  /** The demo calendar change (#473): lan proposes the holiday 1 January 2028 for the banking hours calendar. */
  const calendarChange = seed.calendar !== true ? undefined : {
    id: '01KB2R6Q3C0000000000000CAL', calendarKey: 'vn-banking-hours', status: 'PENDING', approval: 'FOUR_EYES', baseVersion: 3,
    previous: { zone: 'Asia/Ho_Chi_Minh', week: { MONDAY: ['08:00-17:00'], FRIDAY: ['08:00-17:00'] }, holidays: {} },
    proposed: { zone: 'Asia/Ho_Chi_Minh', week: { MONDAY: ['08:00-17:00'], FRIDAY: ['08:00-17:00'] }, holidays: { '2028-01-01': 'New Year' } },
    reason: 'New Year 2028 is a public holiday', proposedBy: 'lan', proposedAt: '2026-11-21T02:00:00Z', decidedBy: null as string | null, decidedAt: null as string | null, comment: null as string | null,
  };
  const calendarHash = () => (calendarChange === undefined ? '' : fakeHash(JSON.stringify([calendarChange.calendarKey, calendarChange.baseVersion, calendarChange.proposed])));
  const calendarBody = () => ({ ...calendarChange, contentHash: calendarHash() });
  /** Approvals sent to the calendar-change endpoints, as the engine received them. */
  const calendarDecisions: { id: string; command: 'approve' | 'reject'; actor: string; body: Record<string, unknown> }[] = [];
  /** The calendars in force (PRE-181), and the changes proposed from the editor (PRE-114, PRE-182). */
  interface WorkingCalendar { zone: string; week: Record<string, string[]>; holidays: Record<string, string> }
  const calendars = new Map<string, { version: number; calendar: WorkingCalendar; updatedBy: string; approvedBy: string; updatedAt: string }>([
    ['vn-banking-hours', {
      version: 3, updatedBy: 'lan', approvedBy: 'minh', updatedAt: '2026-10-02T03:00:00Z',
      calendar: { zone: 'Asia/Ho_Chi_Minh', week: { MONDAY: ['08:00-17:00'], TUESDAY: ['08:00-17:00'], WEDNESDAY: ['08:00-17:00'], THURSDAY: ['08:00-17:00'], FRIDAY: ['08:00-17:00'], SATURDAY: ['08:00-12:00'] }, holidays: { '2026-09-02': 'National Day', '2027-02-08': 'Tet' } },
    }],
  ]);
  interface Proposal { id: string; calendarKey: string; status: string; approval: string; baseVersion: number; previous: WorkingCalendar | null; proposed: WorkingCalendar; reason: string; proposedBy: string; proposedAt: string; decidedBy: string | null; decidedAt: string | null; comment: string | null }
  const proposals: Proposal[] = [];
  /** Proposals sent from the calendar editor, as the engine received them. */
  const calendarProposals: { key: string; ifMatch: string | undefined; idempotencyKey: string | undefined; actor: string; body: Record<string, unknown> }[] = [];
  const proposalBody = (item: Proposal) => ({ ...item, contentHash: fakeHash(JSON.stringify([item.calendarKey, item.baseVersion, item.proposed])) });
  function calendarEditorRoute(request: ProcessRequest, path: string, url: URL): ProcessAnswer | undefined {
    const read = request.permissions.includes('processes:read');
    if (request.method === 'GET' && path === `${BASE}/calendars`) {
      if (!read) return problem(403, 'PERMISSION_DENIED');
      const data = [...calendars.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([key, c]) => ({ key, version: c.version, zone: c.calendar.zone, updatedBy: c.updatedBy, approvedBy: c.approvedBy, updatedAt: c.updatedAt }));
      return json(200, { data, nextCursor: null, hasNext: false });
    }
    const one = /^\/api\/bpm\/processes\/v1\/calendars\/([^/:]+)(\/changes)?$/.exec(path);
    if (one !== null) {
      const key = one[1] ?? '';
      if (one[2] === undefined && request.method === 'GET') {
        if (!read) return problem(403, 'PERMISSION_DENIED');
        const found = calendars.get(key);
        return found === undefined ? problem(404, 'CALENDAR_NOT_FOUND') : json(200, { key, ...found }, { ETag: `"${String(found.version)}"` });
      }
      if (one[2] !== undefined && request.method === 'POST') {
        if (!request.permissions.includes('processes:calendar:manage')) return problem(403, 'PERMISSION_DENIED');
        const ifMatch = request.headers['If-Match'] ?? request.headers['if-match'];
        const idempotencyKey = request.headers['Idempotency-Key'] ?? request.headers['idempotency-key'];
        if (ifMatch === undefined || idempotencyKey === undefined) return problem(428, 'PRECONDITION_REQUIRED');
        const body = JSON.parse(request.body === '' ? '{}' : request.body) as { calendar?: WorkingCalendar; reason?: string };
        calendarProposals.push({ key, ifMatch, idempotencyKey, actor: request.user, body });
        if (!/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/.test(key) || body.calendar === undefined || (body.reason ?? '').trim() === '') return problem(400, 'INPUT_INVALID');
        const current = calendars.get(key);
        if (ifMatch !== `"${String(current?.version ?? 0)}"`) return problem(409, 'VERSION_CONFLICT');
        const item: Proposal = {
          id: `01KB2R6Q3C0000000000PROP${String(proposals.length + 1).padStart(2, '0')}`, calendarKey: key, status: 'PENDING', approval: 'FOUR_EYES', baseVersion: current?.version ?? 0,
          previous: current?.calendar ?? null, proposed: body.calendar, reason: body.reason ?? '', proposedBy: request.user, proposedAt: '2026-11-21T04:00:00Z', decidedBy: null, decidedAt: null, comment: null,
        };
        proposals.push(item);
        return json(201, proposalBody(item), { Location: `${BASE}/calendar-changes/${item.id}` });
      }
      return undefined;
    }
    const changes = /^\/api\/bpm\/processes\/v1\/calendar-changes\/([^/:]+)(:approve|:reject)$/.exec(path);
    if (changes !== null && request.method === 'POST') {
      const item = proposals.find((candidate) => candidate.id === changes[1]);
      if (item === undefined) return undefined;
      if (!request.permissions.includes('processes:calendar:manage')) return problem(403, 'PERMISSION_DENIED');
      if (item.status !== 'PENDING') return problem(409, 'INVALID_STATE');
      const body = JSON.parse(request.body === '' ? '{}' : request.body) as Record<string, unknown>;
      const approve = changes[2] === ':approve';
      if (approve && request.user === item.proposedBy) return problem(403, 'SELF_APPROVAL_FORBIDDEN');
      if (approve && body['expectedChangeHash'] !== proposalBody(item).contentHash) return problem(body['expectedChangeHash'] === undefined ? 400 : 409, body['expectedChangeHash'] === undefined ? 'CONTENT_HASH_REQUIRED' : 'CONTENT_CHANGED');
      Object.assign(item, { status: approve ? 'APPROVED' : 'REJECTED', decidedBy: request.user, decidedAt: '2026-11-21T05:00:00Z', comment: typeof body['comment'] === 'string' ? body['comment'] : null });
      if (approve) calendars.set(item.calendarKey, { version: item.baseVersion + 1, calendar: item.proposed, updatedBy: item.proposedBy, approvedBy: request.user, updatedAt: '2026-11-21T05:00:00Z' });
      return json(200, proposalBody(item));
    }
    const byId = /^\/api\/bpm\/processes\/v1\/calendar-changes\/([^/:]+)$/.exec(path);
    if (byId !== null && request.method === 'GET') {
      const item = proposals.find((candidate) => candidate.id === byId[1]);
      if (item === undefined) return undefined;
      return read ? json(200, proposalBody(item)) : problem(403, 'PERMISSION_DENIED');
    }
    if (request.method === 'GET' && path === `${BASE}/calendar-changes`) {
      if (!read) return problem(403, 'PERMISSION_DENIED');
      const status = url.searchParams.get('status') ?? 'PENDING';
      if (!['PENDING', 'APPROVED', 'REJECTED'].includes(status)) return problem(400, 'QUERY_INVALID');
      const demo = calendarChange !== undefined && calendarChange.status === status ? [calendarBody()] : [];
      const data = [...proposals.filter((item) => item.status === status).map(proposalBody).reverse(), ...demo];
      return json(200, { data, nextCursor: null, hasNext: false });
    }
    return undefined;
  }
  function calendarRoute(request: ProcessRequest, path: string, url: URL): ProcessAnswer | undefined {
    const editor = calendarEditorRoute(request, path, url);
    if (editor !== undefined) return editor;
    const m = /^\/api\/bpm\/processes\/v1\/calendar-changes(?:\/([^/:]+)(:approve|:reject)?)?$/.exec(path);
    if (m === null) return undefined;
    if (calendarChange === undefined) return problem(404, 'CALENDAR_NOT_FOUND');
    if (m[1] === undefined) {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      return json(200, { data: calendarChange.status === 'PENDING' ? [calendarBody()] : [], nextCursor: null, hasNext: false });
    }
    if (m[1] !== calendarChange.id) return problem(404, 'CALENDAR_NOT_FOUND');
    if (m[2] === undefined) return request.permissions.includes('processes:read') ? json(200, calendarBody()) : problem(403, 'PERMISSION_DENIED');
    if (!request.permissions.includes('processes:calendar:manage')) return problem(403, 'PERMISSION_DENIED');
    if (calendarChange.status !== 'PENDING') return problem(409, 'INVALID_STATE');
    const body = request.body === '' ? {} : (JSON.parse(request.body) as Record<string, unknown>);
    const command = m[2] === ':approve' ? 'approve' : 'reject';
    calendarDecisions.push({ id: calendarChange.id, command, actor: request.user, body });
    if (command === 'approve') {
      if (request.user === calendarChange.proposedBy) return problem(403, 'SELF_APPROVAL_FORBIDDEN');
      if (body['expectedChangeHash'] === undefined) return problem(400, 'CONTENT_HASH_REQUIRED');
      if (body['expectedChangeHash'] !== calendarHash()) return problem(409, 'CONTENT_CHANGED');
    }
    Object.assign(calendarChange, { status: command === 'approve' ? 'APPROVED' : 'REJECTED', decidedBy: request.user, decidedAt: '2026-11-21T03:00:00Z', comment: typeof body['comment'] === 'string' ? body['comment'] : null });
    return json(200, calendarBody());
  }

  function pendingApprovals(url: URL, request: ProcessRequest): ProcessAnswer {
    if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
    const limitText = url.searchParams.get('limit');
    const limit = limitText === null ? 50 : Number(limitText);
    if (!Number.isInteger(limit) || limit < 1 || limit > 200) return problem(400, 'QUERY_INVALID');
    const approver = request.permissions.includes('processes:approve');
    const data: (Record<string, unknown> & { requestedAt: string; key: string })[] = definitions
      .filter((d) => d.status === 'PENDING_APPROVAL')
      .map((d) => {
        const requestedBy = d.requestedBy ?? d.createdBy ?? 'lan';
        const self = request.user === requestedBy || request.user === (d.createdBy ?? 'lan');
        const denial = !approver ? 'PERMISSION_REQUIRED' : self ? 'SELF_APPROVAL_FORBIDDEN' : undefined;
        const mayWithdraw = request.user === requestedBy && request.permissions.includes('processes:author');
        return {
          kind: 'DEFINITION', id: null, sourceVersion: null, reason: null, contentHash: d.definitionHash ?? DEFAULT_HASH,
          key: d.key, name: d.name, version: d.version, definitionHash: d.definitionHash ?? DEFAULT_HASH, lockVersion: d.lockVersion,
          requestedBy, requestedByName: DEMO_NAMES[locale].users[requestedBy] ?? requestedBy, requestedAt: d.requestedAt ?? d.lastChangedAt,
          allowedActions: [...(denial === undefined ? ['approve', 'reject'] : []), ...(mayWithdraw ? ['withdraw'] : [])],
          deniedActions: denial === undefined ? {} : { approve: denial, reject: denial },
        };
      });
    // AUD-07: without calendars:manage a caller sees no pending calendar change
    const manager = request.permissions.includes('processes:calendar:manage');
    if (calendarChange?.status === 'PENDING' && manager) {
      const self = request.user === calendarChange.proposedBy;
      data.push({
        kind: 'CALENDAR_CHANGE', id: calendarChange.id, sourceVersion: null, reason: calendarChange.reason, contentHash: calendarHash(),
        key: calendarChange.calendarKey, name: null, version: calendarChange.baseVersion + 1, definitionHash: null, lockVersion: calendarChange.baseVersion,
        requestedBy: calendarChange.proposedBy, requestedByName: DEMO_NAMES[locale].users[calendarChange.proposedBy] ?? calendarChange.proposedBy, requestedAt: calendarChange.proposedAt,
        allowedActions: [...(self ? [] : ['approve']), 'reject'],
        deniedActions: self ? { approve: 'SELF_APPROVAL_FORBIDDEN' } : {},
      });
    }
    data.sort((a, b) => a.requestedAt.localeCompare(b.requestedAt) || a.key.localeCompare(b.key));
    return json(200, { data: data.slice(0, limit), nextCursor: null, hasNext: false });
  }

  /** allowedActions of an incident for this caller (PRE-077, PRE-078): empty without processes:incident:manage. */
  const incidentActions = (item: StoredIncident, request: ProcessRequest): string[] => {
    if (!request.permissions.includes('processes:incident:manage')) return [];
    if (item.status === 'OPEN' && !item.retrying) return [...(item.errorCode === 'COMPENSATION_MANUAL_REQUIRED' ? [] : ['retry']), 'resolve'];
    if (item.status === 'RESOLUTION_PENDING' && item.pendingResolution?.proposedBy !== request.user) return ['approve-resolution', 'reject-resolution'];
    return [];
  };
  const incidentBody = (item: StoredIncident, request: ProcessRequest) => {
    const body: Record<string, unknown> = { ...item, allowedActions: incidentActions(item, request) };
    delete body['requires'];
    return body;
  };

  /** listIncidents: one status, newest first; a retried step settles on the read after the one that showed it retrying. */
  const listIncidents = (url: URL, request: ProcessRequest): ProcessAnswer => {
    if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
    const status = url.searchParams.get('status') ?? 'OPEN';
    if (!['OPEN', 'RESOLUTION_PENDING', 'RESOLVED'].includes(status)) return problem(400, 'QUERY_INVALID');
    if (status === 'OPEN') {
      for (const [id, reads] of [...settling]) {
        const item = incidents.find((candidate) => candidate.id === id);
        if (item === undefined || reads + 1 < 2) { settling.set(id, reads + 1); continue; }
        Object.assign(item, { status: 'RESOLVED', retrying: false, resolution: 'RETRY_SUCCEEDED', resolvedAt: QUEUE_NOW, lockVersion: item.lockVersion + 1 });
        settling.delete(id);
      }
    }
    const data = incidents.filter((item) => item.status === status).sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id));
    return json(200, { data: data.map((item) => incidentBody(item, request)), nextCursor: null, hasNext: false });
  };

  const incidentCommand = (item: StoredIncident, command: RecordedIncidentCommand['command'], request: ProcessRequest, header: (name: string) => string | undefined): ProcessAnswer => {
    if (!request.permissions.includes('processes:incident:manage')) return problem(403, 'PERMISSION_DENIED');
    const ifMatch = header('If-Match');
    const idempotencyKey = header('Idempotency-Key');
    if (ifMatch === undefined || idempotencyKey === undefined) return problem(428, 'PRECONDITION_REQUIRED');
    const replayKey = `${request.user}:${idempotencyKey}`;
    const replay = replies.get(replayKey);
    if (replay !== undefined) return replay;
    let body: Record<string, unknown>;
    try {
      body = JSON.parse(request.body || '{}') as Record<string, unknown>;
    } catch {
      return problem(400, 'INPUT_INVALID');
    }
    incidentCommands.push({ incidentId: item.id, command, actor: request.user, body, ifMatch, idempotencyKey });
    if (ifMatch !== `"${String(item.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT');
    const reason = typeof body['reason'] === 'string' ? body['reason'].trim() : '';
    const answer = (status: number) => {
      const reply = json(status, incidentBody(item, request), { ETag: `"${String(item.lockVersion)}"` });
      replies.set(replayKey, reply);
      return reply;
    };
    switch (command) {
      case 'retry':
        if (reason === '') return problem(400, 'INPUT_INVALID');
        if (item.status !== 'OPEN' || item.retrying || item.errorCode === 'COMPENSATION_MANUAL_REQUIRED') return problem(409, 'INVALID_STATE');
        Object.assign(item, { retrying: true, retryCount: item.retryCount + 1, lockVersion: item.lockVersion + 1 });
        settling.set(item.id, 0);
        return answer(202);
      case 'resolve': {
        if (reason === '' || reason.length > 2000) return problem(400, 'INPUT_INVALID');
        if (item.status !== 'OPEN' || item.retrying) return problem(409, 'INVALID_STATE');
        const output = body['output'];
        if (typeof output !== 'object' || output === null || Array.isArray(output)) return problem(400, 'INPUT_INVALID');
        const missing = item.requires.filter((name) => !(name in output));
        if (missing.length > 0) return problem(422, 'OUTPUT_INVALID', { paths: missing.map((name) => `/${name}`) });
        if ((seed.resolveApproval ?? 'four-eyes') === 'single') {
          Object.assign(item, { status: 'RESOLVED', resolution: 'COMPLETED_MANUALLY', resolvedBy: request.user, resolvedAt: QUEUE_NOW, lockVersion: item.lockVersion + 1 });
        } else {
          Object.assign(item, {
            status: 'RESOLUTION_PENDING', lockVersion: item.lockVersion + 1,
            pendingResolution: { proposedBy: request.user, proposedAt: QUEUE_NOW, reason, output: output as Record<string, unknown>, approvalMode: 'four-eyes' },
          });
        }
        return answer(200);
      }
      case 'approve-resolution':
      case 'reject-resolution':
        if (item.status !== 'RESOLUTION_PENDING' || item.pendingResolution === null) return problem(409, 'INVALID_STATE');
        if (item.pendingResolution.proposedBy === request.user) return problem(403, 'SELF_APPROVAL_FORBIDDEN');
        if (command === 'reject-resolution') {
          if (reason === '') return problem(400, 'INPUT_INVALID');
          Object.assign(item, { status: 'OPEN', pendingResolution: null, lockVersion: item.lockVersion + 1 });
        } else {
          Object.assign(item, { status: 'RESOLVED', resolution: 'COMPLETED_MANUALLY', resolvedBy: request.user, resolvedAt: QUEUE_NOW, lockVersion: item.lockVersion + 1 });
        }
        return answer(200);
    }
  };

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
    const calendar = calendarRoute(request, path, url);
    if (calendar !== undefined) return calendar;
    if (seed.migration !== undefined) {
      const migration = migrations.handle(request, path, url);
      if (migration !== undefined) return migration;
    }
    const lifecycle = /^\/api\/bpm\/processes\/v1\/definitions\/([^/]+)\/versions\/(\d+)(\/diff|:approve|:reject|:withdraw)$/.exec(path);
    if (lifecycle) return lifecycleCommand(request, lifecycle[1] ?? '', Number(lifecycle[2]), lifecycle[3] ?? '', header);
    const layoutRoute = /^\/api\/bpm\/processes\/v1\/definitions\/([^/]+)\/versions\/(\d+)\/layout$/.exec(path);
    if (layoutRoute && request.method === 'GET') {
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      const stored = seed.layouts?.[`${layoutRoute[1] ?? ''}:${layoutRoute[2] ?? ''}`];
      if (stored === undefined) return { status: 204, headers: { ETag: '"0"' }, body: '' };
      return json(200, { revision: 1, layoutHash: '0'.repeat(64), definitionHash: '0'.repeat(64), source: 'BPMN', createdBy: 'import', createdAt: '2026-11-19T09:00:00Z', layout: stored }, { ETag: '"1"' });
    }
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
      const touched = seed.migration !== undefined ? migrations.instance(id) : undefined;
      if (touched !== undefined && instanceMatch[2] === undefined) return json(200, touched, { ETag: '"3"' });
      const found = id === INSTANCE_ID ? { instance: instance(), activities: cases.find((item) => item.instance.id === id)?.activities } : cases.find((item) => item.instance.id === id);
      if (!found) return problem(404, 'INSTANCE_NOT_FOUND');
      if (instanceMatch[2] === undefined) return json(200, found.instance, { ETag: `"${String(found.instance['lockVersion'])}"` });
      if (seed.activities === 'missing') return problem(404, 'INPUT_INVALID');
      if (seed.activities === 'failing') return problem(500, 'INTERNAL');
      const added = [...recordedSubmissions.values()].filter((item) => item.instanceId === id).map((item) => ({
        id: `H-${item.nodeId}-done`, type: 'TASK_COMPLETED', nodeId: item.nodeId, actor: item.actor, occurredAt: '2026-11-20T03:20:00Z',
        submission: item.submission, ...(item.unrecordedFields.length > 0 ? { unrecordedFields: item.unrecordedFields } : {}),
      }));
      const base = found.activities ?? (added.length === 0 ? undefined : { instanceId: id, startedAt: NOW, endedAt: null, nodes: [], entries: [], hasMore: false });
      if (base === undefined) return problem(404, 'INSTANCE_NOT_FOUND');
      const activities = added.length === 0 ? base : { ...base, entries: [...base.entries, ...added] };
      return json(200, activities, { ETag: '"a1b2c3d4e5f60718293a4b5c6d7e8f90"' });
    }
    if (request.method === 'GET' && path === `${BASE}/incidents`) return listIncidents(url, request);
    const incidentMatch = /^\/api\/bpm\/processes\/v1\/incidents\/([^/:]+)(?::(retry|resolve|approve-resolution|reject-resolution))?$/.exec(path);
    if (incidentMatch) {
      const item = incidents.find((candidate) => candidate.id === incidentMatch[1]);
      if (!request.permissions.includes('processes:read')) return problem(403, 'PERMISSION_DENIED');
      if (!item) return problem(404, 'INCIDENT_NOT_FOUND');
      const command = incidentMatch[2] as RecordedIncidentCommand['command'] | undefined;
      if (request.method === 'GET' && command === undefined) return json(200, incidentBody(item, request), { ETag: `"${String(item.lockVersion)}"` });
      if (request.method !== 'POST' || command === undefined) return problem(400, 'INPUT_INVALID');
      return incidentCommand(item, command, request, header);
    }
    if (request.method === 'GET' && path === `${BASE}/tasks`) return listTasks(url, request);
    const formMatch = /^\/api\/bpm\/processes\/v1\/tasks\/([^/:]+)\/form$/.exec(path);
    if (formMatch && request.method === 'GET') {
      const task = tasks.get(formMatch[1] ?? '');
      if (!request.permissions.includes('processes:task:work')) return problem(403, 'PERMISSION_DENIED');
      if (!task) return problem(404, 'TASK_NOT_FOUND');
      const declared = QUEUE_FORMS[task.id];
      if (declared === undefined) return problem(404, 'FORM_NOT_DECLARED');
      const base = { schemaVersion: 1, taskId: task.id, lockVersion: task.lockVersion };
      // Once completed, the schema's variables hold what was submitted (a masked one as the placeholder), as the real engine reads them.
      const submitted = submittedData.get(task.id);
      const body = 'link' in declared ? { ...base, link: declared.link.replace('{taskId}', task.id) } : { ...base, form: submitted === undefined ? declared : { ...declared, data: { ...declared.data, ...submitted } } };
      return json(200, body, { ETag: `"${String(task.lockVersion)}"` });
    }
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
      const declared = QUEUE_FORMS[task.id];
      if (declared !== undefined && 'schema' in declared) {
        const errors = formViolations(declared.schema, result);
        if (errors.length > 0) return problem(422, 'FORM_VALIDATION_FAILED', { violations: errors.map((e) => `$.${e.pointer.slice(1)}: ${e.rule}`), errors });
        completions.push({ taskId: task.id, actor: request.user, result, ifMatch, idempotencyKey });
        const properties = (declared.schema['properties'] ?? {}) as Record<string, { mask?: boolean }>;
        // A masked field the operator left alone still holds its value on the case, so it is recorded as the marker.
        const untouched: [string, unknown][] = Object.keys(declared.data).filter((name) => properties[name]?.mask === true && !(name in result)).map((name) => [name, { masked: true }]);
        const sent: [string, unknown][] = Object.entries(result).filter(([name]) => name in properties).map(([name, value]) => [name, properties[name]?.mask === true ? { masked: true } : value]);
        recordedSubmissions.set(task.id, {
          instanceId: INSTANCE_ID, nodeId: task.nodeId, actor: request.user,
          submission: Object.fromEntries([...untouched, ...sent]),
          unrecordedFields: Object.keys(result).filter((name) => !(name in properties)),
        });
        submittedData.set(task.id, Object.fromEntries(Object.entries(result).map(([name, value]) => [name, properties[name]?.mask === true ? '***' : value])));
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

  /** A step fails again while its retry runs: the same incident stays open with one more retry (PRE-077). */
  const touchIncident = (id: string) => {
    const item = incidents.find((candidate) => candidate.id === id);
    if (item) item.lockVersion += 1;
  };

  /** Another person's change was approved meanwhile: the calendar is one version on. */
  const advanceCalendar = (key: string) => { const found = calendars.get(key); if (found !== undefined) found.version += 1; };
  return { handle, overrides, completions, requests, decisions, calendarDecisions, calendarProposals, advanceCalendar, handOvers, claimAs, migrations, incidentCommands, touchIncident };
}
