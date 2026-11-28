import type { NodeLiveStats } from '../../../api/processes/schemas';

/** What one step holds now: its live tokens and its open incidents (#91). */
export interface LiveFigures { node: string; tokens: number; incidents: number }

/**
 * The steps that hold a live token or an open incident, in step id order. Tokens are summed over every live status
 * the engine names (active and each wait); a step with neither is left out. Whole-number sums only.
 */
export function liveFigures(stats: NodeLiveStats): LiveFigures[] {
  const ids = new Set([...Object.keys(stats.nodes), ...Object.keys(stats.openIncidents ?? {})]);
  return [...ids].sort().flatMap((node) => {
    const tokens = Object.values(stats.nodes[node] ?? {}).reduce((sum, n) => sum + n, 0);
    const incidents = stats.openIncidents?.[node] ?? 0;
    return tokens > 0 || incidents > 0 ? [{ node, tokens, incidents }] : [];
  });
}
