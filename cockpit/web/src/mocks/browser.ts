import { setupWorker } from 'msw/browser';
import { isScenario, KNOWN_RULE_SETS, type Scenario } from './fixtures';
import { handlers } from './handlers';

/** Dev server only. Pick a situation with ?scenario=me-forbidden (see fixtures.ts). */
export async function startDevMocks(): Promise<void> {
  const requested = new URLSearchParams(window.location.search).get('scenario');
  const scenario: Scenario = isScenario(requested) ? requested : 'rules-author';
  for (const { key, value } of KNOWN_RULE_SETS) if (window.localStorage.getItem(key) === null) window.localStorage.setItem(key, value);
  await setupWorker(...handlers(scenario)).start({ onUnhandledFrame: 'bypass', quiet: true });
}
