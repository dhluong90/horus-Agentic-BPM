import type { Page } from '@playwright/test';
import { scenarioPermissions, scenarioResponses, scenarioUser, type MockResponse, type Scenario } from '../src/mocks/fixtures';
import type { AuditOptions } from '../src/mocks/auditEngine';
import { displayNamesAnswer } from '../src/mocks/displayNames';
import { createFakeEngine, demoSeed, type StoredVersion } from '../src/mocks/ruleEngine';

/** Answers the registry, /api/bpm/meta and /api/bpm/me from the shared fixtures, and records the calls. */
export async function mockHost(page: Page, scenario: Scenario = 'ready'): Promise<string[]> {
  const responses = scenarioResponses(scenario);
  const calls: string[] = [];
  const fulfil = (response: MockResponse) =>
    response.location !== undefined
      ? { status: response.status, headers: { Location: response.location }, body: '' }
      : { status: response.status, contentType: response.contentType, body: JSON.stringify(response.body) };
  await page.route('**/cockpit-hosts.json', async (route) => {
    calls.push(new URL(route.request().url()).pathname);
    await route.fulfill(fulfil(responses.registry));
  });
  await page.route('**/api/bpm/meta', async (route) => {
    calls.push(new URL(route.request().url()).pathname);
    await route.fulfill(fulfil(responses.meta));
  });
  await page.route('**/api/bpm/me', async (route) => {
    calls.push(new URL(route.request().url()).pathname);
    await route.fulfill(fulfil(responses.me));
  });
  // The host's display names (#442): roles, groups and users of the demo tenant, in the language asked for.
  await page.route('**/api/bpm/display-names**', async (route) => {
    const request = route.request();
    await route.fulfill({ json: displayNamesAnswer(request.url(), request.headers()['accept-language']) });
  });
  return calls;
}

/** Collects Content-Security-Policy violations reported by the browser. */
export function cspViolations(page: Page): string[] {
  const violations: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy/i.test(message.text())) violations.push(message.text());
  });
  return violations;
}

export async function rememberLocale(page: Page, locale: 'en' | 'vi'): Promise<void> {
  await page.addInitScript((value) => {
    window.localStorage.setItem('horus.cockpit.locale', value);
  }, locale);
}

/** Answers the Rule Engine endpoints from the in-memory engine with the demo data (or `seed`, and the audit options), as the scenario's user. */
export async function mockRuleEngine(page: Page, scenario: Scenario, seed: StoredVersion[] = demoSeed(), audit: AuditOptions = {}): Promise<ReturnType<typeof createFakeEngine>> {
  const engine = createFakeEngine(seed, {}, audit);
  await page.route('**/api/bpm/rules/v1/**', async (route) => {
    const request = route.request();
    const answer = engine.handle({ method: request.method(), path: request.url(), headers: request.headers(), body: request.postData() ?? '', user: scenarioUser(scenario), permissions: scenarioPermissions(scenario) });
    await route.fulfill({ status: answer.status, headers: answer.headers, body: answer.body });
  });
  return engine;
}
