import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { simulationSeed } from '../../mocks/ruleEngine';
import { reloadReadingStyle } from '../../preferences/readingStyle';

let calls: { method: string; path: string; key: string | null; body: string }[] = [];

beforeEach(() => {
  calls = [];
  resetRuleEngine(simulationSeed());
  reloadReadingStyle();
  mockHost.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    const path = decodeURIComponent(url.pathname + url.search);
    if (!path.startsWith('/api/bpm/rules')) return;
    const entry = { method: request.method, path: path.replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key'), body: '' };
    calls.push(entry);
    void request.clone().text().then((text) => { entry.body = text; });
  });
  return () => { mockHost.events.removeAllListeners(); reloadReadingStyle(); };
});

function open(hash: string, scenario: Scenario = 'rules-author-simulate', locale?: 'vi') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const problem = (status: number, code: string) => HttpResponse.json({ type: 'about:blank', title: 'Refused', status, code, detail: 'refused', correlationId: '01JFAKE0000000000000000042' }, { status, headers: { 'Content-Type': 'application/problem+json' } });

/** Five customers of Sao Mai Bank: two of them pay a different fee under the draft. */
const FIVE = [
  'Platinum card\tPlatinum\t\t',
  'Gold, high balance\tGold\t60000000\t',
  'Gold, mid balance\tGold\t30000000\t',
  'Busy classic\tClassic\t\t25',
  'Quiet classic\tClassic\t\t2',
].join('\n');

async function pasteFive(user: ReturnType<typeof userEvent.setup>, words = { open: 'Paste rows', rows: 'Rows', add: 'Add these rows' }) {
  await user.click(await screen.findByRole('button', { name: words.open }));
  await user.click(screen.getByLabelText(words.rows));
  await user.paste(FIVE);
  await user.click(screen.getByRole('button', { name: words.add }));
}

