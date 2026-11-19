import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import type { SessionDependencies } from './app/session/useHostSession';
import { I18nProvider, type Locale } from './i18n/I18nProvider';
import { CockpitShell } from './shell/CockpitShell';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { refetchOnWindowFocus: false, retry: false },
      mutations: { retry: false },
    },
  });
}

export function App({ initialLocale, queryClient, ...dependencies }: SessionDependencies & { initialLocale?: Locale; queryClient?: QueryClient }) {
  const [client] = useState(() => queryClient ?? createQueryClient());
  return (
    <QueryClientProvider client={client}>
      <I18nProvider {...(initialLocale ? { initialLocale } : {})}>
        <CockpitShell {...dependencies} />
      </I18nProvider>
    </QueryClientProvider>
  );
}
