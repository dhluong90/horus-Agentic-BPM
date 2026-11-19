import { useEffect } from 'react';
import { hostLabel } from '../app/config/registry';
import { useHostSession, type SessionDependencies, type SessionState } from '../app/session/useHostSession';
import { SessionHeader, type SessionSummary } from '../components/SessionHeader';
import { ConfigErrorPanel, HostErrorPanel, LoadingPanel } from '../components/StatePanels';
import { useI18n, type Locale } from '../i18n/I18nProvider';
import { Workspace } from './Workspace';

function summary(state: SessionState, locale: Locale): SessionSummary | undefined {
  if (state.status !== 'ready') return undefined;
  const label = hostLabel(state.host.label, locale) || state.meta.hostId || state.host.id;
  return {
    hostLabel: label,
    environment: state.host.environment,
    tenant: state.me.tenant.current,
    userName: state.me.user.displayName ?? state.me.user.id,
    userId: state.me.user.id,
  };
}

function MainContent({ state, fetchImpl }: { state: SessionState; fetchImpl: typeof fetch | undefined }) {
  switch (state.status) {
    case 'loading':
      return <LoadingPanel />;
    case 'config-error':
      return <ConfigErrorPanel problem={state.problem} />;
    case 'host-error':
      return <HostErrorPanel error={state.error} onRetry={state.retry} />;
    case 'ready':
      return <Workspace hostId={state.host.id} baseUrl={state.host.baseUrl} meta={state.meta} me={state.me} fetchImpl={fetchImpl} />;
  }
}

export function CockpitShell(dependencies: SessionDependencies) {
  const state = useHostSession(dependencies);
  const { locale, chosen, setLocale } = useI18n();
  const registry = state.status === 'ready' ? state.registry : undefined;
  const defaultLocale = registry?.ui?.defaultLocale;

  // The registry's default language applies until the user picks one.
  useEffect(() => {
    if (!chosen && defaultLocale !== undefined && defaultLocale !== locale) setLocale(defaultLocale, { remember: false });
  }, [chosen, defaultLocale, locale, setLocale]);

  return (
    <div className="app">
      <SessionHeader session={summary(state, locale)} banner={registry?.ui?.environmentBanner} />
      <main id="main" className="main" tabIndex={-1}>
        <MainContent state={state} fetchImpl={dependencies.fetchImpl} />
      </main>
    </div>
  );
}
