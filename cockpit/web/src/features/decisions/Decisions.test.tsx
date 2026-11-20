import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { mockHost, setScenario } from '../../mocks/node';

/** Rule Engine calls the screens made: method, path and query. */
let calls: { method: string; path: string; query: URLSearchParams }[] = [];

beforeEach(() => {
  // The month pickers start at this month; only Date is faked, timers run as usual.
  vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-11-21T03:00:00Z') });
  calls = [];
  mockHost.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/api/bpm/rules')) calls.push({ method: request.method, path: decodeURIComponent(url.pathname).replace('/api/bpm/rules/v1', ''), query: url.searchParams });
  });
  return () => { mockHost.events.removeAllListeners(); vi.useRealTimers(); };
});

function open(hash: string, scenario: Scenario = 'rules-auditor', locale?: 'vi') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const decisionCalls = () => calls.filter((call) => call.path.startsWith('/decisions'));
const NAMED = '01KF2Q8Z0RQ6V2Y8M1C4N5P7T9';

describe('decision explorer', () => {
  it('CKP_071_decisions_of_a_rule_set_or_a_case_in_a_month_newest_first', async () => {
    const user = userEvent.setup();
    open('#/decisions');
    // Nothing is searched until the user asks.
    expect(await screen.findByText('Choose a rule set or a case reference, and a month.')).toBeInTheDocument();
    expect(decisionCalls()).toEqual([]);

    await screen.findByRole('option', { name: 'card-fee' });
    fireEvent.change(screen.getByLabelText('Month'), { target: { value: '2026-11' } });
    await user.click(screen.getByRole('button', { name: 'Show decisions' }));

    expect(await screen.findByRole('heading', { name: 'card-fee, November 2026' })).toBeInTheDocument();
    expect(await screen.findByText('First 50 decisions')).toBeInTheDocument();
    expect(window.location.hash).toBe('#/decisions?ruleSet=card-fee&month=2026-11');
    const search = decisionCalls()[0];
    expect(search?.path).toBe('/decisions');
    expect(Object.fromEntries(search?.query ?? [])).toEqual({ ruleSet: 'card-fee', from: '2026-11-01T00:00:00Z', to: '2026-12-01T00:00:00Z', limit: '50' });
    const table = screen.getByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(50);
    // Newest first: the decision of 20 November at 10:00, with its outcome in words and a short id.
    expect(rows[0]).toHaveTextContent('Rule R2 matched');
    expect(rows[0]).toHaveTextContent('LN-2026-0042');
    expect(rows[0]).toHaveTextContent('01KF2Q…P7T9');
    expect(within(table).getAllByText('Failed')).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Show more decisions' }));
    await waitFor(() => { expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(101); });
    expect(decisionCalls()[1]?.query.get('cursor')).toBe('50');

    // By case reference: a basic format check first, then one search across rule sets.
    await user.click(screen.getByRole('button', { name: 'Case reference' }));
    await user.type(screen.getByLabelText('Case reference'), 'LN 42');
    const before = decisionCalls().length;
    await user.click(screen.getByRole('button', { name: 'Show decisions' }));
    expect(screen.getByText('Use 1 to 128 letters, digits, dots, dashes, colons or underscores.')).toBeInTheDocument();
    expect(screen.getByLabelText('Case reference')).toHaveAttribute('aria-invalid', 'true');
    expect(decisionCalls()).toHaveLength(before);
    await user.clear(screen.getByLabelText('Case reference'));
    await user.type(screen.getByLabelText('Case reference'), 'LN-2026-0042');
    await user.click(screen.getByRole('button', { name: 'Show decisions' }));
    expect(await screen.findByRole('heading', { name: 'Case LN-2026-0042, November 2026' })).toBeInTheDocument();
    expect(await screen.findByText('2 decisions')).toBeInTheDocument();
    expect(decisionCalls().at(-1)?.query.get('correlationId')).toBe('LN-2026-0042');
    const caseRows = within(screen.getByRole('table')).getAllByRole('row').slice(1);
    expect(caseRows.map((row) => row.textContent)).toEqual([expect.stringContaining('card-fee'), expect.stringContaining('kyc-risk')]);

    // A row opens the decision.
    await user.click(within(caseRows[0] as HTMLElement).getByRole('link'));
    expect(window.location.hash).toBe(`#/decisions/${NAMED}`);
  });

  it('CKP_071_without_the_decision_log_permission_the_page_says_so_and_asks_for_nothing', async () => {
    open('#/decisions?ruleSet=card-fee&month=2026-11', 'rules-author');
    expect(await screen.findByText('Reading decisions needs permission to read the decision log.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show decisions' })).not.toBeInTheDocument();
    expect(decisionCalls()).toEqual([]);
  });

  it('CKP_071_an_empty_month_says_no_decisions_were_logged', async () => {
    open('#/decisions?ruleSet=kyc-risk&month=2026-10');
    expect(await screen.findByText('No decisions were logged for this search.')).toBeInTheDocument();
  });
});

