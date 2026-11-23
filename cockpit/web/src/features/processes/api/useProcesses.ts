import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import type { DefinitionApproval, TaskAnswer } from '../../../api/processes/processesClient';
import type { DefinitionSummary, DefinitionVersion, Task } from '../../../api/processes/schemas';
import { toHostError } from '../../../api/hostError';
import { readBpmnGraph } from '../model/bpmnGraph';
import { useProcessesSession } from './processesContext';

/*
 * Server state of the process screens. Every key starts with host and tenant, so another host's or tenant's answer
 * can never show (cockpit DESIGN section 12). Task commands never change the screen before the engine answers.
 */

export function useKeys() {
  const { hostId, tenant } = useProcessesSession();
  const base = [hostId, tenant, 'processes'] as const;
  return {
    definitions: [...base, 'definitions'] as const,
    /** The approvals queue's pages (#31): read again after a decision here, so the queue and its count follow. */
    approvals: [...base, 'approvals'] as const,
    version: (key: string, version: number) => [...base, 'definition', key, version] as const,
    graph: (key: string, version: number) => [...base, 'graph', key, version] as const,
    diff: (key: string, version: number) => [...base, 'diff', key, version] as const,
    task: (id: string) => [...base, 'task', id] as const,
    taskForm: (id: string) => [...base, 'task', id, 'form'] as const,
    instance: (id: string) => [...base, 'instance', id] as const,
    activities: (id: string) => [...base, 'instance', id, 'activities'] as const,
    instances: (key: string) => [...base, 'instances', key] as const,
    migration: (id: string) => [...base, 'migration', id] as const,
    calendarChanges: [...base, 'calendar-changes'] as const,
    calendarChange: (id: string) => [...base, 'calendar-changes', id] as const,
    migrationOutcomes: (id: string) => [...base, 'migration', id, 'outcomes'] as const,
  };
}

/** Every definition key of the tenant, page by page up to the engine's page limit. */
export function useDefinitions(pageLimit: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.definitions,
    queryFn: async ({ signal }) => {
      const all: DefinitionSummary[] = [];
      let cursor: string | undefined;
      do {
        const page = await client.listDefinitions({ cursor, limit: pageLimit, signal });
        all.push(...page.data);
        cursor = page.hasNext && page.nextCursor !== null ? page.nextCursor : undefined;
      } while (cursor !== undefined);
      return all;
    },
    staleTime: 10_000,
  });
}

export function useDefinitionVersion(key: string, version: number, enabled = true) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.version(key, version), queryFn: ({ signal }) => client.getVersion(key, version, signal), staleTime: 60_000, enabled });
}

/** The version's graph, read from its BPMN export. A version is immutable, so its graph never goes stale. */
export function useDefinitionGraph(key: string, version: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.graph(key, version),
    queryFn: async ({ signal }) => readBpmnGraph(await client.exportBpmn(key, version, signal)),
    staleTime: Infinity,
  });
}

/**
 * The engine's change list from the version in effect to this one. A key with no version in effect answers
 * 404 NO_EFFECTIVE_VERSION: this is a first version, with nothing to compare, and is returned as `null`.
 */
export function useDefinitionDiff(key: string, version: number, enabled: boolean) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.diff(key, version),
    queryFn: async ({ signal }) => {
      try {
        return await client.getDiff(key, version, undefined, signal);
      } catch (error) {
        const hostError = toHostError(error);
        if (hostError.kind === 'problem' && hostError.code === 'NO_EFFECTIVE_VERSION') return null;
        throw error;
      }
    },
    enabled,
    retry: false,
  });
}

/**
 * Approve or reject a pending version, then read the key again. Nothing changes on screen before the engine
 * answers; the answer is the version as stored, which replaces the cached one.
 */
function useLifecycle<TArgs>(key: string, send: (version: DefinitionVersion, args: TArgs, idempotencyKey: string) => Promise<DefinitionVersion>, name: string) {
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: ({ version, args }: { version: DefinitionVersion; args: TArgs }) =>
      send(version, args, keyFor(`${name}:${key}:${String(version.version)}:${String(version.lockVersion ?? 0)}:${JSON.stringify(args)}`)),
    onSuccess: async (stored) => {
      queryClient.setQueryData(keys.version(stored.key, stored.version), stored);
      await Promise.all([queryClient.invalidateQueries({ queryKey: keys.definitions }), queryClient.invalidateQueries({ queryKey: keys.approvals })]);
    },
  });
}

