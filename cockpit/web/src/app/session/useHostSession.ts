import { useQueries, useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';
import { createHostClient, type HostClient } from '../../api/hostClient';
import { HostCallError, toHostError, type HostError } from '../../api/hostError';
import { loadRegistry, selectHost, type HostRegistry, type RegistryHost, type RegistryProblem } from '../config/registry';

export type ConfigProblem = RegistryProblem | { reason: 'oidc-not-available' };

/** Everything the shell can be in. Exactly one variant at a time; no flag soup. */
export type SessionState =
  | { status: 'loading' }
  | { status: 'config-error'; problem: ConfigProblem }
  | { status: 'host-error'; error: HostError; retry: () => void }
  | { status: 'ready'; registry: HostRegistry; host: RegistryHost; meta: BpmMetaV1; me: BpmMeV1 };

export interface SessionDependencies {
  fetchImpl?: typeof fetch;
}

export const registryQueryKey = ['registry'] as const;

export function useHostSession({ fetchImpl }: SessionDependencies = {}): SessionState {
  const registryQuery = useQuery({
    queryKey: registryQueryKey,
    queryFn: () => loadRegistry(fetchImpl),
    staleTime: Number.POSITIVE_INFINITY,
    retry: false,
  });

  const registry = registryQuery.data?.ok ? registryQuery.data.registry : undefined;
  const host = registry ? selectHost(registry) : undefined;
  const sessionHost = registry?.auth.mode === 'host-session' ? host : undefined;

  const client: HostClient | undefined = useMemo(
    () => (sessionHost ? createHostClient(sessionHost.baseUrl, fetchImpl) : undefined),
    [sessionHost, fetchImpl],
  );

  // Query keys start with the host id, so another host's answer can never show here (DESIGN section 12).
  // These two keys have no tenant on purpose: /meta is host-wide and /me is what tells the cockpit the
  // tenant. Every tenant-scoped query uses the full key [hostId, tenantId, resource, params].
  const [metaQuery, meQuery] = useQueries({
    queries: [
      {
        queryKey: [host?.id ?? '', 'meta'],
        queryFn: ({ signal }: { signal: AbortSignal }) => requireClient(client).getMeta(signal),
        enabled: client !== undefined,
        retry: false,
        staleTime: 60_000,
      },
      {
        queryKey: [host?.id ?? '', 'me'],
        queryFn: ({ signal }: { signal: AbortSignal }) => requireClient(client).getMe(signal),
        enabled: client !== undefined,
        retry: false,
        staleTime: 60_000,
      },
    ],
  });

  if (registryQuery.data && !registryQuery.data.ok) return { status: 'config-error', problem: registryQuery.data.problem };
  if (!registry || !host) return { status: 'loading' };
  if (registry.auth.mode !== 'host-session') return { status: 'config-error', problem: { reason: 'oidc-not-available' } };

  const retry = () => {
    void metaQuery.refetch();
    void meQuery.refetch();
  };
  // /me decides who the user is, so its error wins; nothing of the header is shown on any error.
  if (meQuery.isError) return { status: 'host-error', error: toHostError(meQuery.error), retry };
  if (metaQuery.isError) return { status: 'host-error', error: toHostError(metaQuery.error), retry };
  if (!metaQuery.data || !meQuery.data) return { status: 'loading' };
  return { status: 'ready', registry, host, meta: metaQuery.data, me: meQuery.data };
}

function requireClient(client: HostClient | undefined): HostClient {
  if (!client) throw new HostCallError({ kind: 'unreachable' });
  return client;
}
