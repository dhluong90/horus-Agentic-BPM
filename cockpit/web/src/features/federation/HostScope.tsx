import { createContext, useCallback, useContext, type ReactNode } from 'react';
import { formatRoute, withHost, type Route } from '../../app/route';
import type { FederatedHost } from '../../app/federation/federation';
import { PeopleProvider } from '../../app/people';
import { DisplayNamesProvider } from '../processes/api/displayNames';
import { ProcessesProvider } from '../processes/api/processesContext';

/*
 * A row of a merged list belongs to one host. Everything under it (the inspector, its commands, its links) runs as that
 * host: its client and token, its tenant, its permissions, and its own address prefix. This is how an action
 * reaches the right host (CKP-020) without any screen knowing about hosts.
 */

const HostLinkContext = createContext<string | undefined>(undefined);

/** The link to a route on the host a merged row belongs to; on a single-host screen, the plain link. */
export function useHostLink(): (route: Route) => string {
  const hostId = useContext(HostLinkContext);
  return useCallback((route) => withHost(formatRoute(route), hostId), [hostId]);
}

export function HostScope({ host, children }: { host: Extract<FederatedHost, { state: 'ready' }>; children: ReactNode }) {
  return (
    <ProcessesProvider baseUrl={host.baseUrl} hostId={host.id} me={host.me} fetchImpl={host.fetchImpl}>
      <PeopleProvider me={host.me.user}>
        <DisplayNamesProvider>
          <HostLinkContext.Provider value={host.id}>{children}</HostLinkContext.Provider>
        </DisplayNamesProvider>
      </PeopleProvider>
    </ProcessesProvider>
  );
}
