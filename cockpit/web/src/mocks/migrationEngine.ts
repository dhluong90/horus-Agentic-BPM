import { CASE_COUNT, CASE_KEYS, FAILED_CASES, fakeHash, IN_FLIGHT_CASES, migrationCaseId, MIGRATION_KEY, MIGRATION_PLAN_ID, WAITING } from './migrationDemo';

/*
 * The migration part of the mock Process Engine, in the shapes of process-engine/api/openapi.yaml: plans with explicit
 * step mappings, the dry run with its impact, the proposal, the second person's approval and the progress. It keeps
 * the rules the cockpit depends on: the plan is bound to the check (409 DRY_RUN_REQUIRED), the proposer is refused as
 * approver (403 SELF_APPROVAL_FORBIDDEN), If-Match names the proposal (409 VERSION_CONFLICT, 428 when missing) and
 * every command carries an Idempotency-Key. The run is moved by `advance`, so a test or a screenshot sees a known state.
 */

export interface MigrationRequest {
  method: string;
  headers: Readonly<Record<string, string>>;
  body: string;
  user: string;
  permissions: readonly string[];
}

export interface MigrationReply {
  status: number;
  headers: Record<string, string>;
  body: string;
}

export interface MigrationSeed {
  /** The plan already in the store when the engine starts. */
  state?: 'none' | 'planned' | 'unmapped' | 'proposed' | 'approved' | 'running' | 'completed' | undefined;
  /** `single` is the dev profile's mode: a proposal is approved by its proposer at once. */
  approval?: 'four-eyes' | 'single' | undefined;
  proposedBy?: string | undefined;
  /** The tenant's language, for the reasons written into the demo plan. */
  locale?: 'en' | 'vi' | undefined;
  /** What the engine's clock reads, in milliseconds. */
  clock?: (() => number) | undefined;
}

interface Node { id: string; type: string }
interface Definition { key: string; version: number; status: string; bpmn: string }

interface Stored {
  id: string;
  definitionKey: string;
  sourceVersion: number;
  targetVersion: number;
  mappings: { from: string; to: string }[];
  cancellations: { step: string; moveTo: string; reason: string }[];
  createdBy: string;
  createdAt: string;
  planHash: string;
  mode: 'four-eyes' | 'single';
  status: 'PLANNED' | 'PROPOSED' | 'REJECTED' | 'APPROVED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_FAILURES';
  lockVersion: number;
  proposedBy: string | null;
  proposedAt: string | null;
  reason: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  rejectedBy: string | null;
  rejectedAt: string | null;
  rejectionReason: string | null;
  migrated: number;
  startedAt: string | null;
  finishedAt: string | null;
}

/** What the engine received, for a test to read. */
export interface RecordedMigrationCall {
  command: 'create' | 'validate' | 'propose' | 'approve' | 'reject';
  by: string;
  ifMatch: string | undefined;
  idempotencyKey: string | undefined;
  body: Record<string, unknown>;
}

const BASE = '/api/bpm/processes/v1';
const ERROR_TYPE = 'https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#';

function nodesOf(bpmn: string): Node[] {
  const nodes: Node[] = [];
  for (const match of bpmn.matchAll(/<bpmn:(\w+) id="([^"]+)"([^>]*)>/g)) {
    const [, element = '', id = '', rest = ''] = match;
    if (element === 'sequenceFlow' || element === 'process' || element === 'definitions' || element === 'boundaryEvent') continue;
    const agent = element === 'serviceTask' && rest.includes('horus:type="agentTask"');
    nodes.push({ id, type: agent ? 'serviceTask:agentTask' : element === 'intermediateCatchEvent' ? 'intermediateCatchEvent:timer' : element });
  }
  return nodes;
}

