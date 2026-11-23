import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { CalendarChange } from '../../../api/processes/schemas';
import { useProcessesSession } from '../api/processesContext';
import { useActionKey, useKeys } from '../api/useProcesses';

/** The pending changes of the tenant's business calendars, newest first, up to the engine's page limit (PRE-181). */
export function useCalendarChanges(pageLimit: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: [...keys.calendarChanges, pageLimit], queryFn: ({ signal }) => client.listCalendarChanges({ limit: pageLimit, signal }) });
}

export function useCalendarChange(id: string) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.calendarChange(id), queryFn: ({ signal }) => client.getCalendarChange(id, signal) });
}

/**
 * Approve or reject. Approve names the change hash of the change the approver was shown, never one read again at
 * decision time (PD-139). The screen shows the engine's answer, and the approvals queue is read again.
 */
export function useDecideCalendarChange(change: CalendarChange, hash: string) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: ({ approve, comment }: { approve: boolean; comment: string }) => {
      const key = keyFor(`${approve ? 'approve' : 'reject'}:${change.id}:${hash}:${comment}`);
      return approve ? client.approveCalendarChange(change.id, { comment, expectedChangeHash: hash }, key) : client.rejectCalendarChange(change.id, comment, key);
    },
    onSuccess: async (stored) => {
      queryClient.setQueryData(keys.calendarChange(stored.id), stored);
      await Promise.all([queryClient.invalidateQueries({ queryKey: keys.calendarChanges }), queryClient.invalidateQueries({ queryKey: keys.approvals })]);
    },
  });
}
