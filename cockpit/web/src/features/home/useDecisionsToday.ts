import { useQuery } from '@tanstack/react-query';
import type { RulesClient } from '../../api/rules/rulesClient';
import { useEveryRuleSet } from '../overview/useRuleOverview';
import { useRulesSession } from '../rules/api/rulesContext';
import { byHour, dayPeriod } from './model';

/** Pages read of one rule set's log for today; a busier log is cut, and the chart is left out rather than guessed. */
const MAX_PAGES = 5;

export type HourlyDecisions = { status: 'loading' } | { status: 'unavailable' } | { status: 'ready'; counts: number[] };

async function countToday(client: RulesClient, ruleSets: readonly string[], pageSize: number, now: Date, signal: AbortSignal): Promise<number[] | undefined> {
  const { from, to } = dayPeriod(now);
  const counts = Array.from({ length: 24 }, () => 0);
  const read = async (ruleSet: string) => {
    let cursor: string | undefined;
    for (let page = 0; page < MAX_PAGES; page += 1) {
      const answer = await client.searchDecisions({ ruleSet, from, to, limit: pageSize, cursor, signal });
      byHour(answer.data).forEach((count, hour) => { counts[hour] = (counts[hour] ?? 0) + count; });
      if (!answer.hasNext || answer.nextCursor === null) return true;
      cursor = answer.nextCursor;
    }
    return false;
  };
  const complete = await Promise.all(ruleSets.map(read));
  return complete.every(Boolean) ? counts : undefined;
}

/**
 * Today's decisions by hour, from the Rule Engine's decision log (RLE-029): the engine has no hourly count, so the
 * log is read for each rule set and counted by local hour. A host with a failing or too large log gets no chart.
 */
export function useDecisionsToday(pageLimit: number, enabled: boolean, now: Date): HourlyDecisions {
  const { client, hostId, tenant } = useRulesSession();
  const ruleSets = useEveryRuleSet(pageLimit, enabled);
  const names = ruleSets.status === 'ready' ? ruleSets.data.map((item) => item.ruleSet) : [];
  const day = `${String(now.getFullYear())}-${String(now.getMonth() + 1)}-${String(now.getDate())}`;
  const query = useQuery({
    queryKey: [hostId, tenant, 'rules', 'decisions', 'today', day, names.join(',')],
    queryFn: ({ signal }) => countToday(client, names, pageLimit, now, signal),
    enabled: enabled && ruleSets.status === 'ready' && names.length > 0,
    staleTime: 60_000,
    retry: false,
  });
  if (!enabled || ruleSets.status === 'error' || query.isError || (ruleSets.status === 'ready' && names.length === 0)) return { status: 'unavailable' };
  if (query.data === undefined) return query.isSuccess ? { status: 'unavailable' } : { status: 'loading' };
  return { status: 'ready', counts: query.data };
}
