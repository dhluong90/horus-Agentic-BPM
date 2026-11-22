import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { MigrationPlanRequest } from '../../../../api/processes/processesClient';
import type { MigrationExecution, MigrationOutcome, MigrationStatus } from '../../../../api/processes/schemas';
import { useProcessesSession } from '../../api/processesContext';
import { useActionKey, useKeys } from '../../api/useProcesses';

/*
 * Server state of the migration screens. Every command waits for the engine's answer and the screen then shows the
 * answer as stored; nothing is shown as checked, proposed or approved before the engine says so. A command keeps one
 * Idempotency-Key while the same request is retried.
 */

/** Statuses in which a runner may still be moving cases: the screen reads the progress again until it ends. */
const LIVE: readonly MigrationStatus[] = ['APPROVED', 'RUNNING'];

export function useMigrationPlan(id: string) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: [...keys.migration(id), 'plan'], queryFn: ({ signal }) => client.getMigrationPlan(id, signal) });
}

/** The proposal, the approval and the progress; read every `seconds` while a runner is at work. */
export function useMigrationExecution(id: string, seconds = 3) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: [...keys.migration(id), 'execution'],
    queryFn: ({ signal }) => client.getMigrationExecution(id, signal),
    refetchInterval: (query) => (query.state.data !== undefined && LIVE.includes(query.state.data.status) ? seconds * 1000 : false),
  });
}

/** The dry run. It only reads, so it takes no key and may be repeated; its answer is held by the mutation. */
export function useCheckMigration(id: string) {
  const { client } = useProcessesSession();
  return useMutation({ mutationFn: () => client.checkMigrationPlan(id) });
}

export function useCreateMigrationPlan() {
  const { client } = useProcessesSession();
  const keyFor = useActionKey();
  return useMutation({ mutationFn: (plan: MigrationPlanRequest) => client.createMigrationPlan(plan, keyFor(`plan:${JSON.stringify(plan)}`)) });
}

/** Run a command on the plan, then read the plan and its progress again. */
function useCommand<TArgs>(id: string, name: string, send: (args: TArgs, idempotencyKey: string) => Promise<MigrationExecution>) {
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: (args: TArgs) => send(args, keyFor(`${name}:${id}:${JSON.stringify(args)}`)),
    onSuccess: async (stored) => {
      queryClient.setQueryData([...keys.migration(id), 'execution'], stored);
      await queryClient.invalidateQueries({ queryKey: [...keys.migration(id), 'plan'] });
    },
  });
}

export function useProposeMigration(id: string) {
  const { client } = useProcessesSession();
  return useCommand<{ reason: string }>(id, 'propose', ({ reason }, key) => client.proposeMigration(id, reason, key));
}

/** Approve names the proposal the approver was shown (its lockVersion), never one read again at decision time. */
export function useApproveMigration(id: string) {
  const { client } = useProcessesSession();
  return useCommand<{ lockVersion: number }>(id, 'approve', ({ lockVersion }, key) => client.approveMigration(id, lockVersion, key));
}

export function useRejectMigration(id: string) {
  const { client } = useProcessesSession();
  return useCommand<{ lockVersion: number; reason: string }>(id, 'reject', ({ lockVersion, reason }, key) => client.rejectMigration(id, lockVersion, reason, key));
}

interface Cursor { outcome: 'FAILED' | 'SKIPPED'; after: string | undefined }
const FIRST: Cursor = { outcome: 'FAILED', after: undefined };

/** Failed cases first, then skipped ones, a bounded page at a time. */
export function useNotMoved(id: string, enabled: boolean, limit: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useInfiniteQuery({
    queryKey: keys.migrationOutcomes(id),
    enabled,
    initialPageParam: FIRST,
    queryFn: async ({ pageParam, signal }) => ({
      outcome: pageParam.outcome,
      page: await client.listMigrationInstances({ id, outcome: pageParam.outcome, after: pageParam.after, limit, signal }),
    }),
    getNextPageParam: (last): Cursor | undefined => {
      if (last.page.nextAfter !== null && last.page.nextAfter !== undefined) return { outcome: last.outcome, after: last.page.nextAfter };
      return last.outcome === 'FAILED' ? { outcome: 'SKIPPED', after: undefined } : undefined;
    },
  });
}

export type NotMovedCase = MigrationOutcome;
