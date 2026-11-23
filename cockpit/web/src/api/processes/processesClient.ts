import type { z } from 'zod';
import { callHost, callHostIfChanged, downloadFromHost, type Conditional, type HostRequest } from '../hostCall';
import { chunksOf, displayNames, DISPLAY_NAMES_PATH, mergeNames, namesQuery, NO_NAMES, type DisplayNames, type WantedNames } from '../displayNames';
import type { Page } from '../rules/schemas';
import {
  definitionSummaryPage,
  definitionVersion,
  pendingDefinitionApprovalPage,
  type PendingDefinitionApproval,
  graphDiff,
  migrationDryRun,
  migrationExecution,
  migrationOutcomePage,
  migrationPlan,
  incident,
  incidentPage,
  type Incident,
  type IncidentStatus,
  instanceActivities,
  instanceSummaryPage,
  INSTANCE_STATUSES,
  processInstance,
  task,
  taskForm,
  taskSummaryPage,
  type DefinitionSummary,
  type DefinitionVersion,
  type GraphDiff,
  type InstanceActivities,
  type InstanceSummary,
  type MigrationDryRun,
  type MigrationExecution,
  type MigrationOutcomePage,
  type MigrationPlan,
  type MigrationStepCancellation,
  type MigrationStepMapping,
  type ProcessInstance,
  type Task,
  type TaskForm,
  type TaskSummary,
} from './schemas';

type Fetch = typeof fetch;

export const PROCESSES_BASE = '/api/bpm/processes/v1';

/** What a reviewer answers on the review task of a refused agent answer: the result and why (#59). */
export interface TaskAnswer {
  result: Readonly<Record<string, unknown>>;
  /** Asked of a review; a task completed through its declared form sends none. */
  reason?: string;
}

/** What listTasks filters by; each member maps one to one to a query parameter of `GET /tasks`. */
export interface TaskQuery {
  /** `mine`, `candidate` and `any` (the last needs processes:task:manage), comma separated by the client. */
  assignment: readonly ('mine' | 'candidate' | 'any')[];
  status?: readonly ('OPEN' | 'CLAIMED')[] | undefined;
  slaBreached?: boolean | undefined;
  limit?: number | undefined;
  cursor?: string | undefined;
  signal?: AbortSignal | undefined;
}

/** Who takes a task over and why (PD-110): a user id, and a reason of 1 to 500 characters. */
export interface TaskHandOver {
  assignee: string;
  reason: string;
}

/** A resolution proposed for an incident (PRE-078): the step's output, money as `{ amount, currency }`, and why. */
export interface IncidentResolution {
  output: Readonly<Record<string, unknown>>;
  reason: string;
}

/**
 * What Approve sends (approveDefinition): the definitionHash of the content the approver reviewed, never one read
 * again at decision time (PD-139), and an optional comment.
 */
export interface DefinitionApproval {
  expectedDefinitionHash: string;
  comment: string;
}

/** What a migration plan is made of: two versions of one key and the instructions for steps whose id changed or went. */
export interface MigrationPlanRequest {
  definitionKey: string;
  sourceVersion: number;
  targetVersion: number;
  mappings: readonly MigrationStepMapping[];
  cancellations: readonly MigrationStepCancellation[];
}

/**
 * Typed client of the Process Engine REST API (process-engine/api/openapi.yaml). One method per operation; the
 * operation id is named on each. Task commands carry the caller's Idempotency-Key and the task's lockVersion as
 * If-Match.
 */
