import { http, HttpResponse } from 'msw';
import { scenarioResponses, type MockResponse, type Scenario } from './fixtures';

function reply(response: MockResponse) {
  const headers: Record<string, string> = { 'Content-Type': response.contentType };
  if (response.location !== undefined) headers['Location'] = response.location;
  return new HttpResponse(response.location !== undefined ? null : JSON.stringify(response.body), { status: response.status, headers });
}

/** MSW handlers of the mock host. Paths match any origin, so they serve the embedded (same-origin) case. */
export function handlers(scenario: Scenario | (() => Scenario)) {
  const current = () => scenarioResponses(typeof scenario === 'function' ? scenario() : scenario);
  return [
    http.get('*/cockpit-hosts.json', () => reply(current().registry)),
    http.get('*/api/bpm/meta', () => reply(current().meta)),
    http.get('*/api/bpm/me', () => reply(current().me)),
  ];
}
