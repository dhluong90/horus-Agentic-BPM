import { useQueries } from '@tanstack/react-query';
import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';
import { createHostClient, type HostClient } from '../../api/hostClient';
import { useI18n } from '../../i18n/I18nProvider';
import { authorizedFetch } from '../auth/authorizedFetch';
import type { TokenSession } from '../auth/oidc';
import { enabledHosts, tokenScope } from '../auth/standalone';
import { hostLabel, type HostRegistry } from '../config/registry';
import { checkVersion } from '../session/versionSupport';
import { tenantFetch } from '../tenant/tenantFetch';

/*
 * The hosts a merged list reads (cockpit DESIGN section 8). Each enabled host is read like the one on screen: its
 * token, then /meta and /me. A host that cannot be read is listed as such, so a merged list can say it is incomplete;
 * the other hosts are not held back. Query keys are the ones the single-host probe uses, so a host already on screen
 * is not read twice.
 */

export type FederatedHost =
  | { state: 'probing'; id: string; label: string }
  | { state: 'failed'; id: string; label: string }
  | { state: 'unsupported'; id: string; label: string }
  | { state: 'ready'; id: string; label: string; baseUrl: string; me: BpmMeV1; meta: BpmMetaV1; fetchImpl: typeof fetch };

export interface Federation {
  /** The host on screen. */
  currentId: string;
  /** Every enabled host, in registry order; empty until a merged list is opened. */
  hosts: readonly FederatedHost[];
  /** How long a merged list waits for one host (`refresh.hostTimeoutSeconds`). */
  timeoutMs: number;
}

const FederationContext = createContext<Federation | undefined>(undefined);

export function FederationProvider({ value, children }: { value: Federation | undefined; children: ReactNode }) {
  return <FederationContext.Provider value={value}>{children}</FederationContext.Provider>;
}

/** The federation, or undefined when this cockpit has one host (embedded, or one registered): merged lists are not offered. */
export function useFederation(): Federation | undefined {
  return useContext(FederationContext);
}

export function useFederatedHosts(registry: HostRegistry, session: TokenSession, baseFetch: typeof fetch, tenants: Readonly<Record<string, string>>, enabled: boolean): readonly FederatedHost[] {
  const { locale } = useI18n();
  const hosts = enabledHosts(registry);
  const fetches = useMemo(
    () => hosts.map((host) => {
      const scope = tokenScope(registry, host);
      return tenantFetch(authorizedFetch(baseFetch, () => session.accessToken(scope)), tenants[host.id], () => undefined);
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `hosts` is derived from `registry`
    [registry, session, baseFetch, tenants],
  );
  const clients = useMemo(() => hosts.map((host, index) => createHostClient(host.baseUrl, fetches[index])),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `hosts` is derived from `registry`
    [registry, fetches]);
  const metas = useQueries({
    queries: hosts.map((host, index) => ({ queryKey: [host.id, 'meta'], queryFn: ({ signal }: { signal: AbortSignal }) => (clients[index] as HostClient).getMeta(signal), retry: false, staleTime: 60_000, enabled })),
  });
  const mes = useQueries({
    queries: hosts.map((host, index) => ({ queryKey: [host.id, 'me'], queryFn: ({ signal }: { signal: AbortSignal }) => (clients[index] as HostClient).getMe(signal), retry: false, staleTime: 60_000, enabled })),
  });
  return hosts.map((host, index): FederatedHost => {
    const label = hostLabel(host.label, locale) || host.id;
    const meta = metas[index];
    const me = mes[index];
    if (meta?.isError === true || me?.isError === true) return { state: 'failed', id: host.id, label };
    if (meta?.data === undefined || me?.data === undefined) return { state: 'probing', id: host.id, label };
    if (!checkVersion(meta.data).supported) return { state: 'unsupported', id: host.id, label };
    return { state: 'ready', id: host.id, label, baseUrl: host.baseUrl, me: me.data, meta: meta.data, fetchImpl: fetches[index] as typeof fetch };
  });
}