export function createMigrationApi(definitions: () => readonly Definition[], seed: MigrationSeed = {}) {
  const clock = seed.clock ?? (() => Date.now());
  const iso = (ms: number) => new Date(ms).toISOString();
  const plans = new Map<string, Stored>();
  const calls: RecordedMigrationCall[] = [];
  const replies = new Map<string, MigrationReply>();
  let counter = 1;

  const json = (status: number, body: unknown, headers: Record<string, string> = {}): MigrationReply => ({ status, headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  const problem = (status: number, code: string, extra: Record<string, unknown> = {}): MigrationReply => ({
    status, headers: { 'Content-Type': 'application/problem+json' },
    body: JSON.stringify({ type: `${ERROR_TYPE}${code.toLowerCase()}`, title: code, status, code, correlationId: '01JD4C0RRE1AT10N0000000M16', ...extra }),
  });

  const find = (version: number) => definitions().find((d) => d.key === MIGRATION_KEY && d.version === version);
  const mode = seed.approval ?? 'four-eyes';

  function make(base: Pick<Stored, 'sourceVersion' | 'targetVersion' | 'mappings' | 'cancellations' | 'createdBy'>, id: string): Stored {
    const planHash = fakeHash(JSON.stringify([base.sourceVersion, base.targetVersion, base.mappings, base.cancellations]));
    return {
      id, definitionKey: MIGRATION_KEY, ...base, createdAt: iso(clock() - 3_600_000), planHash, mode, status: 'PLANNED', lockVersion: 0,
      proposedBy: null, proposedAt: null, reason: null, approvedBy: null, approvedAt: null, rejectedBy: null, rejectedAt: null, rejectionReason: null,
      migrated: 0, startedAt: null, finishedAt: null,
    };
  }

  /** The dry run of a stored plan, computed from the two versions and the cases waiting at each step. */
  function dryRun(plan: Stored) {
    const target = find(plan.targetVersion);
    const targetNodes = target !== undefined ? nodesOf(target.bpmn) : [];
    const typeOf = (list: Node[], id: string) => list.find((node) => node.id === id)?.type ?? null;
    let mapped = 0, cancelled = 0, inFlight = 0, unmapped = 0;
    const conflicts: { code: string; nodeId: string | null; name: string | null; instances: { count: number; instanceIds: string[]; truncated: boolean } }[] = [];
    const nodes = WAITING.map((step) => {
      const map = plan.mappings.find((item) => item.from === step.nodeId);
      const stop = plan.cancellations.find((item) => item.step === step.nodeId);
      const same = targetNodes.find((node) => node.id === step.nodeId);
      const sample = (count: number, ids: readonly number[]) => ({ count, instanceIds: ids.map(migrationCaseId), truncated: false });
      if (map !== undefined) { mapped += step.cases; return { nodeId: step.nodeId, name: step.name, type: step.type, liveTokens: step.cases, mapping: 'MAPPED', targetNodeId: map.to, targetType: typeOf(targetNodes, map.to) }; }
      if (stop !== undefined) {
        cancelled += step.cases - step.inFlight;
        if (step.inFlight > 0) {
          inFlight += step.inFlight;
          conflicts.push({ code: 'STEP_IN_FLIGHT', nodeId: step.nodeId, name: step.name, instances: sample(step.inFlight, IN_FLIGHT_CASES) });
        }
        return { nodeId: step.nodeId, name: step.name, type: step.type, liveTokens: step.cases, mapping: 'CANCELLED', targetNodeId: stop.moveTo, targetType: typeOf(targetNodes, stop.moveTo) };
      }
      if (same === undefined || same.type !== step.type) {
        unmapped += step.cases;
        const code = same === undefined ? 'STEP_NOT_IN_TARGET' : 'STEP_TYPE_CHANGED';
        conflicts.push({ code, nodeId: step.nodeId, name: step.name, instances: sample(step.cases, []) });
        return { nodeId: step.nodeId, name: step.name, type: step.type, liveTokens: step.cases, mapping: code, targetNodeId: same?.id ?? null, targetType: same?.type ?? null };
      }
      return { nodeId: step.nodeId, name: step.name, type: step.type, liveTokens: step.cases, mapping: 'SAME_ID', targetNodeId: same.id, targetType: same.type };
    });
    const targetActive = target?.status === 'ACTIVE';
    if (!targetActive) conflicts.push({ code: 'TARGET_NOT_ACTIVE', nodeId: null, name: null, instances: { count: CASE_COUNT, instanceIds: [], truncated: false } });
    const blocked = unmapped + inFlight;
    const checkedAt = clock();
    return {
      migrationId: plan.id, planHash: plan.planHash, definitionKey: plan.definitionKey, sourceVersion: plan.sourceVersion, targetVersion: plan.targetVersion,
      valid: conflicts.every((item) => item.code === 'STEP_IN_FLIGHT'), checkedAt: iso(checkedAt), validUntil: iso(checkedAt + 3_600_000),
      approval: { mode: plan.mode, secondPersonRequired: plan.mode === 'four-eyes' },
      instances: { running: CASE_COUNT, movable: CASE_COUNT - blocked, blocked },
      nodes, conflicts, movable: { count: CASE_COUNT - blocked, instanceIds: [], truncated: true },
      impact: { mapped, cancelled, inFlight, unmapped },
    };
  }

  const planBody = (plan: Stored) => ({
    id: plan.id, definitionKey: plan.definitionKey, sourceVersion: plan.sourceVersion, targetVersion: plan.targetVersion, status: plan.status, planHash: plan.planHash,
    approval: { mode: plan.mode, secondPersonRequired: plan.mode === 'four-eyes' }, createdBy: plan.createdBy, createdAt: plan.createdAt,
    mappings: plan.mappings, cancellations: plan.cancellations,
  });

  const skippedCount = IN_FLIGHT_CASES.length;
  const failedCount = FAILED_CASES.length;
  const executionBody = (plan: Stored) => {
    const done = plan.status === 'COMPLETED' || plan.status === 'COMPLETED_WITH_FAILURES';
    return {
      migrationId: plan.id, status: plan.status, approval: { mode: plan.mode, secondPersonRequired: plan.mode === 'four-eyes' }, lockVersion: plan.lockVersion,
      proposedBy: plan.proposedBy, proposedAt: plan.proposedAt, reason: plan.reason, approvedBy: plan.approvedBy, approvedAt: plan.approvedAt,
      rejectedBy: plan.rejectedBy, rejectedAt: plan.rejectedAt, rejectionReason: plan.rejectionReason,
      progress: { total: plan.status === 'PLANNED' || plan.status === 'PROPOSED' || plan.status === 'REJECTED' ? 0 : CASE_COUNT, migrated: plan.migrated, failed: done ? failedCount : 0, skipped: done ? skippedCount : 0 },
      startedAt: plan.startedAt, finishedAt: plan.finishedAt,
    };
  };
  const etag = (plan: Stored) => ({ ETag: `"${String(plan.lockVersion)}"` });

  /** Moves the run along by `count` cases; at the end the engine settles with the one busy case and the two in flight left behind. */
  function advance(id: string, count: number) {
    const plan = plans.get(id);
    if (plan === undefined || (plan.status !== 'APPROVED' && plan.status !== 'RUNNING')) return;
    const movable = CASE_COUNT - failedCount - skippedCount;
    plan.status = 'RUNNING';
    plan.migrated = Math.min(movable, plan.migrated + count);
    if (plan.migrated >= movable) {
      Object.assign(plan, { status: 'COMPLETED_WITH_FAILURES', finishedAt: iso(clock()), lockVersion: plan.lockVersion + 1 });
    }
  }

  function arrange(state: NonNullable<MigrationSeed['state']>) {
    const plan = make({ sourceVersion: 2, targetVersion: 3, createdBy: 'lan', mappings: [{ from: 'approve', to: 'senior-approval' }], cancellations: [{ step: 'cooling', moveTo: 'open-account', reason: seed.locale === 'vi' ? 'Đã bỏ bước chờ một ngày' : 'The one-day wait was retired' }] }, MIGRATION_PLAN_ID);
    plans.set(plan.id, plan);
    // The wait at `cooling` has no instruction: 30 cases wait at a step the plan does not cover.
    if (state === 'unmapped') plan.cancellations = [];
    if (state === 'planned' || state === 'unmapped') return;
    const proposer = seed.proposedBy ?? 'lan';
    Object.assign(plan, { status: 'PROPOSED', proposedBy: proposer, proposedAt: iso(clock() - 600_000), reason: seed.locale === 'vi' ? 'Chuyển sang quy trình KYC mới' : 'Moving to new KYC review', lockVersion: 1 });
    if (state === 'proposed') return;
    Object.assign(plan, { status: 'APPROVED', approvedBy: 'minh', approvedAt: iso(clock() - 300_000), startedAt: iso(clock() - 280_000), lockVersion: 2 });
    if (state === 'approved') return;
    advance(plan.id, 40);
    if (state === 'completed') advance(plan.id, 100);
  }
  if (seed.state !== undefined && seed.state !== 'none') arrange(seed.state);

  const reasons = (outcome: string) => outcome === 'FAILED'
    ? FAILED_CASES.map((n) => ({ instanceId: migrationCaseId(n), outcome: 'FAILED', code: 'LOCK_CONFLICT', detail: 'LOCK_CONFLICT at approve', occurredAt: iso(clock()), incidentId: '01JD4INCIDENT0000000000M01' }))
    : IN_FLIGHT_CASES.map((n) => ({ instanceId: migrationCaseId(n), outcome: 'SKIPPED', code: 'STEP_IN_FLIGHT', detail: 'STEP_IN_FLIGHT at cooling', occurredAt: iso(clock()), incidentId: null }));

  /** A case the migration touched, as getInstance answers it; the first has its reference masked. */
  function instance(id: string) {
    const number = [...FAILED_CASES, ...IN_FLIGHT_CASES].find((n) => migrationCaseId(n) === id);
    if (number === undefined) return undefined;
    return { id, definitionKey: MIGRATION_KEY, definitionVersion: 2, businessKey: CASE_KEYS[number] ?? null, status: 'RUNNING', variables: {}, tokens: [], tasks: [], lockVersion: 3, startedBy: 'lan', startedAt: '2026-11-19T02:00:00Z', endedAt: null };
  }

  function handle(request: MigrationRequest, path: string, url: URL): MigrationReply | undefined {
    const header = (name: string) => request.headers[name] ?? request.headers[name.toLowerCase()];
    const may = (name: 'manage' | 'read') => request.permissions.includes('processes:migration:manage') || (name === 'read' && request.permissions.includes('processes:read'));
    if (request.method === 'POST' && path === `${BASE}/migrations`) {
      if (!may('manage')) return problem(403, 'PERMISSION_DENIED');
      const key = header('Idempotency-Key');
      if (key === undefined) return problem(428, 'PRECONDITION_REQUIRED');
      const replay = replies.get(`${request.user}:${key}`);
      if (replay !== undefined) return replay;
      let body: Record<string, unknown>;
      try { body = JSON.parse(request.body || '{}') as Record<string, unknown>; } catch { return problem(400, 'INPUT_INVALID'); }
      calls.push({ command: 'create', by: request.user, ifMatch: undefined, idempotencyKey: key, body });
      const source = Number(body['sourceVersion']);
      const target = Number(body['targetVersion']);
      if (source === target) return problem(400, 'INPUT_INVALID');
      const from = find(source);
      const to = find(target);
      if (from === undefined || to === undefined) return problem(404, 'DEFINITION_NOT_FOUND');
      if (to.status !== 'ACTIVE') return problem(409, 'INVALID_STATE');
      const mappings = (Array.isArray(body['mappings']) ? body['mappings'] : []) as { from: string; to: string }[];
      const cancellations = (Array.isArray(body['cancellations']) ? body['cancellations'] : []) as { step: string; moveTo: string; reason: string }[];
      const targetNodes = nodesOf(to.bpmn);
      const sourceNodes = nodesOf(from.bpmn);
      const paths: string[] = [];
      for (const item of mappings) {
        const kind = sourceNodes.find((node) => node.id === item.from)?.type;
        if (targetNodes.find((node) => node.id === item.to)?.type !== kind) paths.push(`/mappings/${item.from}`);
      }
      for (const item of cancellations) {
        if (!targetNodes.some((node) => node.id === item.moveTo) || item.reason.trim() === '') paths.push(`/cancellations/${item.step}`);
      }
      if (paths.length > 0) return problem(422, 'MIGRATION_PLAN_INVALID', { paths });
      counter += 1;
      const id = `01JD4M1GRAT10N0000000${String(counter).padStart(5, '0')}`;
      const plan = make({ sourceVersion: source, targetVersion: target, mappings, cancellations, createdBy: request.user }, id);
      plans.set(id, plan);
      const created = json(201, planBody(plan), { Location: `${BASE}/migrations/${id}` });
      replies.set(`${request.user}:${key}`, created);
      return created;
    }
    const match = /^\/api\/bpm\/processes\/v1\/migrations\/([^/:]+)(?::(validate|execute|approve|reject)|(\/execution|\/instances))?$/.exec(path);
    if (match === null) return undefined;
    const plan = plans.get(match[1] ?? '');
    const command = match[2];
    const sub = match[3];
    if (!may(command === undefined ? 'read' : command === 'validate' ? 'manage' : 'manage')) return problem(403, 'PERMISSION_DENIED');
    if (plan === undefined) return problem(404, 'MIGRATION_NOT_FOUND');
    if (request.method === 'GET' && command === undefined && sub === undefined) return json(200, planBody(plan));
    if (request.method === 'GET' && sub === '/execution') return json(200, executionBody(plan), etag(plan));
    if (request.method === 'GET' && sub === '/instances') {
      const outcome = url.searchParams.get('outcome') ?? 'FAILED';
      if (plan.status !== 'COMPLETED' && plan.status !== 'COMPLETED_WITH_FAILURES') return json(200, { items: [], nextAfter: null });
      return json(200, { items: reasons(outcome), nextAfter: null });
    }
    if (request.method !== 'POST' || command === undefined) return problem(400, 'INPUT_INVALID');
    if (command === 'validate') {
      calls.push({ command: 'validate', by: request.user, ifMatch: undefined, idempotencyKey: undefined, body: {} });
      return json(200, dryRun(plan));
    }
    const key = header('Idempotency-Key');
    if (key === undefined) return problem(428, 'PRECONDITION_REQUIRED');
    let body: Record<string, unknown>;
    try { body = JSON.parse(request.body || '{}') as Record<string, unknown>; } catch { return problem(400, 'INPUT_INVALID'); }
    const ifMatch = header('If-Match');
    const recorded = command === 'execute' ? 'propose' : command === 'approve' ? 'approve' : 'reject';
    calls.push({ command: recorded, by: request.user, ifMatch, idempotencyKey: key, body });
    const reason = typeof body['reason'] === 'string' ? body['reason'].trim() : '';
    if (command === 'execute') {
      if (reason === '') return problem(400, 'INPUT_INVALID');
      if (plan.status !== 'PLANNED' && plan.status !== 'REJECTED') return problem(409, 'INVALID_STATE');
      if (!dryRun(plan).valid) return problem(409, 'DRY_RUN_REQUIRED');
      const single = plan.mode === 'single';
      Object.assign(plan, {
        status: single ? 'APPROVED' : 'PROPOSED', proposedBy: request.user, proposedAt: iso(clock()), reason, lockVersion: plan.lockVersion + 1,
        ...(single ? { approvedBy: request.user, approvedAt: iso(clock()), startedAt: iso(clock()) } : {}),
      });
      return json(202, executionBody(plan), etag(plan));
    }
    if (ifMatch === undefined) return problem(428, 'PRECONDITION_REQUIRED');
    if (plan.status !== 'PROPOSED') return problem(409, 'INVALID_STATE');
    if (plan.proposedBy === request.user) return problem(403, 'SELF_APPROVAL_FORBIDDEN');
    if (ifMatch !== `"${String(plan.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT');
    if (command === 'reject') {
      if (reason === '') return problem(400, 'INPUT_INVALID');
      Object.assign(plan, { status: 'REJECTED', rejectedBy: request.user, rejectedAt: iso(clock()), rejectionReason: reason, lockVersion: plan.lockVersion + 1 });
      return json(200, executionBody(plan), etag(plan));
    }
    if (!dryRun(plan).valid) return problem(409, 'DRY_RUN_REQUIRED');
    Object.assign(plan, { status: 'APPROVED', approvedBy: request.user, approvedAt: iso(clock()), startedAt: iso(clock()), lockVersion: plan.lockVersion + 1 });
    return json(200, executionBody(plan), etag(plan));
  }

  /** Another second person changes the proposal while this one reads it: the lockVersion moves on. */
  function touch(id: string) {
    const plan = plans.get(id);
    if (plan !== undefined) plan.lockVersion += 1;
  }

  return { handle, advance, instance, calls, touch, plan: (id: string) => plans.get(id) };
}
