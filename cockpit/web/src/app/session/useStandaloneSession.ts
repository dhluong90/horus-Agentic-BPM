import { useQueries } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';
import { createHostClient } from '../../api/hostClient';
import { toHostError, type HostError } from '../../api/hostError';
import { authorizedFetch } from '../auth/authorizedFetch';
import { createOidcClient, SignInError, type OidcClient, type OidcDependencies, type SignInProblem, type TokenSession } from '../auth/oidc';
import { enabledHosts, isCallbackPath, oidcSettings, tokenScope } from '../auth/standalone';
import { cockpitBase, type HostRegistry, type RegistryHost } from '../config/registry';
import { linkedHost, parseRoute, splitHost, withHost } from '../route';
import { checkVersion } from './versionSupport';

/** Sign-in of the whole cockpit: once per page load, before any host is called. */
export type SignInState =
  | { status: 'signing-in' }
  | { status: 'failed'; problem: SignInProblem; retry: () => void }
  | { status: 'signed-in'; session: TokenSession };

/** One host, the one on screen (cockpit DESIGN section 5.3). Exactly one variant at a time. */
export type HostState =
  | { status: 'probing'; host: RegistryHost }
  | { status: 'host-error'; host: RegistryHost; error: HostError; retry: () => void }
  | { status: 'unsupported'; host: RegistryHost; engineVersion: string; lines: string[] }
  | { status: 'ready'; host: RegistryHost; meta: BpmMetaV1; me: BpmMeV1; fetchImpl: typeof fetch };

/** Which host the address names: one host, or none yet because a decision link must say which (PD-108). */
export type HostChoice = { kind: 'host'; host: RegistryHost } | { kind: 'choose' };

function subscribe(onChange: () => void): () => void {
  window.addEventListener('hashchange', onChange);
  return () => {
    window.removeEventListener('hashchange', onChange);
  };
}

function useHash(): string {
  return useSyncExternalStore(subscribe, () => window.location.hash, () => '');
}

/**
 * Signs in once: finishes the callback when the page is the sign-in callback, otherwise sends the browser to
 * the identity provider with the route asked for. A reload signs in again through the provider's session,
 * since tokens are never stored (CKP-013).
 */
export function useSignIn(registry: HostRegistry, dependencies: OidcDependencies): { state: SignInState; client: OidcClient } {
  const { pathname, origin, search } = window.location;
  const redirectPath = registry.auth.redirectPath;
  const root = cockpitBase().pathname;
  const client = useMemo(() => createOidcClient(oidcSettings(registry, origin, root), dependencies), [registry, origin, root, dependencies]);
  const [state, setState] = useState<SignInState>({ status: 'signing-in' });
  // StrictMode runs effects twice; the callback's code may be redeemed once only.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const fail = (cause: unknown) => {
      const problem: SignInProblem = cause instanceof SignInError ? cause.problem : { reason: 'provider-unreachable' };
      setState({
        status: 'failed',
        problem,
        retry: () => {
          setState({ status: 'signing-in' });
          // Start again from the route the user asked for, never from the used callback.
          void client.beginSignIn(window.location.hash).catch(fail);
        },
      });
    };
    if (isCallbackPath(pathname, redirectPath)) {
      void client.completeSignIn(new URLSearchParams(search)).then(({ returnTo, session }) => {
        // The address becomes the cockpit's own, without the code and state.
        window.history.replaceState(null, '', `${root}${returnTo}`);
        window.dispatchEvent(new HashChangeEvent('hashchange'));
        setState({ status: 'signed-in', session });
      }, fail);
    } else {
      void client.beginSignIn(window.location.hash).catch(fail);
    }
  }, [client, pathname, redirectPath, root, search]);

  return { state, client };
}

/** The host the address names, a `?host=` decision link, or the first enabled host. */
export function chooseHost(registry: HostRegistry, hash: string): HostChoice {
  const hosts = enabledHosts(registry);
  const { hostId, rest } = splitHost(hash);
  const named = hostId ?? linkedHost(rest);
  const found = hosts.find((host) => host.id === named);
  if (found) return { kind: 'host', host: found };
  // PD-108: a decision link without its host, while several hosts are registered, asks which one.
  if (named === undefined && hosts.length > 1 && parseRoute(rest).name === 'decision') return { kind: 'choose' };
  const first = hosts[0];
  return first ? { kind: 'host', host: first } : { kind: 'choose' };
}

/** Keeps the host id in the address: a link without it gets the current host's prefix (CKP-015). */
export function useHostInAddress(registry: HostRegistry): HostChoice {
  const hash = useHash();
  const choice = chooseHost(registry, hash);
  const hostId = choice.kind === 'host' ? choice.host.id : undefined;
  useEffect(() => {
    if (hostId === undefined) return;
    const { hostId: inAddress, rest } = splitHost(hash);
    if (inAddress === hostId) return;
    // Drop the ?host= of a decision link once it has chosen the host; nothing else of the link is kept.
    const cleaned = rest.replace(/([?&])host=[^&]*&?/, '$1').replace(/[?&]$/, '');
    window.history.replaceState(null, '', `${window.location.pathname}${withHost(cleaned === '' ? '#/' : cleaned, hostId)}`);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  }, [hash, hostId]);
  return choice;
}

/**
 * Reads one host for the signed-in user: its token, then /meta and /me with it, then the version check.
 * Query keys start with the host id, so another host's answer never shows here.
 */
export function useHostProbe(registry: HostRegistry, host: RegistryHost, session: TokenSession, baseFetch: typeof fetch): HostState {
  const scope = tokenScope(registry, host);
  const fetchImpl = useMemo(() => authorizedFetch(baseFetch, () => session.accessToken(scope)), [baseFetch, session, scope]);
  const client = useMemo(() => createHostClient(host.baseUrl, fetchImpl), [host.baseUrl, fetchImpl]);
  const [metaQuery, meQuery] = useQueries({
    queries: [
      { queryKey: [host.id, 'meta'], queryFn: ({ signal }: { signal: AbortSignal }) => client.getMeta(signal), retry: false, staleTime: 60_000 },
      { queryKey: [host.id, 'me'], queryFn: ({ signal }: { signal: AbortSignal }) => client.getMe(signal), retry: false, staleTime: 60_000 },
    ],
  });
  const retry = useCallback(() => {
    void metaQuery.refetch();
    void meQuery.refetch();
  }, [metaQuery, meQuery]);
  if (meQuery.isError) return { status: 'host-error', host, error: toHostError(meQuery.error), retry };
  if (metaQuery.isError) return { status: 'host-error', host, error: toHostError(metaQuery.error), retry };
  if (!metaQuery.data || !meQuery.data) return { status: 'probing', host };
  const version = checkVersion(metaQuery.data);
  if (!version.supported) return { status: 'unsupported', host, engineVersion: version.engineVersion, lines: version.lines };
  return { status: 'ready', host, meta: metaQuery.data, me: meQuery.data, fetchImpl };
}
