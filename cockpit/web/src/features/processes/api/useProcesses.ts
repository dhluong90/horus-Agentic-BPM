import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
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

function useKeys() {
  const { hostId, tenant } = useProcessesSession();
  const base = [hostId, tenant, 'processes'] as const;
  return {
    definitions: [...base, 'definitions'] as const,
    version: (key: string, version: number) => [...base, 'definition', key, version] as const,
    graph: (key: string, version: number) => [...base, 'graph', key, version] as const,
    diff: (key: string, version: number) => [...base, 'diff', key, version] as const,
    task: (id: string) => [...base, 'task', id] as const,
    instance: (id: string) => [...base, 'instance', id] as const,
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
      await queryClient.invalidateQueries({ queryKey: keys.definitions });
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

/** The task's instance, for who started it. A reviewer without processes:read gets no answer; four eyes then rests on the engine. */
export function useInstance(id: string | undefined) {
  const { client, can } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.instance(id ?? ''),
    queryFn: ({ signal }) => client.getInstance(id ?? '', signal),
    enabled: id !== undefined && can('processes:read'),
  });
}

/**
 * One Idempotency-Key per user action: the same key while the same request is retried, a new one when the request
 * changes (cockpit DESIGN section 12).
 */
function useActionKey() {
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
