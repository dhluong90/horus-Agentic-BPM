import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { mockHost, setScenario } from '../../mocks/node';

/*
 * The approvals queue across both engines (#31, CKP-003), with the demo data of the in-memory engines: card-fee v2
 * asked for by Lan on 18 Nov and Customer onboarding (KYC) v3 asked for by Lan on 20 Nov.
 */

const problem = (status: number, code: string) => HttpResponse.json({ title: code, status, code, correlationId: '01JQUEUE' }, { status, headers: { 'Content-Type': 'application/problem+json' } });

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

const queueTitles = async () => {
  await screen.findAllByRole('button', { name: /· Version/ });
  return screen.getAllByRole('button', { name: /· Version/ }).map((button) => button.querySelector('.queue-card__title')?.textContent);
};

describe('Approvals queue across both engines (#31)', () => {
  it('CKP_003_one_queue_holds_rule_and_process_requests_oldest_first_and_the_menu_counts_both', async () => {
    setScenario('processes-approver');
    open('#/approvals');

    expect(await queueTitles()).toEqual(['card-fee · Version 2', 'Customer onboarding (KYC) · Version 3']);
    const menu = screen.getByRole('navigation', { name: 'Cockpit menu' });
    expect(within(menu).getByRole('link', { name: /Approvals/ })).toHaveTextContent('2');
    await userEvent.click(screen.getByRole('button', { name: /Customer onboarding \(KYC\) · Version 3/ }));
    const preview = await screen.findByRole('article', { name: 'Customer onboarding (KYC) · Version 3' });
    expect(within(preview).getByText('Process')).toBeInTheDocument();
    expect(within(preview).getByRole('link', { name: /Review and approve/ })).toHaveAttribute('href', '#/processes/kyc-onboarding/3/changes');
    // No raw ids or hashes as text: the key and the content hash stay in the link and the request.
    expect(screen.getByRole('main')).not.toHaveTextContent(/kyc-onboarding|9a8b7c6d/);
  });

  it('CKP_003_home_counts_process_requests_too', async () => {
    setScenario('processes-approver');
    open('');

    const waiting = await screen.findByRole('article', { name: /Awaiting approval/ });
    expect(await within(waiting).findByText('card-fee · Version 2')).toBeInTheDocument();
    expect(within(waiting).getByText('Customer onboarding (KYC) · Version 3')).toBeInTheDocument();
    expect(within(waiting).getByText('2 waiting')).toBeInTheDocument();
    expect(within(screen.getByRole('link', { name: /Awaiting approval/ })).getByText('2')).toBeInTheDocument();
  });

  it('CKP_003_the_requester_sees_approve_disabled_with_the_engines_reason_on_both_kinds_of_change', async () => {
    setScenario('processes-requester');
    open('#/approvals');

    expect(await queueTitles()).toEqual(['card-fee · Version 2', 'Customer onboarding (KYC) · Version 3']);
    expect(screen.getByRole('button', { name: /Your requests/ })).toHaveAttribute('aria-pressed', 'true');
    // Lan may approve processes but not rules: the engine names the reason that applies to each change.
    const reasons = [
      ['card-fee · Version 2', 'Only an approver can approve this change.'],
      ['Customer onboarding (KYC) · Version 3', 'You made this change, so someone else approves it.'],
    ] as const;
    for (const [name, reason] of reasons) {
      await userEvent.click(screen.getByRole('button', { name: new RegExp(name.replace(/[()]/g, '\\$&')) }));
      const preview = await screen.findByRole('article', { name });
      const approve = within(preview).getByRole('button', { name: /Approve/ });
      expect(approve).toBeDisabled();
      expect(approve).toHaveAccessibleDescription(reason);
    }
    expect(screen.queryByRole('link', { name: 'Review next' })).not.toBeInTheDocument();
  });

  it('CKP_003_a_failed_engine_leaves_the_other_engines_requests_and_says_the_list_is_incomplete', async () => {
    setScenario('processes-approver');
    let failing = true;
    mockHost.use(http.get('*/api/bpm/processes/v1/approvals', () => (failing ? problem(500, 'INTERNAL_ERROR') : undefined)));
    open('#/approvals');

    expect(await queueTitles()).toEqual(['card-fee · Version 2']);
    const notice = screen.getByText('Process changes could not be read. This list may be incomplete.').closest('.notice');
    expect(notice).not.toBeNull();
    failing = false;
    await userEvent.click(within(notice as HTMLElement).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('button', { name: /Customer onboarding \(KYC\) · Version 3/ })).toBeInTheDocument();
    expect(screen.queryByText(/could not be read/)).not.toBeInTheDocument();
  });

  it('CKP_003_when_every_engine_fails_the_queue_shows_one_error_with_try_again', async () => {
    setScenario('processes-approver');
    mockHost.use(
      http.get('*/api/bpm/processes/v1/approvals', () => problem(500, 'INTERNAL_ERROR')),
      http.get('*/api/bpm/rules/v1/approvals', () => problem(503, 'SERVICE_UNAVAILABLE')),
    );
    open('#/approvals');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The service could not answer. Try again.');
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('CKP_003_a_refused_read_is_the_forbidden_state_with_no_retry', async () => {
    setScenario('processes-approver');
    mockHost.use(
      http.get('*/api/bpm/processes/v1/approvals', () => problem(403, 'PERMISSION_DENIED')),
      http.get('*/api/bpm/rules/v1/approvals', () => problem(403, 'PERMISSION_DENIED')),
    );
    open('#/approvals');

    expect(await screen.findByText('You do not have access to approvals on this service.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Approvals' })).not.toBeInTheDocument();
  });

  it('CKP_003_the_queue_shows_skeleton_rows_while_it_loads', async () => {
    setScenario('processes-approver');
    mockHost.use(http.get('*/api/bpm/rules/v1/approvals', async () => { await delay(200); return undefined; }));
    open('#/approvals');

    expect(await screen.findByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await queueTitles()).toHaveLength(2);
  });

  it('CKP_003_show_more_reads_the_next_page_of_the_engine_at_the_frontier_only', async () => {
    setScenario('processes-approver');
    const cursors: (string | null)[] = [];
    const item = (ruleSet: string, requestedAt: string) => ({ ruleSet, version: 2, canonicalHash: 'c'.repeat(64), lockVersion: 1, requestedBy: 'hoa', requestedAt, allowedActions: ['approve', 'reject'], deniedActions: {} });
    mockHost.use(http.get('*/api/bpm/rules/v1/approvals', ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      cursors.push(cursor);
      return HttpResponse.json(cursor === null
        ? { data: [item('card-fee', '2026-11-18T08:30:00Z')], nextCursor: 'page-2', hasNext: true }
        : { data: [item('fx-margin', '2026-11-21T01:00:00Z')], nextCursor: null, hasNext: false });
    }));
    open('#/approvals');

    // The process request of 20 Nov waits until the Rule Engine's next page is read.
    expect(await queueTitles()).toEqual(['card-fee · Version 2']);
    expect(screen.getByRole('button', { name: /To review/ })).toHaveTextContent('1+');
    await userEvent.click(screen.getByRole('button', { name: 'Show more' }));
    expect(await screen.findByRole('button', { name: /fx-margin · Version 2/ })).toBeInTheDocument();
    expect(await queueTitles()).toEqual(['card-fee · Version 2', 'Customer onboarding (KYC) · Version 3', 'fx-margin · Version 2']);
    expect(cursors).toEqual([null, 'page-2']);
    expect(screen.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument();
  });
});
