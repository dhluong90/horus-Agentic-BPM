import { http, HttpResponse } from 'msw';
import { scenarioPermissions, scenarioResponses, scenarioUser, type MockResponse, type Scenario } from './fixtures';
import type { AuditOptions } from './auditEngine';
import { createFakeEngine, demoSeed, type AcknowledgementSettings, type StoredVersion } from './ruleEngine';

function reply(response: MockResponse) {
  const headers: Record<string, string> = { 'Content-Type': response.contentType };
  if (response.location !== undefined) headers['Location'] = response.location;
  return new HttpResponse(response.location !== undefined ? null : JSON.stringify(response.body), { status: response.status, headers });
}

let engine = createFakeEngine(demoSeed());

/**
 * A fresh mock Rule Engine with the demo data, or the given versions, for test isolation; optionally with the
 * acknowledgement settings the engine marks warnings by (PD-104, RLE-053), and the audit options (decision log, evidence limit).
 */
export function resetRuleEngine(seed: StoredVersion[] = demoSeed(), acknowledgement: AcknowledgementSettings = {}, audit: AuditOptions = {}): void {
  engine = createFakeEngine(seed, acknowledgement, audit);
}

/** MSW handlers of the mock host. Paths match any origin, so they serve the embedded (same-origin) case. */
export function handlers(scenario: Scenario | (() => Scenario)) {
  const currentScenario = () => (typeof scenario === 'function' ? scenario() : scenario);
  const current = () => scenarioResponses(currentScenario());
  const rules = async ({ request }: { request: Request }) => {
    const headers: Record<string, string> = {};
    request.headers.forEach((value, key) => {
      headers[key] = value;
    });
    const answer = engine.handle({ method: request.method, path: request.url, headers, body: await request.text(), user: scenarioUser(currentScenario()), permissions: scenarioPermissions(currentScenario()) });
    return new HttpResponse(answer.body, { status: answer.status, headers: answer.headers });
  };
  return [
    http.get('*/cockpit-hosts.json', () => reply(current().registry)),
    http.get('*/api/bpm/meta', () => reply(current().meta)),
    http.get('*/api/bpm/me', () => reply(current().me)),
    http.all('*/api/bpm/rules/v1/*', rules),
  ];
}
