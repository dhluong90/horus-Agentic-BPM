import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import type { TaskAnswer } from '../../../api/processes/processesClient';
import type { DefinitionSummary, Task } from '../../../api/processes/schemas';
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

export function useDefinitionVersion(key: string, version: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.version(key, version), queryFn: ({ signal }) => client.getVersion(key, version, signal), staleTime: 60_000 });
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
