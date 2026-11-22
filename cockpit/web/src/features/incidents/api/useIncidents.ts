import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import type { IncidentResolution } from '../../../api/processes/processesClient';
import type { Incident, IncidentStatus } from '../../../api/processes/schemas';
import { useProcessesSession } from '../../processes/api/processesContext';
import { readBpmnGraph } from '../../processes/model/bpmnGraph';

/*
 * Server state of the incident queue (CKP-008). Every key starts with host and tenant (cockpit DESIGN section 12).
 * A command never changes a list before the engine answers; after it, the lists and the incident are read again.
 */

/** The page size of each tab; the tab says "50+" when the engine has more. */
export const INCIDENT_LIMIT = 50;

function useKeys() {
  const { hostId, tenant } = useProcessesSession();
  const base = [hostId, tenant, 'processes', 'incidents'] as const;
  return {
    all: base,
    list: (status: IncidentStatus) => [...base, 'list', status] as const,
    incident: (id: string) => [...base, 'one', id] as const,
  };
}

/** The refresh period with plus or minus 10 % jitter (cockpit DESIGN section 8); paused while the tab is hidden. */
function jittered(seconds: number): () => number {
  return () => Math.round(seconds * 1000 * (0.9 + Math.random() * 0.2));
}

/** One status's first page, newest first. */
export function useIncidentList(status: IncidentStatus, refreshSeconds: number) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({
    queryKey: keys.list(status),
    queryFn: ({ signal }) => client.listIncidents({ status, limit: INCIDENT_LIMIT, signal }),
    refetchInterval: jittered(refreshSeconds),
    staleTime: 5_000,
  });
}

/** The selected incident with the actions this user may take now and its lockVersion for If-Match. */
export function useIncident(id: string) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  return useQuery({ queryKey: keys.incident(id), queryFn: ({ signal }) => client.getIncident(id, signal), staleTime: 2_000 });
}

/** The case an incident stopped: its business key and process, for the facts beside the incident. */
export function useIncidentCase(instanceId: string) {
  const { client, hostId, tenant } = useProcessesSession();
  return useQuery({
    queryKey: [hostId, tenant, 'processes', 'instance', instanceId] as const,
    queryFn: ({ signal }) => client.getInstance(instanceId, signal),
    staleTime: 30_000,
    retry: false,
  });
}

/**
 * The steps of the incidents by the names their process gives them, in the tenant's language: the incident names
 * only its step id, so each case and its definition's diagram are read (the same cached reads as the case screen).
 * Until they arrive, or when they cannot be read, a step reads as its id in words.
 */
export function useStepNames(incidents: readonly Pick<Incident, 'instanceId' | 'nodeId'>[]): (incident: Pick<Incident, 'instanceId' | 'nodeId'>) => string | undefined {
  const { client, hostId, tenant } = useProcessesSession();
  const ids = [...new Set(incidents.map((item) => item.instanceId))];
  const cases = useQueries({
    queries: ids.map((id) => ({
      queryKey: [hostId, tenant, 'processes', 'instance', id] as const,
      queryFn: ({ signal }: { signal: AbortSignal }) => client.getInstance(id, signal),
      staleTime: 30_000,
      retry: false,
    })),
  });
  const versionOf = new Map(ids.map((id, index) => {
    const data = cases[index]?.data;
    return [id, data !== undefined ? `${data.definitionKey}\u0000${String(data.definitionVersion)}` : undefined] as const;
  }));
  const versions = [...new Set([...versionOf.values()].filter((value): value is string => value !== undefined))];
  const graphs = useQueries({
    queries: versions.map((value) => {
      const [key = '', version = '0'] = value.split('\u0000');
      return {
        queryKey: [hostId, tenant, 'processes', 'graph', key, Number(version)] as const,
        queryFn: async ({ signal }: { signal: AbortSignal }) => readBpmnGraph(await client.exportBpmn(key, Number(version), signal)),
        staleTime: Infinity,
        retry: false,
      };
    }),
  });
  return (incident) => {
    const version = versionOf.get(incident.instanceId);
    const graph = version !== undefined ? graphs[versions.indexOf(version)]?.data : undefined;
    return graph?.nodes.find((node) => node.id === incident.nodeId)?.name;
  };
}

export type IncidentCommand =
  | { kind: 'retry'; reason: string }
  | { kind: 'resolve'; resolution: IncidentResolution }
  | { kind: 'approve'; comment: string }
  | { kind: 'reject'; reason: string };

/**
 * Retry, propose a result, or approve or reject a proposal, on the incident as it was read. One Idempotency-Key per
 * user action, kept while the same request is retried. Settled either way, the lists and the incident are read again.
 */
export function useIncidentCommand(incident: Incident) {
  const { client } = useProcessesSession();
  const keys = useKeys();
  const queryClient = useQueryClient();
  const last = useRef<{ request: string; key: string } | undefined>(undefined);
  return useMutation({
    mutationFn: (command: IncidentCommand) => {
      const request = JSON.stringify([command, incident.id, incident.lockVersion]);
      if (last.current?.request !== request) last.current = { request, key: crypto.randomUUID() };
      const key = last.current.key;
      switch (command.kind) {
        case 'retry':
          return client.retryIncident(incident.id, incident.lockVersion, command.reason, key);
        case 'resolve':
          return client.resolveIncident(incident.id, incident.lockVersion, command.resolution, key);
        case 'approve':
          return client.approveResolution(incident.id, incident.lockVersion, command.comment, key);
        case 'reject':
          return client.rejectResolution(incident.id, incident.lockVersion, command.reason, key);
      }
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: keys.all });
    },
  });
}
