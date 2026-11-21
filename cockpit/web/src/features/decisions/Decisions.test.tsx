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

  it('CKP_071_without_the_decision_log_permission_decisions_is_hidden_and_asks_for_nothing', async () => {
    open('#/decisions?ruleSet=card-fee&month=2026-11', 'rules-author');
    // Not in the menu, and a link to it reads as not available.
    expect(await screen.findByRole('heading', { level: 1, name: 'Not available' })).toBeInTheDocument();
    const menu = screen.getByRole('navigation', { name: 'Cockpit menu' });
    expect(within(menu).getByRole('link', { name: 'Rules' })).toBeInTheDocument();
    expect(within(menu).queryByRole('link', { name: /Decisions/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show decisions' })).not.toBeInTheDocument();
    expect(decisionCalls()).toEqual([]);
  });

  it('CKP_071_with_the_decision_log_permission_decisions_is_in_the_menu', async () => {
    open('#/rules');
    const menu = await screen.findByRole('navigation', { name: 'Cockpit menu' });
    expect(await within(menu).findByRole('link', { name: /Decisions/ })).toBeInTheDocument();
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
    // The engine's sentence, unchanged: here the rule set author's sentence for R2, with the value grouped.
    expect(await screen.findByText('The card fee is waived because a Gold customer keeps a balance of 60,000,000 VND, at least 50,000,000 VND.')).toBeInTheDocument();
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
    // No raw id in the breadcrumb: it leads back to Decisions only.
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Decisions' })).toBeInTheDocument();
    expect(crumbs).not.toHaveTextContent(/01KF/);
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
    expect(document.body).not.toHaveTextContent(/[{}]"/);
  });

  it('CKP_072_copy_copies_exactly_the_decision_id_shown', async () => {
    const user = userEvent.setup();
    open(`#/decisions/${NAMED}`);
    const shown = await screen.findByTestId('decision-id');
    expect(shown).toHaveTextContent(new RegExp(`^${NAMED}$`));
    await user.click(screen.getByRole('button', { name: 'Copy decision id' }));
    // user-event provides the clipboard.
    expect(await navigator.clipboard.readText()).toBe(shown.textContent);
    expect(await screen.findByText('Decision id copied')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/[{}]"/);
  });

  it('CKP_072_the_sentence_follows_the_screen_language', async () => {
    open(`#/decisions/${NAMED}`, 'rules-auditor', 'vi');
    // The rule set's Vietnamese sentence for R2, the amount grouped for the language; the cockpit shows it unchanged.
    expect(await screen.findByText('Phí thẻ được miễn vì khách hàng hạng Vàng duy trì số dư 60.000.000 VND, từ 50.000.000 VND trở lên.')).toBeInTheDocument();
    expect(document.querySelector('.why__text')).toHaveAttribute('lang', 'vi');
    expect(calls.find((call) => call.path.endsWith('/explain'))?.query.get('lang')).toBe('vi');
    expect(screen.getByRole('tab', { name: 'Điều kiện' })).toBeInTheDocument();
  });

  it('CKP_072_a_row_without_its_own_sentence_reads_in_the_generated_sentence_grouped_for_the_language', async () => {
    open('#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7TA', 'rules-auditor', 'vi');
    expect(await screen.findByText(/^Quy tắc R4 của card-fee phiên bản 1 khớp vì tx count là 25, tức là ít nhất 20\./)).toBeInTheDocument();
    const sentence = document.querySelector('.why__text');
    expect(sentence).toHaveTextContent('Kết quả: fee 15.000 VND; waived Không.');
    expect(sentence).not.toHaveTextContent(/true|false|\d{4,} VND/);
  });

  it('CKP_072_a_failed_decision_reads_in_words_with_the_error_edge_and_the_code_behind_details', async () => {
    const user = userEvent.setup();
    open('#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7TC');
    expect(await screen.findByText('The decision failed, so nothing was produced.')).toBeInTheDocument();
    expect(screen.getByText('Failed', { selector: '.why .badge' })).toBeInTheDocument();
    const why = screen.getByRole('article', { name: 'Why' });
    expect(why).toHaveClass('why--failed');
    expect(within(why).getByText('The rule set could not be evaluated, so no result was produced.')).toBeVisible();
    // The raw code and the engine's sentence are one click away, not in the sentence.
    expect(within(why).getByText('EVALUATION_ERROR')).not.toBeVisible();
    await user.click(within(why).getByText('Details'));
    expect(within(why).getByText('EVALUATION_ERROR')).toBeVisible();
    expect(within(why).getByText(/failed with EVALUATION_ERROR/)).toBeVisible();
  });

  it('CKP_072_a_failed_decision_reads_in_vietnamese', async () => {
    open('#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7TC', 'rules-auditor', 'vi');
    expect(await screen.findByText('Không thể đánh giá bộ quy tắc nên không có kết quả.')).toBeInTheDocument();
  });

  it('CKP_072_a_successful_decision_keeps_the_accent_edge_and_the_engine_sentence', async () => {
    open(`#/decisions/${NAMED}`);
    const why = await screen.findByRole('article', { name: 'Why' });
    expect(why).not.toHaveClass('why--failed');
    expect(within(why).queryByText('Details')).not.toBeInTheDocument();
  });

  it('CKP_072_a_decision_of_another_tenant_or_missing_reads_as_not_found', async () => {
    open('#/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7ZZ');
    expect(await screen.findByText('Decision not found for tenant bank-a.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('CKP_042_a_user_without_the_decision_log_is_told_so_and_no_decision_is_requested', async () => {
    open(`#/decisions/${NAMED}`, 'rules-auditor-no-log');
    expect(await screen.findByText('You do not have permission to open decisions.')).toBeInTheDocument();
    // Not a dead end: the refusal offers Home.
    expect(screen.getByRole('link', { name: 'Go to Home' })).toHaveAttribute('href', '#/home');
    expect(decisionCalls()).toEqual([]);
  });

  it('CKP_042_a_link_with_markup_or_a_long_id_is_not_valid_and_nothing_of_it_is_rendered', async () => {
    open(`#/decisions/${encodeURIComponent('<img src=x onerror=alert(1)>')}`);
    expect(await screen.findByText('This link is not valid.')).toBeInTheDocument();
    expect(document.querySelector('img[src="x"]')).toBeNull();
    open(`#/decisions/${NAMED}X`);
    expect(await screen.findAllByText('This link is not valid.')).not.toHaveLength(0);
    expect(decisionCalls()).toEqual([]);
  });

  it('CKP_072_a_link_that_is_not_a_decision_id_is_refused_before_any_request', async () => {
    open('#/decisions/not-a-decision');
    expect(await screen.findByText('This link is not valid.')).toBeInTheDocument();
    expect(decisionCalls()).toEqual([]);
  });
});
