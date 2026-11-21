import { createContext, useContext, useMemo, type ReactNode } from 'react';
import type { BpmMeV1 } from '../../../api/generated/bpm-me.v1';
import { createRulesClient, type RulesClient } from '../../../api/rules/rulesClient';
import type { ReadingStyle } from '../../../preferences/storage';

/** Who is calling which host: the client, and what /me says the user may do. */
export interface RulesSession {
  client: RulesClient;
  hostId: string;
  tenant: string;
  userId: string;
  can: (permission: string) => boolean;
  /** False when the host keeps rule changes in its release pipeline (/meta setting authoring.cockpit-drafts). */
  cockpitDrafts: boolean;
  /** The host's reading style (/meta setting rules.readings.style), used until the viewer picks one (PD-077). */
  readingStyle: ReadingStyle;
}

const RulesContext = createContext<RulesSession | undefined>(undefined);

export function RulesProvider({
  baseUrl,
  hostId,
  me,
  cockpitDrafts,
  readingStyle = 'full',
  fetchImpl,
  children,
}: {
  baseUrl: string;
  hostId: string;
  me: BpmMeV1;
  cockpitDrafts: boolean;
  readingStyle?: ReadingStyle;
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
      cockpitDrafts,
      readingStyle,
    };
  }, [baseUrl, hostId, permissions, tenant, userId, cockpitDrafts, readingStyle, fetchImpl]);
  return <RulesContext.Provider value={value}>{children}</RulesContext.Provider>;
}

export function useRulesSession(): RulesSession {
  const value = useContext(RulesContext);
  if (!value) throw new Error('useRulesSession must be used inside RulesProvider');
  return value;
}