describe('simulation of a draft', () => {
  it('CKP_004_simulation_displays_outcome_changes_safely', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/simulate');
    await pasteFive(user);
    expect(screen.getByRole('textbox', { name: 'S5, Name' })).toHaveValue('Quiet classic');
    await user.click(screen.getByRole('button', { name: 'Run simulation' }));

    expect(await screen.findByRole('heading', { name: '2 of 5 results change' })).toBeVisible();
    const table = screen.getByRole('table', { name: 'Results of 5 samples' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(5);
    // Each result names its sample: the engine's reference and the author's name for it.
    expect(rows[2]).toHaveTextContent('S3Gold, mid balance');
    expect(rows[2]).toHaveTextContent('The result changes');
    expect(rows[2]).toHaveTextContent('Rule R6 applies');
    expect(rows[2]).toHaveTextContent('Rule R3 applies when Tier is Gold. Result: fee is 30,000 VND; waived is No.');
    expect(rows[3]).toHaveTextContent('The result changes');
    expect(rows[0]).toHaveTextContent('Same result');
    expect(within(table).getAllByText('The result changes')).toHaveLength(2);
    expect(within(table).getByRole('columnheader', { name: 'In effect: version 1' })).toBeVisible();

    // One read, no Idempotency-Key, and nothing that logs a decision.
    const sent = calls.filter((c) => c.method === 'POST');
    expect(sent.map((c) => c.path)).toEqual(['/rule-sets/card-fee/versions/2:simulate-readings']);
    expect(sent[0]?.key).toBeNull();
    await waitFor(() => { expect(JSON.parse(sent[0]?.body ?? '{}')).toMatchObject({ samples: [{ label: 'Platinum card', inputs: { tier: 'PLATINUM' } }, { label: 'Gold, high balance', inputs: { tier: 'GOLD', balance: { currency: 'VND', amount: 60000000 } } }, {}, {}, { label: 'Quiet classic', inputs: { tier: 'CLASSIC', txCount: 2 } }] }); });
    expect(calls.some((c) => c.path.includes(':evaluate') || c.path.startsWith('/decisions'))).toBe(false);

    // The samples come back as they were.
    await user.click(screen.getByRole('button', { name: /Change the samples/ }));
    expect(screen.getByRole('textbox', { name: 'S3, Name' })).toHaveValue('Gold, mid balance');
  });

  it('CKP_004_vietnamese_reads_the_summary_and_the_results_in_vietnamese', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/simulate', 'rules-author-simulate', 'vi');
    await pasteFive(user, { open: 'Dán các dòng', rows: 'Các dòng', add: 'Thêm các dòng này' });
    await user.click(screen.getByRole('button', { name: 'Chạy mô phỏng' }));
    expect(await screen.findByRole('heading', { name: '2 trên 5 kết quả thay đổi' })).toBeVisible();
    expect(screen.queryByText(/Rule R\d applies/)).toBeNull();
  });

  it('CKP_004_simulation_is_offered_on_drafts_only', async () => {
    open('#/rules/card-fee/1/simulate');
    expect(await screen.findByText('Simulation runs on drafts only.')).toBeVisible();
    const views = screen.getByRole('tablist', { name: 'View' });
    expect(within(views).queryByRole('tab', { name: 'Simulate' })).toBeNull();
  });

  it('CKP_004_a_masked_input_is_typed_as_dots_and_never_shown', async () => {
    const user = userEvent.setup();
    open('#/rules/kyc-routing/2/simulate');
    const field = await screen.findByLabelText('S1, National ID');
    expect(field).toHaveAttribute('type', 'password');
    await user.type(field, 'SEG-4471');
    await user.click(screen.getByRole('button', { name: 'Run simulation' }));
    const table = await screen.findByRole('table', { name: 'Result of 1 sample' });
    expect(table).toHaveTextContent('National ID ••••');
    expect(screen.getByRole('main')).not.toHaveTextContent('SEG-4471');
    expect(screen.getByText('Masked data is named here, never shown.')).toBeVisible();
  });

  it('CKP_004_run_is_disabled_with_a_reason', async () => {
    open('#/rules/card-fee/2/simulate', 'rules-author');
    const run = await screen.findByRole('button', { name: 'Run simulation' });
    expect(run).toBeDisabled();
    expect(run).toHaveAccessibleDescription('You need permission to simulate rules.');
  });

  it('CKP_004_an_empty_sample_cannot_run_and_a_bad_amount_is_named', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/simulate');
    const run = await screen.findByRole('button', { name: 'Run simulation' });
    expect(run).toHaveAccessibleDescription('Fill in at least one sample to run.');
    await user.type(screen.getByLabelText('S1, Balance'), '12.5');
    await user.click(run);
    expect(await screen.findByRole('alert')).toHaveTextContent('S1, Balance: enter an amount such as 60,000,000 VND.');
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('CKP_004_a_draft_without_rows_says_so', async () => {
    open('#/rules/new-fees/1/simulate');
    expect(await screen.findByText('This draft has no rows to simulate yet.')).toBeVisible();
  });

  it('CKP_004_too_many_samples_refused_and_failed_read_in_words', async () => {
    const user = userEvent.setup();
    let refusal: [number, string] = [422, 'READINGS_TOO_LARGE'];
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2:simulate-readings', () => problem(...refusal)));
    open('#/rules/card-fee/2/simulate');
    const again = async () => {
      await user.click(await screen.findByRole('button', { name: /Change the samples/ }));
      await user.click(screen.getByRole('button', { name: 'Run simulation' }));
    };
    await user.selectOptions(await screen.findByLabelText('S1, Tier'), 'GOLD');
    await user.click(screen.getByRole('button', { name: 'Run simulation' }));
    expect(await screen.findByText('The service reads a limited number of samples at once. Run fewer samples.')).toBeVisible();
    refusal = [403, 'PERMISSION_DENIED'];
    await again();
    expect(await screen.findByText('You cannot simulate rules on this service.')).toBeVisible();
    refusal = [500, 'INTERNAL'];
    await again();
    expect(await screen.findByText('The service could not answer. Try again.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
  });
});
