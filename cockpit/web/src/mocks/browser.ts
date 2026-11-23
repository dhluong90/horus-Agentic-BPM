import { setupWorker } from 'msw/browser';
import { isScenario, type Scenario } from './fixtures';
import { handlers, resetProcessEngine, resetRuleEngine } from './handlers';
import { simulationSeed } from './ruleEngine';
import { homeDemoAudit, homeDemoProcessSeed, homeDemoVersions } from './homeDemo';

/** Dev server only. Pick a situation with ?scenario=me-forbidden (see fixtures.ts). */
export async function startDevMocks(): Promise<void> {
  const requested = new URLSearchParams(window.location.search).get('scenario');
  const scenario: Scenario = isScenario(requested) ? requested : 'rules-author';
  if (scenario === 'home-operations') {
    resetRuleEngine(homeDemoVersions(), {}, homeDemoAudit());
    resetProcessEngine(homeDemoProcessSeed(new URLSearchParams(window.location.search).get('lang') === 'vi' ? 'vi' : 'en'));
  }
  // A draft with logged decisions behind it, so the replay screen has something to replay in development.
  if (scenario === 'rules-author-replay') resetRuleEngine(simulationSeed());
  await setupWorker(...handlers(scenario)).start({ onUnhandledFrame: 'bypass', quiet: true });
}
