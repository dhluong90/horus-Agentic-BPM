import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import type { TaskHandOver } from '../../../api/processes/processesClient';
import type { Task } from '../../../api/processes/schemas';
import { useProcessesSession } from '../../processes/api/processesContext';
import { queryOf, type QueueView } from '../model/queue';

/*
 * Server state of the task queue. Every key starts with host and tenant (cockpit DESIGN section 12). A command never
 * changes a list before the engine answers 200; after it, the lists and the task are read again.
 */

/** The page size of each tab; the header says "50+" when the engine has more. */
export const QUEUE_LIMIT = 50;

function useKeys() {
  const { hostId, tenant } = useProcessesSession();
  const base = [hostId, tenant, 'processes', 'tasks'] as const;
  return {
    all: base,
    view: (view: QueueView, overdue: boolean) => [...base, 'view', view, overdue ? 'overdue' : 'all'] as const,
    task: (id: string) => [hostId, tenant, 'processes', 'task', id] as const,
  };
}

/**
 * The refresh period with plus or minus 10 % jitter, drawn again before each refresh (cockpit DESIGN section 8).
 * TanStack Query pauses it while the browser tab is hidden.
 */
function jittered(seconds: number): () => number {
  return () => Math.round(seconds * 1000 * (0.9 + Math.random() * 0.2));
}

/**
 * One tab's first page, in the engine's due-time order. `overdue` adds `slaBreached=true` (CKP-034, CKP-050): which
 * tasks are overdue is the engine's answer, never worked out in the browser.
 */
export function useQueue(view: QueueView, enabled: boolean, refreshSeconds: number, overdue = false) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.view(view, overdue),
    queryFn: ({ signal }) => client.listTasks({ ...queryOf(view), ...(overdue ? { slaBreached: true } : {}), limit: QUEUE_LIMIT, signal }),
    enabled,
    refetchInterval: jittered(refreshSeconds),
    staleTime: 5_000,
  });
}

/** The selected task, with the actions this user may take now and its lockVersion for If-Match. */
export function useQueueTask(id: string | undefined) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.task(id ?? ''), queryFn: ({ signal }) => client.getTask(id ?? '', signal), enabled: id !== undefined, staleTime: 2_000 });
}

/** One Idempotency-Key per user action: kept while the same request is retried, new when the request changes. */
function useActionKey() {
  const last = useRef<{ request: string; key: string } | undefined>(undefined);
  return (request: string) => {
    if (last.current?.request !== request) last.current = { request, key: crypto.randomUUID() };
    return last.current.key;
  };
}

export type QueueCommand =
  | { kind: 'claim' }
  | { kind: 'unclaim' }
  | { kind: 'reassign'; handOver: TaskHandOver }
  | { kind: 'delegate'; handOver: TaskHandOver };

/**
 * Claim, release, reassign or delegate the task as it was read. Settled either way, the lists and the task are read
 * again: after a 200 the task has moved; after a 409 the screen shows where it is now.
 */
export function useQueueCommand(task: Task) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: (command: QueueCommand) => {
      const request = `${command.kind}:${task.id}:${String(task.lockVersion)}:${'handOver' in command ? JSON.stringify(command.handOver) : ''}`;
      const key = keyFor(request);
      switch (command.kind) {
        case 'claim':
          return client.claimTask(task.id, task.lockVersion, key);
        case 'unclaim':
          return client.unclaimTask(task.id, task.lockVersion, key);
        case 'reassign':
          return client.reassignTask(task.id, task.lockVersion, command.handOver, key);
        case 'delegate':
          return client.delegateTask(task.id, task.lockVersion, command.handOver, key);
      }
    },
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.all }),
        queryClient.invalidateQueries({ queryKey: keys.task(task.id) }),
      ]);
    },
  });
}
