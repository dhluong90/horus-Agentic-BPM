import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { BpmMeV1 } from '../../../api/generated/bpm-me.v1';
import { createRulesClient, type RulesClient } from '../../../api/rules/rulesClient';

/** Who is calling which host: the client, and what /me says the user may do. */
export interface RulesSession {
  client: RulesClient;
  hostId: string;
  tenant: string;
  userId: string;
  can: (permission: string) => boolean;
}

const RulesContext = createContext<RulesSession | undefined>(undefined);

export function RulesProvider({
  baseUrl,
  hostId,
  me,
  fetchImpl,
  children,
}: {
  baseUrl: string;
  hostId: string;
  me: BpmMeV1;
  fetchImpl?: typeof fetch | undefined;
  children: ReactNode;
}) {
  const { permissions } = me;
  const tenant = me.tenant.current;
  const userId = me.user.id;
  const value = useMemo<RulesSession>(() => {
    const granted = new Set(permissions);
    return {
      client: createRulesClient(baseUrl, fetchImpl),
      hostId,
      tenant,
      userId,
      can: (permission) => granted.has(permission),
    };
  }, [baseUrl, hostId, permissions, tenant, userId, fetchImpl]);
  return <RulesContext.Provider value={value}>{children}</RulesContext.Provider>;
}

export function useRulesSession(): RulesSession {
  const value = useContext(RulesContext);
  if (!value) throw new Error('useRulesSession must be used inside RulesProvider');
  return value;
}
