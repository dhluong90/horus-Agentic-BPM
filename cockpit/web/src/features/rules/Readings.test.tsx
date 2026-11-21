import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { readingsSeed } from '../../mocks/ruleEngine';
import { reloadReadingStyle } from '../../preferences/readingStyle';

let calls: { method: string; path: string; key: string | null; body: string }[] = [];

beforeEach(() => {
  calls = [];
  resetRuleEngine(readingsSeed());
  reloadReadingStyle();
  mockHost.events.on('request:start', ({ request }) => {
    const path = decodeURIComponent(new URL(request.url).pathname + new URL(request.url).search);
    if (!path.startsWith('/api/bpm/rules')) return;
    const entry = { method: request.method, path: path.replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key'), body: '' };
    calls.push(entry);
    void request.clone().text().then((text) => { entry.body = text; });
  });
  return () => { mockHost.events.removeAllListeners(); reloadReadingStyle(); };
});

function open(hash: string, scenario: Scenario = 'rules-approver', locale?: 'vi') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const problem = (status: number, code: string) => HttpResponse.json({ type: 'about:blank', title: 'Refused', status, code, detail: 'refused', correlationId: '01JFAKE0000000000000000042' }, { status, headers: { 'Content-Type': 'application/problem+json' } });

describe('readings of a version', () => {
  it('CKP_076_each_row_shows_the_engine_sentence_in_the_chosen_language', async () => {
    open('#/rules/card-fee/1/readings');
    const list = await screen.findByRole('list', { name: 'Rows of card-fee version 1 in plain words' });
    const items = within(list).getAllByRole('listitem');
    expect(items).toHaveLength(5);
    expect(items[1]).toHaveTextContent('Rule R2 applies when Tier is Gold and Balance is at least 50,000,000 VND. Then fee is 0 VND; waived is Yes.');
    expect(screen.getByRole('button', { name: 'Sentence' })).toHaveAttribute('aria-pressed', 'true');
    expect(calls.filter((c) => c.path === '/rule-sets/card-fee/versions/1/readings')).toHaveLength(1);
  });

  it('CKP_076_vietnamese_shows_the_vietnamese_sentence_with_grouped_amounts', async () => {
    open('#/rules/card-fee/1/readings', 'rules-approver', 'vi');
    const list = await screen.findByRole('list', { name: 'Các dòng của card-fee phiên bản 1 bằng lời' });
    expect(within(list).getAllByRole('listitem')[1]).toHaveTextContent('Quy tắc R2 áp dụng khi Hạng là Gold và Số dư ít nhất là 50.000.000 VND. Khi đó phí là 0 VND; miễn phí là Có.');
    expect(screen.queryByText(/Rule R2 applies/)).toBeNull();
  });

  it('CKP_076_the_short_form_is_chosen_once_and_remembered_for_the_viewer', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1/readings');
    await screen.findByRole('list', { name: /in plain words/ });
    await user.click(screen.getByRole('button', { name: 'Short' }));
    expect(await screen.findByText('Gold, Balance at least 50,000,000 VND: fee 0 VND; waived: Yes')).toBeVisible();
    expect(window.localStorage.getItem('horus.cockpit.readingStyle')).toBe('short');
    // No new request: the engine sent both styles.
    expect(calls.filter((c) => c.path.endsWith('/readings'))).toHaveLength(1);
  });

  it('CKP_076_the_host_setting_is_the_default_until_the_viewer_chooses', async () => {
    window.localStorage.setItem('horus.cockpit.readingStyle', 'short');
    reloadReadingStyle();
    open('#/rules/card-fee/1/readings');
    expect(await screen.findByText(/^Gold, Balance at least/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Short' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('CKP_079_a_row_testing_a_masked_input_says_so_and_never_shows_the_value', async () => {
    open('#/rules/kyc-routing/1/readings');
    const list = await screen.findByRole('list', { name: /kyc-routing/ });
    const [first, second] = within(list).getAllByRole('listitem');
    expect(first).toHaveTextContent('National ID is masked; its test is not shown');
    expect(first).toHaveTextContent('Masked data is named here, never shown.');
    expect(second).not.toHaveTextContent('Masked data');
    expect(list).not.toHaveTextContent('SEG-4471');
  });

  it('CKP_079_too_many_rows_reads_calmly_and_offers_the_table', async () => {
    const user = userEvent.setup();
    open('#/rules/branch-fees/1/readings');
    expect(await screen.findByText('Too many rows to read at once')).toBeVisible();
    expect(screen.queryByRole('alert')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Open the table' }));
    await waitFor(() => { expect(window.location.hash).toBe('#/rules/branch-fees/1/table'); });
    expect(await screen.findByRole('table', { name: /branch-fees/ })).toBeVisible();
  });

  it('CKP_079_no_rows_and_no_permission_and_a_failure_each_read_in_words', async () => {
    open('#/rules/new-fees/1/readings');
    expect(await screen.findByText('This version has no rows yet.')).toBeVisible();
  });

  it('CKP_079_403_says_so_without_retry', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/1/readings', () => problem(403, 'PERMISSION_DENIED')));
    open('#/rules/card-fee/1/readings');
    expect(await screen.findByText('You cannot read rows in plain words on this service.')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  });

  it('CKP_079_a_failure_offers_retry_with_the_reference_behind_details', async () => {
    const user = userEvent.setup();
    let fail = true;
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/1/readings', () => (fail ? problem(500, 'INTERNAL') : undefined)));
    open('#/rules/card-fee/1/readings');
    const alert = await screen.findByRole('alert', {}, { timeout: 5000 });
    expect(alert).toHaveTextContent('The service could not answer. Try again.');
    fail = false;
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('list', { name: /card-fee version 1/ })).toBeVisible();
  });
});

describe('readings of a change', () => {
  it('CKP_077_the_old_reading_is_struck_and_the_new_one_added_for_each_changed_row', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/changes');
    await user.click(await screen.findByRole('button', { name: 'Readings' }));
    const list = await screen.findByRole('list', { name: 'Changed rows of version 2 against version 1, in plain words' });
    const r3 = within(list).getAllByRole('listitem').find((item) => item.textContent.includes('R3'));
    expect(r3?.querySelector('del')).toHaveTextContent('Rule R3 applies when Tier is Gold. Then fee is 30,000 VND; waived is No.');
    expect(r3?.querySelector('ins')).toHaveTextContent('Rule R3 applies when Tier is Gold. Then fee is 35,000 VND; waived is No.');
    const r6 = within(list).getAllByRole('listitem').find((item) => item.textContent.includes('R6'));
    expect(r6).toHaveTextContent('Added');
    expect(r6?.querySelector('del')).toBeNull();
    const r4 = within(list).getAllByRole('listitem').find((item) => item.textContent.includes('R4'));
    expect(r4).toHaveTextContent('Removed');
    expect(r4?.querySelector('ins')).toBeNull();
    expect(screen.getByText('3 rows read the same.')).toBeVisible();
    // The approval stays docked while the readings are read.
    expect(screen.getByRole('button', { name: 'Approve and publish' })).toBeVisible();
    expect(calls.some((c) => c.path === '/rule-sets/card-fee/versions/2/diff/readings?base=1')).toBe(true);
  });

  it('CKP_077_one_view_at_a_time_table_or_readings', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/changes');
    expect(await screen.findByRole('table', { name: /card-fee/ })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Readings' }));
    await screen.findByRole('list', { name: /Changed rows/ });
    expect(screen.queryByRole('table', { name: /card-fee/ })).toBeNull();
  });

  it('CKP_079_a_change_too_large_to_read_offers_the_table', async () => {
    const user = userEvent.setup();
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2/diff/readings', () => problem(422, 'READINGS_TOO_LARGE')));
    open('#/rules/card-fee/2/changes');
    await user.click(await screen.findByRole('button', { name: 'Readings' }));
    expect(await screen.findByText('This change touches more rows than the service reads at once. Compare it in the table.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Compare in the table' }));
    expect(await screen.findByRole('table', { name: /card-fee/ })).toBeVisible();
  });
});

