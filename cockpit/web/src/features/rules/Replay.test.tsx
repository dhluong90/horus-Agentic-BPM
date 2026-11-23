import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { decisionIdOf } from '../../mocks/ruleReports';
import type { HistoryReadings } from '../../api/rules/schemas';
import { simulationSeed, type FakeRequest } from '../../mocks/ruleEngine';
import { dayOf, groupOf, parseDecisionIds, presetDays, replayRequest, runReplay, weeklyWindows } from './model/replay';

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
    // Reads only: the cockpit reads the period a week at a time, newest first, sends the period and the cap of
    // each step, never an Idempotency-Key, and nothing that logs a decision.
    expect(sent.length).toBeGreaterThan(1);
    expect(new Set(sent.map((call) => call.path))).toEqual(new Set(['/rule-sets/card-fee/versions/2:simulate-history-readings']));
    expect(sent.every((call) => call.key === null)).toBe(true);
    await waitFor(() => { expect(sent.every((call) => call.body !== '')).toBe(true); });
    for (const call of sent) expect(Object.keys(JSON.parse(call.body) as object).sort()).toEqual(['from', 'limit', 'to']);
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

describe('replay of selected decisions and the part read before a cancel (#96)', () => {
  it('CKP_096_selected_decisions_are_the_only_ones_sent_and_replayed', async () => {
    resetRuleEngine(simulationSeed());
    const user = userEvent.setup();
    const [a, b] = [decisionIdOf(0), decisionIdOf(2)];
    open('#/rules/card-fee/2/replay');
    await user.type(await screen.findByLabelText('Only these decisions (optional)'), `${a}\n${b}\n01NOTMINE0000000000000000`);
    await user.click(screen.getByRole('button', { name: 'Run replay' }));
    await screen.findByRole('table', { name: 'Replayed decisions, newest first' });
    // The id that is not in the log is left out, never reported; the two chosen are listed, newest first.
    await user.click(screen.getByRole('button', { name: /^All/ }));
    const rows = within(screen.getByRole('table', { name: 'Replayed decisions, newest first' })).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(a.slice(-8));
    expect(rows[1]).toHaveTextContent(b.slice(-8));
    await waitFor(() => { expect((JSON.parse(sent[0]?.body ?? '{}') as { decisionIds: string[] }).decisionIds).toEqual([a, b, '01NOTMINE0000000000000000']); });
  });

  it('CKP_096_an_id_that_is_not_in_the_log_is_left_out_and_the_answer_holds_no_input', () => {
    const engine = resetRuleEngine(simulationSeed());
    const wide = { from: new Date(Date.now() - 20 * 86_400_000).toISOString(), to: new Date(Date.now() + 86_400_000).toISOString() };
    const answer = engine.handle(post('/rule-sets/card-fee/versions/2:simulate-history-readings', { ...wide, decisionIds: ['01NOTMINE0000000000000000', decisionIdOf(3)] }, [...LAN, 'rules:read']));
    expect(answer.status).toBe(200);
    const body = JSON.parse(answer.body) as { results: { decisionId: string }[] };
    expect(body.results.map((r) => r.decisionId)).toEqual([decisionIdOf(3)]);
    expect(answer.body).not.toContain('"inputs"');
    expect(engine.handle(post('/rule-sets/card-fee/versions/2:simulate-history-readings', { ...wide, decisionIds: 'x' }, [...LAN, 'rules:read'])).status).toBe(400);
  });

  it('CKP_096_a_cancel_keeps_the_steps_already_read_and_the_period_goes_week_by_week_newest_first', async () => {
    const weeks = weeklyWindows('2026-11-01T00:00:00.000Z', '2026-11-30T00:00:00.000Z');
    expect(weeks).toHaveLength(5);
    expect(weeks[0]).toEqual({ from: '2026-11-23T00:00:00.000Z', to: '2026-11-30T00:00:00.000Z' });
    expect(weeks.at(-1)).toEqual({ from: '2026-11-01T00:00:00.000Z', to: '2026-11-02T00:00:00.000Z' });
    const part = (ids: string[]): HistoryReadings => ({ ruleSet: 'r', version: 2, status: 'DRAFT', from: '', to: '', decisionCount: ids.length, replayedCount: ids.length, changedCount: 0, notReplayableCount: 0, truncated: false, languages: ['en'], results: ids.map((decisionId, i) => ({ decisionId, loggedVersion: 1, evalClock: `2026-11-${String(29 - i).padStart(2, '0')}T00:00:00Z`, changed: false, code: null, maskedInputs: [], simulated: null, logged: null })) });
    let calls = 0;
    const seen: HistoryReadings[] = [];
    await expect(runReplay({ from: '2026-11-01T00:00:00.000Z', to: '2026-11-30T00:00:00.000Z', limit: 100 }, () => {
      calls += 1;
      if (calls === 3) return Promise.reject(new DOMException('aborted', 'AbortError'));
      return Promise.resolve(part(calls === 1 ? ['a', 'b'] : ['c']));
    }, (joined) => { seen.push(joined); })).rejects.toMatchObject({ name: 'AbortError' });
    expect(seen).toHaveLength(2);
    expect(seen.at(-1)?.results.map((r) => r.decisionId)).toEqual(['a', 'c', 'b']);
    expect(parseDecisionIds(' a,b\n a ;c ')).toEqual(['a', 'b', 'c']);
  });
});