export function useApproveDefinition(key: string) {
  const { client } = useProcessesSession();
  return useLifecycle<DefinitionApproval>(key, (version, approval, idempotencyKey) => client.approveDefinition(version.key, version.version, version.lockVersion ?? 0, approval, idempotencyKey), 'approve');
}

export function useRejectDefinition(key: string) {
  const { client } = useProcessesSession();
  return useLifecycle<string>(key, (version, reason, idempotencyKey) => client.rejectDefinition(version.key, version.version, version.lockVersion ?? 0, reason, idempotencyKey), 'reject');
}

export function useTask(id: string) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.task(id), queryFn: ({ signal }) => client.getTask(id, signal) });
}

/** The form a task is completed through; a task whose step declares none answers 404 FORM_NOT_DECLARED (CKP-021). */
export function useTaskForm(id: string, enabled: boolean) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.taskForm(id), queryFn: ({ signal }) => client.getTaskForm(id, signal), enabled, retry: false });
}

/** The task's instance, for who started it. A reviewer without processes:read gets no answer; four eyes then rests on the engine. */
export function useInstance(id: string | undefined, live = false) {
  const { client, can } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.instance(id ?? ''),
    queryFn: ({ signal }) => client.getInstance(id ?? '', signal),
    enabled: id !== undefined && can('processes:read'),
    ...(live ? { refetchInterval: 15_000 } : {}),
  });
}

/** The instances of one process key, every status, newest first, one page at a time (Load more). */
export function useInstances(definitionKey: string, pageLimit: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useInfiniteQuery({
    queryKey: keys.instances(definitionKey),
    queryFn: ({ pageParam, signal }) => client.listInstances({ definitionKey, cursor: pageParam, limit: pageLimit, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasNext && last.nextCursor !== null ? last.nextCursor : undefined),
    staleTime: 10_000,
  });
}

/** What happened at each step of one instance, as the engine computed it (PRE-027). Read again every 15 s while open. */
export function useInstanceActivities(id: string, enabled: boolean) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.activities(id),
    queryFn: ({ signal }) => client.getInstanceActivities(id, signal),
    enabled,
    refetchInterval: 15_000,
  });
}

/**
 * One Idempotency-Key per user action: the same key while the same request is retried, a new one when the request
 * changes (cockpit DESIGN section 12).
 */
export function useActionKey() {
  const last = useRef<{ request: string; key: string } | undefined>(undefined);
  return (request: string) => {
    if (last.current?.request !== request) last.current = { request, key: crypto.randomUUID() };
    return last.current.key;
  };
}

/** Claim the review task, then read it again with what the caller may now do. */
export function useClaimTask(task: Task) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: () => client.claimTask(task.id, task.lockVersion, keyFor(`claim:${task.id}:${String(task.lockVersion)}`)),
    onSuccess: (claimed) => { queryClient.setQueryData(keys.task(task.id), claimed); },
  });
}

/** Start one enabled activity of an ad-hoc sub-process (#500); the case and its timeline are read again. */
export function useStartAdHocActivity(instance: { id: string; lockVersion: number }) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: ({ scopeTokenId, activityId }: { scopeTokenId: string; activityId: string }) =>
      client.startAdHocActivity(instance.id, instance.lockVersion, scopeTokenId, activityId, keyFor(`ad-hoc:${instance.id}:${String(instance.lockVersion)}:${scopeTokenId}:${activityId}`)),
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: keys.instance(instance.id) }); },
  });
}

/** Complete the review task with the reviewer's answer and reason; the task is read again for its completed state. */
export function useCompleteTask(task: Task) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: (answer: TaskAnswer) => client.completeTask(task.id, task.lockVersion, answer, keyFor(`complete:${task.id}:${String(task.lockVersion)}:${JSON.stringify(answer)}`)),
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: keys.task(task.id) }); },
  });
}

/**
 * Complete the task through its form: `If-Match` is the lock version the form was read at, and one Idempotency-Key
 * serves every retry of the same result, so a double click or a retried request completes once. Settled either way,
 * the task and its form are read again: after a 200 the task is done; after a 409 the screen shows where it is now.
 */
export function useCompleteForm(taskId: string, lockVersion: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: (result: Readonly<Record<string, unknown>>) => client.completeTask(taskId, lockVersion, { result }, keyFor(`complete-form:${taskId}:${String(lockVersion)}:${JSON.stringify(result)}`)),
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.task(taskId) }),
        queryClient.invalidateQueries({ queryKey: [keys.task(taskId)[0], keys.task(taskId)[1], 'processes', 'tasks'] }),
      ]);
    },
  });
}