describe('one decision', () => {
  it('CKP_005_decision_log_masks_pii_and_highlights_matched_row', async () => {
    const user = userEvent.setup();
    open(`#/decisions/${NAMED}`);
    // The engine's sentence, unchanged.
    expect(await screen.findByText(/^Rule R2 of card-fee version 1 matched because tier is GOLD/)).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Inputs' }));
    const inputs = screen.getByRole('table', { name: 'Inputs' });
    const nationalId = within(inputs).getByRole('row', { name: /National id/ });
    expect(nationalId).toHaveTextContent('Masked');
    expect(within(nationalId).getByLabelText('reference 9f4a6c')).toBeInTheDocument();
    // Never the stored form, and never a plain value.
    expect(document.body).not.toHaveTextContent('hmac:');
    await user.click(screen.getByRole('tab', { name: 'Rule table' }));
    await waitFor(() => { expect(document.querySelectorAll('.dt-scroll tr.dt__row--matched')).toHaveLength(1); });
    const marked = document.querySelectorAll('.dt-scroll tr.dt__row--matched');
    expect(marked[0]).toHaveTextContent(/^R2/);
  });

  it('CKP_072_decision_shows_engine_sentence_conditions_and_masked_inputs', async () => {
    open(`#/decisions/${NAMED}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'card-fee decision' })).toBeInTheDocument();
    expect(screen.getByText('Rule R2 matched', { selector: '.why .badge' })).toBeInTheDocument();
    const explain = calls.find((call) => call.path.endsWith('/explain'));
    expect(explain?.path).toBe(`/decisions/${NAMED}/explain`);
    expect(explain?.query.get('lang')).toBe('en');
    // Conditions of R2 with readable names, the rule's condition as in the table, grouped amounts and "Held".
    const conditions = screen.getByRole('region', { name: 'Rule R2, row 2' });
    const tier = within(conditions).getByRole('row', { name: /^Tier/ });
    expect(tier).toHaveTextContent('Gold');
    expect(tier).toHaveTextContent('Held');
    const balance = within(conditions).getByRole('row', { name: /^Balance/ });
    expect(balance).toHaveTextContent('≥50,000,000 VND');
    expect(balance).toHaveTextContent('60,000,000 VND');
    expect(screen.getByRole('button', { name: 'Copy id' })).toBeInTheDocument();
    expect(screen.getByText('01KF2Q…P7T9')).toHaveAttribute('title', NAMED);
    expect(document.body).not.toHaveTextContent(/[{}]"/);
  });

  it('CKP_072_the_sentence_follows_the_screen_language', async () => {
    open(`#/decisions/${NAMED}`, 'rules-auditor', 'vi');
    expect(await screen.findByText(/^Quy tắc R2 của card-fee phiên bản 1 khớp vì/)).toBeInTheDocument();
    expect(calls.find((call) => call.path.endsWith('/explain'))?.query.get('lang')).toBe('vi');
    expect(screen.getByRole('tab', { name: 'Điều kiện' })).toBeInTheDocument();
  });

  it('CKP_072_a_failed_decision_says_nothing_was_produced', async () => {
    open('#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7TC');
    expect(await screen.findByText('The decision failed, so nothing was produced.')).toBeInTheDocument();
    expect(screen.getByText('Failed', { selector: '.why .badge' })).toBeInTheDocument();
  });

  it('CKP_072_a_decision_of_another_tenant_or_missing_reads_as_not_found', async () => {
    open('#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7ZZ');
    expect(await screen.findByText('No decision with this id in tenant bank-a.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('CKP_072_a_link_that_is_not_a_decision_id_is_refused_before_any_request', async () => {
    open('#/decisions/not-a-decision');
    expect(await screen.findByText('This link does not name a decision.')).toBeInTheDocument();
    expect(decisionCalls()).toEqual([]);
  });
});
