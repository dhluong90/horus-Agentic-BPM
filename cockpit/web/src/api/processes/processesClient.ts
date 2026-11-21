import type { z } from 'zod';
import { callHost, downloadFromHost, type HostRequest } from '../hostCall';
import type { Page } from '../rules/schemas';
import {
  definitionSummaryPage,
  definitionVersion,
  processInstance,
  task,
  taskSummaryPage,
  type DefinitionSummary,
  type DefinitionVersion,
  type ProcessInstance,
  type Task,
  type TaskSummary,
} from './schemas';

type Fetch = typeof fetch;

export const PROCESSES_BASE = '/api/bpm/processes/v1';

/** What a reviewer answers on the review task of a refused agent answer: the result and why (#59). */
export interface TaskAnswer {
  result: Readonly<Record<string, unknown>>;
  reason: string;
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

/**
 * Typed client of the Process Engine REST API (process-engine/api/openapi.yaml). One method per operation; the
 * operation id is named on each. Task commands carry the caller's Idempotency-Key and the task's lockVersion as
 * If-Match.
 */
export interface ProcessesClient {
  /** listDefinitions: one page of keys, ordered by key */
  listDefinitions(request?: { cursor?: string | undefined; limit?: number | undefined; signal?: AbortSignal | undefined }): Promise<Page<DefinitionSummary>>;
  /** getDefinitionVersion */
  getVersion(key: string, version: number, signal?: AbortSignal): Promise<DefinitionVersion>;
  /** exportDefinition (format=bpmn): the version's graph as BPMN 2.0 XML; writes nothing */
  exportBpmn(key: string, version: number, signal?: AbortSignal): Promise<string>;
  /** getTask: the task with the actions this caller may take, and the review context of a refused agent answer */
  getTask(id: string, signal?: AbortSignal): Promise<Task>;
  /** getInstance: read for who started it (four eyes) */
  getInstance(id: string, signal?: AbortSignal): Promise<ProcessInstance>;
  /** listTasks: one page in due-time order, tasks without a due time last */
  listTasks(query: TaskQuery): Promise<Page<TaskSummary>>;
  /** claimTask */
  claimTask(id: string, lockVersion: number, idempotencyKey: string): Promise<Task>;
  /** unclaimTask: hand a claimed task back to its candidates */
  unclaimTask(id: string, lockVersion: number, idempotencyKey: string): Promise<Task>;
  /** reassignTask: an open task to a named user, with a reason (processes:task:manage) */
  reassignTask(id: string, lockVersion: number, handOver: TaskHandOver, idempotencyKey: string): Promise<Task>;
  /** delegateTask: a claimed task to another user, with a reason (processes:task:manage) */
  delegateTask(id: string, lockVersion: number, handOver: TaskHandOver, idempotencyKey: string): Promise<Task>;
  /** completeTask: the reviewer's answer and reason; the engine records HUMAN_OVERRIDE */
  completeTask(id: string, lockVersion: number, answer: TaskAnswer, idempotencyKey: string): Promise<ProcessInstance>;
}

const segment = encodeURIComponent;

export function createProcessesClient(baseUrl: string, fetchImpl: Fetch = fetch): ProcessesClient {
  const call = <T,>(request: HostRequest, schema: z.ZodType<T>) => callHost(fetchImpl, baseUrl, request, schema);
  const versionPath = (key: string, version: number) => `${PROCESSES_BASE}/definitions/${segment(key)}/versions/${String(version)}`;
  const command = (lockVersion: number, idempotencyKey: string) => ({ 'Idempotency-Key': idempotencyKey, 'If-Match': `"${String(lockVersion)}"` });
  const withSignal = (signal: AbortSignal | undefined) => (signal ? { signal } : {});

  return {
    async listDefinitions(request = {}) {
      const query = new URLSearchParams();
      if (request.limit !== undefined) query.set('limit', String(request.limit));
      if (request.cursor !== undefined) query.set('cursor', request.cursor);
      const text = query.toString();
      const answer = await call({ method: 'GET', path: `${PROCESSES_BASE}/definitions${text === '' ? '' : `?${text}`}`, ...withSignal(request.signal) }, definitionSummaryPage);
      return answer.data;
    },
    async getVersion(key, version, signal) {
      return (await call({ method: 'GET', path: versionPath(key, version), ...withSignal(signal) }, definitionVersion)).data;
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
    async completeTask(id, lockVersion, answer, idempotencyKey) {
      const body = { contentType: 'application/json', text: JSON.stringify({ result: answer.result, reason: answer.reason }) };
      return (await call({ method: 'POST', path: `${PROCESSES_BASE}/tasks/${segment(id)}:complete`, headers: command(lockVersion, idempotencyKey), body }, processInstance)).data;
    },
  };
}
