import { useMemo } from 'react';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import { RulesProvider } from './api/rulesContext';
import { RuleSetList } from './RuleSetList';
import { RuleSetPage } from './RuleSetPage';
import { formatRulesPath, parseRulesPath, RulesNavigationContext, type RulesNavigation } from './routes';

export interface RulesAreaProps {
  /** The path below the mount point, for example "/card-fee/2" when the browser shows /rules/card-fee/2. */
  path: string;
  /** Go to a path below the mount point. */
  navigate: (path: string) => void;
  /** The link target for a path below the mount point. */
  href: (path: string) => string;
  baseUrl: string;
  hostId: string;
  me: BpmMeV1;
  cockpitDrafts: boolean;
  fetchImpl?: typeof fetch | undefined;
}

/**
 * The rule screens as one mountable area: rule set list, version view, draft editor, approval and Try it.
 * It owns its sub-routes and needs only a path, two navigation callbacks and the host session, so a shell
 * can mount it under /rules with any router.
 */
export function RulesArea({ path, navigate, href, baseUrl, hostId, me, cockpitDrafts, fetchImpl }: RulesAreaProps) {
  const navigation = useMemo<RulesNavigation>(
    () => ({ go: (route) => { navigate(formatRulesPath(route)); }, href: (route) => href(formatRulesPath(route)) }),
    [navigate, href],
  );
  const route = parseRulesPath(path);
  return (
    <RulesProvider baseUrl={baseUrl} hostId={hostId} me={me} cockpitDrafts={cockpitDrafts} fetchImpl={fetchImpl}>
      <RulesNavigationContext.Provider value={navigation}>
        {route.name === 'list' ? <RuleSetList /> : <RuleSetPage name={route.ruleSet} versionNumber={route.version} lens={route.lens} />}
      </RulesNavigationContext.Provider>
    </RulesProvider>
  );
}