export interface ProcessesClient {
  /** getDisplayNames (host contract): readable names of roles, groups and users in `language`; only the known ones */
  getDisplayNames(wanted: WantedNames, language: string, signal?: AbortSignal): Promise<DisplayNames>;
  /** listDefinitions: one page of keys, ordered by key */
  listDefinitions(request?: { cursor?: string | undefined; limit?: number | undefined; signal?: AbortSignal | undefined }): Promise<Page<DefinitionSummary>>;
  /** listPendingApprovals: one page of definition versions waiting for a second person, oldest request first (PRE-190) */
  listPendingApprovals(request?: { cursor?: string | undefined; limit?: number | undefined; signal?: AbortSignal | undefined }): Promise<Page<PendingDefinitionApproval>>;
  /** getDefinitionVersion */
  getVersion(key: string, version: number, signal?: AbortSignal): Promise<DefinitionVersion>;
  /** getDefinitionDiff: steps and links changed from `base` (the version in effect when absent) to this version */
  getDiff(key: string, version: number, base?: number, signal?: AbortSignal): Promise<GraphDiff>;
  /** approveDefinition: as a second person, bound to the reviewed content hash */
  approveDefinition(key: string, version: number, lockVersion: number, approval: DefinitionApproval, idempotencyKey: string): Promise<DefinitionVersion>;
  /** rejectDefinition: back to draft with a reason */
  rejectDefinition(key: string, version: number, lockVersion: number, reason: string, idempotencyKey: string): Promise<DefinitionVersion>;
  /** exportDefinition (format=bpmn): the version's graph as BPMN 2.0 XML; writes nothing */
  exportBpmn(key: string, version: number, signal?: AbortSignal): Promise<string>;
  /** getTask: the task with the actions this caller may take, and the review context of a refused agent answer */
  getTask(id: string, signal?: AbortSignal): Promise<Task>;
  /** getInstance: the instance with its tokens and tasks; the review reads who started it (four eyes) */
  getInstance(id: string, signal?: AbortSignal): Promise<ProcessInstance>;
  /** listInstances: one page of the instances of one key, every status, newest first */
  listInstances(request: { definitionKey: string; cursor?: string | undefined; limit?: number | undefined; signal?: AbortSignal | undefined }): Promise<Page<InstanceSummary>>;
  /** getInstanceActivities: per step what happened, and the history entries in time order */
  getInstanceActivities(id: string, signal?: AbortSignal): Promise<InstanceActivities>;
  /** listTasks: one page in due-time order, tasks without a due time last */
  listTasks(query: TaskQuery): Promise<Page<TaskSummary>>;
  /** listTasks as a conditional read: sends If-None-Match when `etag` is given; 304 is `changed: false` (CKP-019) */
  readTasks(query: TaskQuery, etag?: string): Promise<Conditional<Page<TaskSummary>>>;
  /** getTaskForm: the form the task's step declares, masked fields still masked, or the link to the host's screen (404 when none) */
  getTaskForm(id: string, signal?: AbortSignal): Promise<TaskForm>;
  /** claimTask */
  claimTask(id: string, lockVersion: number, idempotencyKey: string): Promise<Task>;
  /** unclaimTask: hand a claimed task back to its candidates */
  unclaimTask(id: string, lockVersion: number, idempotencyKey: string): Promise<Task>;
  /** reassignTask: an open task to a named user, with a reason (processes:task:manage) */
  reassignTask(id: string, lockVersion: number, handOver: TaskHandOver, idempotencyKey: string): Promise<Task>;
  /** delegateTask: a claimed task to another user, with a reason (processes:task:manage) */
  delegateTask(id: string, lockVersion: number, handOver: TaskHandOver, idempotencyKey: string): Promise<Task>;
  /** createMigrationPlan: moves nothing; the plan is stored with its approval mode */
  createMigrationPlan(plan: MigrationPlanRequest, idempotencyKey: string): Promise<MigrationPlan>;
  /** getMigrationPlan */
  getMigrationPlan(id: string, signal?: AbortSignal): Promise<MigrationPlan>;
  /** validateMigrationPlan: the dry run; it only reads */
  checkMigrationPlan(id: string, signal?: AbortSignal): Promise<MigrationDryRun>;
  /** proposeMigrationExecution: asks a second person to approve moving the instances, with a reason */
  proposeMigration(id: string, reason: string, idempotencyKey: string): Promise<MigrationExecution>;
  /** approveMigrationExecution: as a second person; `lockVersion` is the proposal the approver was shown */
  approveMigration(id: string, lockVersion: number, idempotencyKey: string): Promise<MigrationExecution>;
  /** rejectMigrationExecution: back to the proposer with a reason */
  rejectMigration(id: string, lockVersion: number, reason: string, idempotencyKey: string): Promise<MigrationExecution>;
  /** getMigrationExecution: the proposal, the approval and the progress */
  getMigrationExecution(id: string, signal?: AbortSignal): Promise<MigrationExecution>;
  /** listMigrationInstances: what happened to each instance, one outcome kind at a time, a bounded page */
  listMigrationInstances(request: { id: string; outcome: 'FAILED' | 'SKIPPED'; after?: string | undefined; limit?: number | undefined; signal?: AbortSignal | undefined }): Promise<MigrationOutcomePage>;
  /** listIncidents: one page of the incidents of one status, newest first */
  listIncidents(request: { status: IncidentStatus; cursor?: string | undefined; limit?: number | undefined; signal?: AbortSignal | undefined }): Promise<Page<Incident>>;
  /** listIncidents as a conditional read: sends If-None-Match when `etag` is given; 304 is `changed: false` (CKP-019) */
  readIncidents(request: { status: IncidentStatus; cursor?: string | undefined; limit?: number | undefined; signal?: AbortSignal | undefined }, etag?: string): Promise<Conditional<Page<Incident>>>;
  /** getIncident: one incident with the actions this caller may take */
  getIncident(id: string, signal?: AbortSignal): Promise<Incident>;
  /** retryIncident: run the failed step again, with a reason; one person's action (PRE-077) */
  retryIncident(id: string, lockVersion: number, reason: string, idempotencyKey: string): Promise<Incident>;
  /** resolveIncident: propose the output the waiting step completes with; a second person approves it (PRE-078, PD-126) */
  resolveIncident(id: string, lockVersion: number, resolution: IncidentResolution, idempotencyKey: string): Promise<Incident>;
  /** approveIncidentResolution: as a second person; the step completes with the proposed output */
  approveResolution(id: string, lockVersion: number, comment: string, idempotencyKey: string): Promise<Incident>;
  /** rejectIncidentResolution: as a second person, with a reason; the incident is open again */
  rejectResolution(id: string, lockVersion: number, reason: string, idempotencyKey: string): Promise<Incident>;
  /** completeTask: the reviewer's answer and reason; the engine records HUMAN_OVERRIDE */
  completeTask(id: string, lockVersion: number, answer: TaskAnswer, idempotencyKey: string): Promise<ProcessInstance>;
}

