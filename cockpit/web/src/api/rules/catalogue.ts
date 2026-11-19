import type { RulesClient } from './rulesClient';
import type { VersionSummary } from './schemas';

/*
 * Rule set and version discovery through the engine's list endpoints (GET /rule-sets and
 * GET /rule-sets/{name}/versions, rule-engine DESIGN section 4.2). Nothing is kept in the browser.
 */

const NAME = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

export function isRuleSetName(value: string): boolean {
  return NAME.test(value) && value.length <= 100;
}

/** Page size the rule set list asks for; within the engine's default maximum of 200. */
export const PAGE_SIZE = 50;

/** A guard against a host that keeps answering with a next page; never reached with a sound engine. */
const MAX_PAGES = 50;

/** Every version of a rule set, newest first, following the engine's cursor. An unknown rule set is 404. */
export async function allVersions(client: RulesClient, name: string, signal?: AbortSignal): Promise<VersionSummary[]> {
  const versions: VersionSummary[] = [];
  let cursor: string | undefined;
  for (let pageNumber = 0; pageNumber < MAX_PAGES; pageNumber += 1) {
    const answer = await client.listVersions(name, { cursor, limit: 200, signal });
    versions.push(...answer.data);
    if (!answer.hasNext || answer.nextCursor === null) break;
    cursor = answer.nextCursor;
  }
  return versions;
}
