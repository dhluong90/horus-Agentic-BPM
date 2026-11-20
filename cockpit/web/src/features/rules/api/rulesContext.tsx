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
  /** False when the host keeps rule changes in its release pipeline (/meta setting authoring.cockpit-drafts). */
  cockpitDrafts: boolean;
  /** Warning codes an approver must accept before Approve (/meta setting lifecycle.acknowledge-warnings, PD-104). */
  acknowledgeWarnings: readonly string[];
  /** The tenant's decision-log.mask-default in /meta: an undeclared input is masked, so its warning needs no acceptance. */
  maskDefault: boolean;
}

/** PD-104: the engine's default for lifecycle.acknowledge-warnings. */
export const DEFAULT_ACKNOWLEDGE_WARNINGS: readonly string[] = ['UNMASKED_PERSONAL_DATA'];

const RulesContext = createContext<RulesSession | undefined>(undefined);

export function RulesProvider({
  baseUrl,
  hostId,
  me,
  cockpitDrafts,
  acknowledgeWarnings = DEFAULT_ACKNOWLEDGE_WARNINGS,
  maskDefault = false,
  fetchImpl,
  children,
}: {
  baseUrl: string;
  hostId: string;
  me: BpmMeV1;
  cockpitDrafts: boolean;
  acknowledgeWarnings?: readonly string[] | undefined;
  maskDefault?: boolean | undefined;
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
      acknowledgeWarnings,
      maskDefault,
    };
  }, [baseUrl, hostId, permissions, tenant, userId, cockpitDrafts, acknowledgeWarnings, maskDefault, fetchImpl]);
  return <RulesContext.Provider value={value}>{children}</RulesContext.Provider>;
}

export function useRulesSession(): RulesSession {
  const value = useContext(RulesContext);
  if (!value) throw new Error('useRulesSession must be used inside RulesProvider');
  return value;
}
