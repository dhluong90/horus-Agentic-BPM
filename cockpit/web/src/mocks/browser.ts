import { setupWorker } from 'msw/browser';
import { isScenario, KNOWN_RULE_SETS, type Scenario } from './fixtures';
import { handlers } from './handlers';

/** Dev server only. Pick a situation with ?scenario=me-forbidden (see fixtures.ts). */
export async function startDevMocks(): Promise<void> {
  const requested = new URLSearchParams(window.location.search).get('scenario');
  const scenario: Scenario = isScenario(requested) ? requested : 'rules-author';
  if (window.localStorage.getItem(KNOWN_RULE_SETS.key) === null) window.localStorage.setItem(KNOWN_RULE_SETS.key, KNOWN_RULE_SETS.value);
  await setupWorker(...handlers(scenario)).start({ onUnhandledFrame: 'bypass', quiet: true });
}