describe('readings of a simulation', () => {
  it('CKP_078_a_simulation_reads_both_sides_and_says_whether_the_result_changes', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/table');
    await user.click(await screen.findByRole('tab', { name: 'Simulate' }));
    await user.selectOptions(screen.getByLabelText('Tier'), 'GOLD');
    await user.click(screen.getByRole('button', { name: 'Simulate' }));
    expect(await screen.findByText('The result changes')).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Version 2' })).toBeVisible();
    expect(screen.getByText('In effect: version 1')).toBeVisible();
    expect(screen.getByText('Rule R3 applies when Tier is Gold. Result: fee is 35,000 VND; waived is No.')).toBeVisible();
    expect(screen.getByText('Rule R3 applies when Tier is Gold. Result: fee is 30,000 VND; waived is No.')).toBeVisible();
    const sent = calls.find((c) => c.path === '/rule-sets/card-fee/versions/2:simulate-readings');
    expect(sent?.key).toBeNull();
    await waitFor(() => { expect(JSON.parse(sent?.body ?? '{}')).toEqual({ samples: [{ inputs: { tier: 'GOLD' } }] }); });
    // A simulation logs nothing: no evaluate call.
    expect(calls.some((c) => c.path.includes(':evaluate'))).toBe(false);
  });

  it('CKP_078_without_permission_simulate_is_disabled_with_a_reason', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/table', 'rules-author');
    await user.click(await screen.findByRole('tab', { name: 'Simulate' }));
    const button = screen.getByRole('button', { name: 'Simulate' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription('You need permission to simulate rules.');
  });

  it('CKP_079_a_masked_input_is_named_in_the_simulation', async () => {
    const user = userEvent.setup();
    open('#/rules/kyc-routing/1');
    await user.click(await screen.findByRole('tab', { name: 'Simulate' }));
    await user.type(screen.getByLabelText('National ID'), 'SEG-4471');
    await user.click(screen.getByRole('button', { name: 'Simulate' }));
    expect(await screen.findByText('Same result')).toBeVisible();
    const status = screen.getByText('Same result').closest('.sim__result');
    expect(status).toHaveTextContent('National ID is masked; its test is not shown');
    expect(status).toHaveTextContent('Masked data is named here, never shown.');
    expect(status).not.toHaveTextContent('SEG-4471');
  });

  it('CKP_079_a_simulation_too_large_or_refused_reads_in_words', async () => {
    const user = userEvent.setup();
    let refusal: [number, string] = [422, 'READINGS_TOO_LARGE'];
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2:simulate-readings', () => problem(...refusal)));
    open('#/rules/card-fee/2/table');
    await user.click(await screen.findByRole('tab', { name: 'Simulate' }));
    await user.click(screen.getByRole('button', { name: 'Simulate' }));
    expect(await screen.findByText('The service reads a limited number of samples at once. Run fewer samples.')).toBeVisible();
    refusal = [403, 'PERMISSION_DENIED'];
    await user.click(screen.getByRole('button', { name: 'Simulate' }));
    expect(await screen.findByText('You cannot simulate rules on this service.')).toBeVisible();
  });
});
