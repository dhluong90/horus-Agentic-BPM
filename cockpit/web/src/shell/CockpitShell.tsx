import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, type ReactNode } from 'react';
import type { BpmMeV1 } from '../api/generated/bpm-me.v1';
import { browserDependencies, type OidcDependencies, type TokenSession } from '../app/auth/oidc';
import { enabledHosts } from '../app/auth/standalone';
import { hostLabel, loadRegistry, type HostRegistry, type RegistryHost } from '../app/config/registry';
import { splitHost, withHost } from '../app/route';
import { registryQueryKey, useHostSession, type SessionDependencies } from '../app/session/useHostSession';
import { useHostInAddress, useHostProbe, useSignIn } from '../app/session/useStandaloneSession';
import { HostPicker } from '../components/HostPicker';
import { capabilities, SessionHeader, type SessionSummary } from '../components/SessionHeader';
import { ConfigErrorPanel, HostChooserPanel, HostErrorPanel, LoadingPanel, SignInFailedPanel, SigningInPanel, UnsupportedVersionPanel } from '../components/StatePanels';
import { useI18n } from '../i18n/I18nProvider';
import { AppFrame } from './AppFrame';
import { Breadcrumb } from './Breadcrumb';

export interface ShellDependencies extends SessionDependencies {
  /** Standalone sign-in; the browser's own by default. */
  oidc?: OidcDependencies;
}

function summary(host: RegistryHost, me: BpmMeV1, label: string): SessionSummary {
  return {
    hostLabel: label,
    environment: host.environment,
    tenant: me.tenant.current,
    userName: me.user.displayName ?? me.user.id,
    userId: me.user.id,
    capabilities: capabilities(me.permissions),
    baseUrl: host.baseUrl,
  };
}

/** The registry's default language applies until the user picks one. */
function useDefaultLocale(registry: HostRegistry | undefined) {
  const { locale, chosen, setLocale } = useI18n();
  const defaultLocale = registry?.ui?.defaultLocale;
  useEffect(() => {
    if (!chosen && defaultLocale !== undefined && defaultLocale !== locale) setLocale(defaultLocale, { remember: false });
  }, [chosen, defaultLocale, locale, setLocale]);
}

/** Loading and error states fill the page; the menu appears once the host has said who the user is. */
function Page({ header, children }: { header: ReactNode; children: ReactNode }) {
  return (
    <div className="app">
      {header}
      <main id="main" className="main" tabIndex={-1}>
        {children}
      </main>
    </div>
  );
}

export function CockpitShell(dependencies: ShellDependencies) {
  const { fetchImpl } = dependencies;
  const registryQuery = useQuery({
    queryKey: registryQueryKey,
    queryFn: () => loadRegistry(fetchImpl),
    staleTime: Number.POSITIVE_INFINITY,
    retry: false,
  });
  const result = registryQuery.data;
  useDefaultLocale(result?.ok ? result.registry : undefined);

  if (result === undefined) return <Page header={<SessionHeader session={undefined} banner={undefined} />}><LoadingPanel /></Page>;
  if (!result.ok) return <Page header={<SessionHeader session={undefined} banner={undefined} />}><ConfigErrorPanel problem={result.problem} /></Page>;
  return result.registry.auth.mode === 'oidc'
    ? <StandaloneShell registry={result.registry} dependencies={dependencies} />
    : <EmbeddedShell registry={result.registry} dependencies={dependencies} />;
}

/** Embedded mode: one host, on the host's own session (CKP-001). */
function EmbeddedShell({ registry, dependencies }: { registry: HostRegistry; dependencies: ShellDependencies }) {
  const state = useHostSession(registry, dependencies);
  const { locale } = useI18n();
  const banner = registry.ui?.environmentBanner;
  if (state.status !== 'ready') {
    return (
      <Page header={<SessionHeader session={undefined} banner={banner} />}>
        {state.status === 'loading' ? <LoadingPanel /> : <HostErrorPanel error={state.error} onRetry={state.retry} />}
      </Page>
    );
  }
  const label = hostLabel(state.host.label, locale) || state.meta.hostId || state.host.id;
  return (
    <div className="app">
      <SessionHeader session={summary(state.host, state.me, label)} banner={banner} breadcrumb={<Breadcrumb />} />
      <AppFrame host={state.host} meta={state.meta} me={state.me} fetchImpl={dependencies.fetchImpl} />
    </div>
  );
}

/** Standalone mode: one sign-in, every registered host one at a time, the host id in the address (CKP-012 to CKP-015). */
function StandaloneShell({ registry, dependencies }: { registry: HostRegistry; dependencies: ShellDependencies }) {
  const oidc = useMemo(() => dependencies.oidc ?? browserDependencies(), [dependencies.oidc]);
  const { state, client } = useSignIn(registry, oidc);
  const banner = registry.ui?.environmentBanner;
  if (state.status !== 'signed-in') {
    return (
      <Page header={<SessionHeader session={undefined} banner={banner} />}>
        {state.status === 'signing-in' ? <SigningInPanel /> : <SignInFailedPanel problem={state.problem} onRetry={state.retry} />}
      </Page>
    );
  }
  const session = state.session;
  const signOut = async () => {
    const url = await client.signOutUrl(session);
    session.clear();
    window.location.assign(url);
    return true;
  };
  return <SignedIn registry={registry} session={session} baseFetch={dependencies.fetchImpl ?? fetch} signOut={signOut} />;
}

interface SignedInProps {
  registry: HostRegistry;
  session: TokenSession;
  baseFetch: typeof fetch;
  signOut: () => Promise<boolean>;
}

function SignedIn(props: SignedInProps) {
  const { registry } = props;
  const choice = useHostInAddress(registry);
  const { locale } = useI18n();
  if (choice.kind === 'choose') {
    const rest = splitHost(window.location.hash).rest;
    const hosts = enabledHosts(registry).map((host) => ({ id: host.id, label: hostLabel(host.label, locale) || host.id, href: withHost(rest, host.id) }));
    return (
      <Page header={<SessionHeader session={undefined} banner={registry.ui?.environmentBanner} />}>
        <HostChooserPanel hosts={hosts} />
      </Page>
    );
  }
  // A new host is a new subtree: nothing of the previous host's screens or drafts survives the switch.
  return <HostScreens key={choice.host.id} host={choice.host} {...props} />;
}

function HostScreens({ registry, host, session, baseFetch, signOut }: SignedInProps & { host: RegistryHost }) {
  const state = useHostProbe(registry, host, session, baseFetch);
  const { locale } = useI18n();
  const banner = registry.ui?.environmentBanner;
  const label = hostLabel(host.label, locale) || host.id;
  const ready = state.status === 'ready';
  const picker = <HostPicker hosts={enabledHosts(registry)} current={host} tenant={ready ? state.me.tenant.current : undefined} ready={ready} />;
  if (state.status !== 'ready') {
    return (
      <Page header={<SessionHeader session={undefined} banner={banner} service={picker} />}>
        {state.status === 'probing' ? <LoadingPanel /> : null}
        {state.status === 'host-error' ? <HostErrorPanel error={state.error} onRetry={state.retry} /> : null}
        {state.status === 'unsupported' ? <UnsupportedVersionPanel hostName={label} engineVersion={state.engineVersion} lines={state.lines} /> : null}
      </Page>
    );
  }
  return (
    <div className="app">
      <SessionHeader session={{ ...summary(host, state.me, label), signOut }} banner={banner} breadcrumb={<Breadcrumb />} service={picker} />
      <AppFrame host={host} meta={state.meta} me={state.me} fetchImpl={state.fetchImpl} />
    </div>
  );
}