const segment = encodeURIComponent;

function tasksPath(request: TaskQuery): string {
  const query = new URLSearchParams();
  query.set('assignment', request.assignment.join(','));
  if (request.status !== undefined && request.status.length > 0) query.set('status', request.status.join(','));
  if (request.slaBreached !== undefined) query.set('slaBreached', String(request.slaBreached));
  if (request.limit !== undefined) query.set('limit', String(request.limit));
  if (request.cursor !== undefined) query.set('cursor', request.cursor);
  return `${PROCESSES_BASE}/tasks?${query.toString()}`;
}

function incidentsPath(request: { status: IncidentStatus; cursor?: string | undefined; limit?: number | undefined }): string {
  const query = new URLSearchParams({ status: request.status });
  if (request.limit !== undefined) query.set('limit', String(request.limit));
  if (request.cursor !== undefined) query.set('cursor', request.cursor);
  return `${PROCESSES_BASE}/incidents?${query.toString()}`;
}

export function createProcessesClient(baseUrl: string, fetchImpl: Fetch = fetch): ProcessesClient {
  const call = <T,>(request: HostRequest, schema: z.ZodType<T>) => callHost(fetchImpl, baseUrl, request, schema);
  const versionPath = (key: string, version: number) => `${PROCESSES_BASE}/definitions/${segment(key)}/versions/${String(version)}`;
  const command = (lockVersion: number, idempotencyKey: string) => ({ 'Idempotency-Key': idempotencyKey, 'If-Match': `"${String(lockVersion)}"` });
  const withSignal = (signal: AbortSignal | undefined) => (signal ? { signal } : {});

  return {
    async getDisplayNames(wanted, language, signal) {
      const answers = await Promise.all(chunksOf(wanted).map(async (chunk) => (
        await call({ method: 'GET', path: `${DISPLAY_NAMES_PATH}?${namesQuery(chunk)}`, headers: { 'Accept-Language': language }, ...withSignal(signal) }, displayNames)
      ).data));
      return answers.length === 0 ? NO_NAMES : mergeNames(answers);
    },
    async listDefinitions(request = {}) {
      const query = new URLSearchParams();
      if (request.limit !== undefined) query.set('limit', String(request.limit));
      if (request.cursor !== undefined) query.set('cursor', request.cursor);
      const text = query.toString();
      const answer = await call({ method: 'GET', path: `${PROCESSES_BASE}/definitions${text === '' ? '' : `?${text}`}`, ...withSignal(request.signal) }, definitionSummaryPage);
      return answer.data;
    },
    async listPendingApprovals(request = {}) {
      const query = new URLSearchParams();
      if (request.limit !== undefined) query.set('limit', String(request.limit));
      if (request.cursor !== undefined) query.set('cursor', request.cursor);
      const text = query.toString();
      const answer = await call({ method: 'GET', path: `${PROCESSES_BASE}/approvals${text === '' ? '' : `?${text}`}`, ...withSignal(request.signal) }, pendingDefinitionApprovalPage);
      return answer.data;
    },
    async getVersion(key, version, signal) {
      return (await call({ method: 'GET', path: versionPath(key, version), ...withSignal(signal) }, definitionVersion)).data;
    },
    async getDiff(key, version, base, signal) {
      const query = base !== undefined ? `?base=${String(base)}` : '';
      return (await call({ method: 'GET', path: `${versionPath(key, version)}/diff${query}`, ...withSignal(signal) }, graphDiff)).data;
    },
    async approveDefinition(key, version, lockVersion, approval, idempotencyKey) {
      const comment = approval.comment.trim();
      const body = { contentType: 'application/json', text: JSON.stringify({ expectedDefinitionHash: approval.expectedDefinitionHash, comment: comment === '' ? null : comment }) };
      return (await call({ method: 'POST', path: `${versionPath(key, version)}:approve`, headers: command(lockVersion, idempotencyKey), body }, definitionVersion)).data;
    },
    async rejectDefinition(key, version, lockVersion, reason, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ reason }) };
      return (await call({ method: 'POST', path: `${versionPath(key, version)}:reject`, headers: command(lockVersion, idempotencyKey), body }, definitionVersion)).data;
    },
    async exportBpmn(key, version, signal) {
      const answer = await downloadFromHost(fetchImpl, baseUrl, { method: 'GET', path: `${versionPath(key, version)}/export?format=bpmn`, ...withSignal(signal) }, 'application/bpmn+xml');
      return answer.file.text();
    },
    async getTask(id, signal) {
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/tasks/${segment(id)}`, ...withSignal(signal) }, task)).data;
    },
    async getInstance(id, signal) {
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/instances/${segment(id)}`, ...withSignal(signal) }, processInstance)).data;
    },
    async listInstances(request) {
      const query = new URLSearchParams({ status: INSTANCE_STATUSES.join(','), definitionKey: request.definitionKey });
      if (request.limit !== undefined) query.set('limit', String(request.limit));
      if (request.cursor !== undefined) query.set('cursor', request.cursor);
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/instances?${query.toString()}`, ...withSignal(request.signal) }, instanceSummaryPage)).data;
    },
    async getInstanceActivities(id, signal) {
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/instances/${segment(id)}/activities`, ...withSignal(signal) }, instanceActivities)).data;
    },
    async readTasks(request, etag) {
      return callHostIfChanged(fetchImpl, baseUrl, { method: 'GET', path: tasksPath(request), ...withSignal(request.signal) }, taskSummaryPage, etag);
    },
    async readIncidents(request, etag) {
      return callHostIfChanged(fetchImpl, baseUrl, { method: 'GET', path: incidentsPath(request), ...withSignal(request.signal) }, incidentPage, etag);
    },
    async listTasks(request) {
      const query = new URLSearchParams();
      query.set('assignment', request.assignment.join(','));
      if (request.status !== undefined && request.status.length > 0) query.set('status', request.status.join(','));
      if (request.slaBreached !== undefined) query.set('slaBreached', String(request.slaBreached));
      if (request.limit !== undefined) query.set('limit', String(request.limit));
      if (request.cursor !== undefined) query.set('cursor', request.cursor);
      const answer = await call({ method: 'GET', path: `${PROCESSES_BASE}/tasks?${query.toString()}`, ...withSignal(request.signal) }, taskSummaryPage);
      return answer.data;
    },
    async getTaskForm(id, signal) {
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/tasks/${segment(id)}/form`, ...withSignal(signal) }, taskForm)).data;
    },
    async claimTask(id, lockVersion, idempotencyKey) {
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/tasks/${segment(id)}:claim`, headers: command(lockVersion, idempotencyKey) }, task)).data;
    },
    async unclaimTask(id, lockVersion, idempotencyKey) {
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/tasks/${segment(id)}:unclaim`, headers: command(lockVersion, idempotencyKey) }, task)).data;
    },
    async reassignTask(id, lockVersion, handOver, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ assignee: handOver.assignee, reason: handOver.reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/tasks/${segment(id)}:reassign`, headers: command(lockVersion, idempotencyKey), body }, task)).data;
    },
    async delegateTask(id, lockVersion, handOver, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ assignee: handOver.assignee, reason: handOver.reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/tasks/${segment(id)}:delegate`, headers: command(lockVersion, idempotencyKey), body }, task)).data;
    },
    async listIncidents(request) {
      const query = new URLSearchParams({ status: request.status });
      if (request.limit !== undefined) query.set('limit', String(request.limit));
      if (request.cursor !== undefined) query.set('cursor', request.cursor);
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/incidents?${query.toString()}`, ...withSignal(request.signal) }, incidentPage)).data;
    },
    async getIncident(id, signal) {
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/incidents/${segment(id)}`, ...withSignal(signal) }, incident)).data;
    },
    async retryIncident(id, lockVersion, reason, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/incidents/${segment(id)}:retry`, headers: command(lockVersion, idempotencyKey), body }, incident)).data;
    },
    async resolveIncident(id, lockVersion, resolution, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ output: resolution.output, reason: resolution.reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/incidents/${segment(id)}:resolve`, headers: command(lockVersion, idempotencyKey), body }, incident)).data;
    },
    async approveResolution(id, lockVersion, comment, idempotencyKey) {
      const text = comment.trim();
      const body = { contentType: 'application/json', text: JSON.stringify({ comment: text === '' ? null : text }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/incidents/${segment(id)}:approve-resolution`, headers: command(lockVersion, idempotencyKey), body }, incident)).data;
    },
    async rejectResolution(id, lockVersion, reason, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/incidents/${segment(id)}:reject-resolution`, headers: command(lockVersion, idempotencyKey), body }, incident)).data;
    },
    async completeTask(id, lockVersion, answer, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ result: answer.result, reason: answer.reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/tasks/${segment(id)}:complete`, headers: command(lockVersion, idempotencyKey), body }, processInstance)).data;
    },
    async createMigrationPlan(plan, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify(plan) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/migrations`, headers: { 'Idempotency-Key': idempotencyKey }, body }, migrationPlan)).data;
    },
    async getMigrationPlan(id, signal) {
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/migrations/${segment(id)}`, ...withSignal(signal) }, migrationPlan)).data;
    },
    async checkMigrationPlan(id, signal) {
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/migrations/${segment(id)}:validate`, ...withSignal(signal) }, migrationDryRun)).data;
    },
    async proposeMigration(id, reason, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/migrations/${segment(id)}:execute`, headers: { 'Idempotency-Key': idempotencyKey }, body }, migrationExecution)).data;
    },
    async approveMigration(id, lockVersion, idempotencyKey) {
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/migrations/${segment(id)}:approve`, headers: command(lockVersion, idempotencyKey) }, migrationExecution)).data;
    },
    async rejectMigration(id, lockVersion, reason, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/migrations/${segment(id)}:reject`, headers: command(lockVersion, idempotencyKey), body }, migrationExecution)).data;
    },
    async getMigrationExecution(id, signal) {
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/migrations/${segment(id)}/execution`, ...withSignal(signal) }, migrationExecution)).data;
    },
    async listMigrationInstances(request) {
      const query = new URLSearchParams({ outcome: request.outcome });
      if (request.after !== undefined) query.set('after', request.after);
      if (request.limit !== undefined) query.set('limit', String(request.limit));
      return (await call({ method: 'GET', path: `${PROCESSES_BASE}/migrations/${segment(request.id)}/instances?${query.toString()}`, ...withSignal(request.signal) }, migrationOutcomePage)).data;
    },
  };
}
