import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { BpmMeV1 } from '../../../api/generated/bpm-me.v1';
import { createProcessesClient, type ProcessesClient } from '../../../api/processes/processesClient';

/** Who is calling which host's Process Engine: the client, and what /me says the user may do. */
export interface ProcessesSession {
  client: ProcessesClient;
  hostId: string;
  tenant: string;
  userId: string;
  can: (permission: string) => boolean;
}

const ProcessesContext = createContext<ProcessesSession | undefined>(undefined);

export function ProcessesProvider({ baseUrl, hostId, me, fetchImpl, children }: {
  baseUrl: string;
  hostId: string;
  me: BpmMeV1;
  fetchImpl?: typeof fetch | undefined;
  children: ReactNode;
}) {
  const { permissions } = me;
  const tenant = me.tenant.current;
  const userId = me.user.id;
  const value = useMemo<ProcessesSession>(() => {
    const granted = new Set(permissions);
    return { client: createProcessesClient(baseUrl, fetchImpl), hostId, tenant, userId, can: (permission) => granted.has(permission) };
  }, [baseUrl, hostId, permissions, tenant, userId, fetchImpl]);
  return <ProcessesContext.Provider value={value}>{children}</ProcessesContext.Provider>;
}

export function useProcessesSession(): ProcessesSession {
  const value = useContext(ProcessesContext);
  if (!value) throw new Error('useProcessesSession must be used inside ProcessesProvider');
  return value;
}
