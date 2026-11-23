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
  /** Whether the host announces rule set templates (/meta feature rules.templates). */
  templates: boolean;
  /** Whether the host stores simulation reports (/meta feature rules.simulation-reports). */
  simulationReports: boolean;
  /** Whether the host announces replay of logged decisions in plain words (/meta feature rules.history-readings). */
  historyReadings: boolean;
  /** True when the host lists this rule set in lifecycle.require-simulation-report: a publication request must carry a report. */
  requiresReport: (ruleSet: string) => boolean;
}

/** What /meta says about templates and reports: features of the rules module, and the setting naming the rule sets that need a report. */
export interface ReportHostSettings {
  templates?: boolean;
  simulationReports?: boolean;
  historyReadings?: boolean;
  /** The rule set names of lifecycle.require-simulation-report; empty when the host requires none. */
  requireReport?: readonly string[];
}

const RulesContext = createContext<RulesSession | undefined>(undefined);

export function RulesProvider({
  baseUrl,
  hostId,
  me,
  cockpitDrafts,
  readingStyle = 'full',
  host: hostSettings = {},
  fetchImpl,
  children,
}: {
  baseUrl: string;
  hostId: string;
  me: BpmMeV1;
  cockpitDrafts: boolean;
  readingStyle?: ReadingStyle;
  host?: ReportHostSettings;
  fetchImpl?: typeof fetch | undefined;
  children: ReactNode;
}) {
  const { permissions } = me;
  const tenant = me.tenant.current;
  const userId = me.user.id;
  const { templates = false, simulationReports = false, historyReadings = false, requireReport } = hostSettings;
  const required = requireReport?.join(',') ?? '';
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
      templates,
      simulationReports,
      historyReadings,
      requiresReport: (ruleSet) => required.split(',').includes(ruleSet),
    };
  }, [baseUrl, hostId, permissions, tenant, userId, cockpitDrafts, readingStyle, templates, simulationReports, historyReadings, required, fetchImpl]);
  return <RulesContext.Provider value={value}>{children}</RulesContext.Provider>;
}

export function useRulesSession(): RulesSession {
  const value = useContext(RulesContext);
  if (!value) throw new Error('useRulesSession must be used inside RulesProvider');
  return value;
}
