import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { CalendarChange, CalendarValue } from '../../../api/processes/schemas';
import { useProcessesSession } from '../api/processesContext';
import { useActionKey, useKeys } from '../api/useProcesses';

/** The changes of the tenant's business calendars with one status (pending when absent), newest first, up to the engine's page limit (PRE-181). */
export function useCalendarChanges(pageLimit: number, status: CalendarChange['status'] = 'PENDING') {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: [...keys.calendarChanges, pageLimit, status], queryFn: ({ signal }) => client.listCalendarChanges({ limit: pageLimit, status, signal }) });
}

/** The tenant's calendars by key, up to the engine's page limit (PRE-181). */
export function useCalendars(pageLimit: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: [...keys.calendars, pageLimit], queryFn: ({ signal }) => client.listCalendars({ limit: pageLimit, signal }) });
}

/** One calendar in force, or null when it does not exist yet (a new one is created by its first change). */
export function useCalendar(key: string) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.calendar(key), queryFn: ({ signal }) => client.getCalendar(key, signal) });
}

/**
 * Propose the whole calendar as it should be, against the version read (`0` for a new calendar). The change waits for a
 * second person; nothing on screen says it applies before the engine's answer (PD-131).
 */
export function useProposeCalendarChange(key: string, baseVersion: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  const queryClient = useQueryClient();
  const keyFor = useActionKey();
  return useMutation({
    mutationFn: (proposal: { calendar: CalendarValue; reason: string }) => client.proposeCalendarChange(key, baseVersion, proposal, keyFor(`propose:${key}:${String(baseVersion)}:${JSON.stringify(proposal)}`)),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.calendars }),
        queryClient.invalidateQueries({ queryKey: keys.calendarChanges }),
        queryClient.invalidateQueries({ queryKey: keys.approvals }),
      ]);
    },
  });
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
