import type { Page } from '@playwright/test';
import { scenarioResponses, type MockResponse, type Scenario } from '../src/mocks/fixtures';

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
