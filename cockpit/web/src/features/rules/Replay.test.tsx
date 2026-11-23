import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { simulationSeed, type FakeRequest } from '../../mocks/ruleEngine';
import { dayOf, groupOf, presetDays, replayRequest } from './model/replay';

let sent: { path: string; key: string | null; body: string }[] = [];

beforeEach(() => {
  sent = [];
  mockHost.events.on('request:start', ({ request }) => {
    const path = decodeURIComponent(new URL(request.url).pathname);
    if (path.startsWith('/api/bpm/rules') && request.method === 'POST') {
      const entry = { path: path.replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key'), body: '' };
      sent.push(entry);
      void request.clone().text().then((text) => { entry.body = text; });
    }
  });
  return () => { mockHost.events.removeAllListeners(); };
});

function open(hash: string, scenario: Scenario = 'rules-author-replay', locale?: 'vi') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const LAN = ['rules:read', 'rules:author', 'rules:simulate', 'rules:log:read'];
function post(path: string, body: unknown, permissions: string[] = LAN): FakeRequest {
  return { method: 'POST', path: `/api/bpm/rules/v1${path}`, headers: {}, body: typeof body === 'string' ? body : JSON.stringify(body), user: 'lan', permissions };
}
const PERIOD = { from: '2026-11-01T00:00:00Z', to: '2026-12-01T00:00:00Z' };

describe('replay of past decisions against a draft', () => {
  it('CKP_096_replay_lists_changed_and_unchanged_decisions_with_plain_readings_and_writes_nothing', async () => {
    resetRuleEngine(simulationSeed());
    const user = userEvent.setup();
    open('#/rules/card-fee/2/replay');
    await user.click(await screen.findByRole('button', { name: 'Run replay' }));
    expect(await screen.findByRole('heading', { name: /decisions would change$/ })).toBeVisible();
    const tiles = screen.getByRole('group', { name: 'Replay result' });
    expect(within(tiles).getByText('Would change')).toBeVisible();
    expect(within(tiles).getByText('Stay the same')).toBeVisible();
    const table = screen.getByRole('table', { name: 'Replayed decisions, newest first' });
    const changed = within(table).getAllByRole('row').slice(1);
    expect(changed.length).toBeGreaterThan(0);
    expect(changed[0]).toHaveTextContent('Changes');
    expect(changed[0]).toHaveTextContent(/Rule R\d applies/);
    await user.click(screen.getByRole('button', { name: /^Unchanged/ }));
    expect(within(screen.getByRole('table', { name: 'Replayed decisions, newest first' })).getAllByText('Same result with this draft.').length).toBeGreaterThan(0);
    // One read: the cockpit sends only the period and the cap, no Idempotency-Key, and nothing that logs a decision.
    expect(sent.map((call) => call.path)).toEqual(['/rule-sets/card-fee/versions/2:simulate-history-readings']);
    expect(sent[0]?.key).toBeNull();
    await waitFor(() => { expect(Object.keys(JSON.parse(sent[0]?.body ?? '{}') as object).sort()).toEqual(['from', 'limit', 'to']); });
  });

  it('CKP_096_a_decision_with_a_masked_input_names_the_input_and_never_shows_a_value', async () => {
    resetRuleEngine(simulationSeed());
    const user = userEvent.setup();
    open('#/rules/kyc-routing/2/replay');
    await user.click(await screen.findByRole('button', { name: 'Run replay' }));
    await user.click(await screen.findByRole('button', { name: /^Not replayed/ }));
    const table = screen.getByRole('table', { name: 'Replayed decisions, newest first' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.length).toBeGreaterThan(0);
    expect(rows[0]).toHaveTextContent('It used National ID, which is masked. The log keeps no value to replay.');
    expect(document.body.textContent).not.toMatch(/SEG-\d+/);
  });

  it('CKP_096_a_period_the_service_refuses_is_said_in_plain_words', async () => {
    resetRuleEngine(simulationSeed());
    const user = userEvent.setup();
    open('#/rules/card-fee/2/replay');
    fireEvent.change(await screen.findByLabelText('From'), { target: { value: '2026-09-01' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-11-30' } });
    await user.click(screen.getByRole('button', { name: 'Run replay' }));
    expect(await screen.findByText('The service does not accept this period. Choose a shorter one that ends after it starts.')).toBeVisible();
    // The service's own sentence stays behind Details, for support.
    expect(screen.getByRole('button', { name: /Details/ })).toBeVisible();
  });

  it('CKP_096_without_the_log_permission_run_is_disabled_with_the_reason_and_nothing_is_sent', async () => {
    resetRuleEngine(simulationSeed());
    open('#/rules/card-fee/2/replay', 'rules-author-replay-no-log');
    const run = await screen.findByRole('button', { name: 'Run replay' });
    expect(run).toBeDisabled();
    expect(screen.getByText('Replay needs permission to simulate and to read the decision log.')).toBeVisible();
    expect(sent).toEqual([]);
  });

  it('CKP_096_vietnamese_reads_the_summary_in_vietnamese', async () => {
    resetRuleEngine(simulationSeed());
    const user = userEvent.setup();
    open('#/rules/card-fee/2/replay', 'rules-author-replay', 'vi');
    await user.click(await screen.findByRole('button', { name: 'Chạy phát lại' }));
    expect(await screen.findByRole('heading', { name: /quyết định sẽ đổi kết quả$/ })).toBeVisible();
  });
});

describe('the engine of the mock answers :simulate-history-readings as the OpenAPI says', () => {
  it('RLE_202_permissions_come_before_the_body_and_limits_are_the_engines', () => {
    const engine = resetRuleEngine(simulationSeed());
    const path = '/rule-sets/card-fee/versions/2:simulate-history-readings';
    // Each of the three permissions in turn; the body is not even valid JSON.
    for (const missing of ['rules:simulate', 'rules:log:read', 'rules:read']) {
      expect(engine.handle(post(path, '{not json', LAN.concat('rules:read').filter((p) => p !== missing))).status).toBe(403);
    }
    expect(engine.handle(post(path, { ...PERIOD, limit: 501 }, [...LAN, 'rules:read'])).status).toBe(422);
    expect(JSON.parse(engine.handle(post(path, { ...PERIOD, limit: 501 }, [...LAN, 'rules:read'])).body)).toMatchObject({ code: 'READINGS_TOO_LARGE' });
    expect(engine.handle(post(path, { from: '2026-09-01T00:00:00Z', to: '2026-11-30T00:00:00Z' }, [...LAN, 'rules:read'])).status).toBe(400);
    expect(engine.handle(post('/rule-sets/card-fee/versions/9:simulate-history-readings', PERIOD, [...LAN, 'rules:read'])).status).toBe(404);
  });

  it('RLE_202_a_masked_decision_is_REPLAY_NOT_POSSIBLE_with_names_only_and_no_input_value', () => {
    const engine = resetRuleEngine(simulationSeed());
    const answer = engine.handle(post('/rule-sets/kyc-routing/versions/2:simulate-history-readings', PERIOD, [...LAN, 'rules:read']));
    expect(answer.status).toBe(200);
    const body = JSON.parse(answer.body) as { decisionCount: number; replayedCount: number; notReplayableCount: number; changedCount: number; results: { code: string | null; maskedInputs: string[]; simulated: unknown; logged: unknown; changed: boolean }[] };
    expect(body.replayedCount + body.notReplayableCount).toBe(body.decisionCount);
    const refused = body.results.filter((result) => result.code === 'REPLAY_NOT_POSSIBLE');
    expect(refused.length).toBe(body.notReplayableCount);
    expect(refused.length).toBeGreaterThan(0);
    for (const result of refused) expect([result.maskedInputs, result.simulated, result.logged, result.changed]).toEqual([['nationalId'], null, null, false]);
    expect(body.changedCount).toBe(body.results.filter((result) => result.changed).length);
    expect(answer.body).not.toMatch(/SEG-\d+/);
    expect(answer.body).not.toContain('"inputs"');
  });
});

describe('the period and the cap of a replay', () => {
  it('CKP_096_the_last_day_is_included_and_the_cap_is_a_whole_number', () => {
    expect(replayRequest({ from: '2026-11-01', to: '2026-11-30', cap: '100' })).toEqual({ from: '2026-11-01T00:00:00.000Z', to: '2026-12-01T00:00:00.000Z', limit: 100 });
    expect(replayRequest({ from: '2026-11-01', to: '', cap: '100' })).toBeUndefined();
    expect(replayRequest({ from: '2026-11-01', to: '2026-11-30', cap: '1.5' })).toBeUndefined();
    expect(replayRequest({ from: '2026-11-01', to: '2026-11-30', cap: '' })).toBeUndefined();
  });

  it('CKP_096_presets_count_from_today_and_last_month_is_the_calendar_month_before', () => {
    const now = Date.parse('2026-12-15T10:00:00Z');
    expect(presetDays('week', now)).toEqual({ from: '2026-12-09', to: '2026-12-15' });
    expect(presetDays('thirty', now)).toEqual({ from: '2026-11-16', to: '2026-12-15' });
    expect(presetDays('lastMonth', now)).toEqual({ from: '2026-11-01', to: '2026-11-30' });
    expect(presetDays('lastMonth', Date.parse('2027-01-02T00:00:00Z'))).toEqual({ from: '2026-12-01', to: '2026-12-31' });
    expect(dayOf(now)).toBe('2026-12-15');
  });

  it('CKP_096_a_decision_that_could_not_be_replayed_is_never_counted_as_changed', () => {
    expect(groupOf({ changed: true, code: 'REPLAY_NOT_POSSIBLE' })).toBe('notReplayable');
    expect(groupOf({ changed: true, code: null })).toBe('changed');
    expect(groupOf({ changed: false, code: null })).toBe('unchanged');
  });
});
