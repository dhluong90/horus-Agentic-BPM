import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../../App';
import { currentProcessEngine, resetProcessEngine } from '../../../mocks/handlers';
import { setScenario } from '../../../mocks/node';
import { KYC_V3_HASH } from '../../../mocks/processDemo';

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

const dock = () => screen.getByRole('region', { name: 'Your decision' });
const approve = () => within(dock()).getByRole('button', { name: /Approve change/ });

describe('CKP-024 process approval with the graph diff', () => {
  it('CKP_024_processes_list_offers_review_change_on_a_waiting_version', async () => {
    setScenario('processes-approver');
    open('#/processes');
    const review = await screen.findByRole('link', { name: 'Review change: Customer onboarding (KYC), Version 3' });
    expect(review).toHaveAttribute('href', '#/processes/kyc-onboarding/3/changes');
    // The name still opens the version in effect.
    expect(screen.getByRole('link', { name: 'Customer onboarding (KYC)' })).toHaveAttribute('href', '#/processes/kyc-onboarding/2');
  });

  it('CKP_024_the_approver_reads_the_change_and_approves_the_hash_on_screen', async () => {
    setScenario('processes-approver');
    open('#/processes/kyc-onboarding/3');
    expect(await screen.findByRole('tab', { name: 'Changes', selected: true })).toBeInTheDocument();
    const list = await screen.findByRole('region', { name: 'What changed' });
    expect(within(list).getByRole('button', { name: 'Added Check for fraud Service task' })).toBeInTheDocument();
    expect(list).toHaveTextContent('Assignee › Role');
    expect(list).toHaveTextContent('500,000,000 VND');
    expect(list).toHaveTextContent('Verify identity documents → Check for fraud');
    const diagram = await screen.findByRole('group', { name: /with changes marked/ });
    expect(within(diagram).getByRole('img', { name: 'Check for fraud, Service task, Added' })).toBeInTheDocument();
    expect(within(diagram).getByRole('img', { name: 'Approve high-risk customer, Person task, Changed' })).toBeInTheDocument();

    await waitFor(() => { expect(approve()).toBeEnabled(); });
    expect(dock()).not.toHaveTextContent(KYC_V3_HASH.slice(0, 12));
    await userEvent.type(within(dock()).getByRole('textbox', { name: 'Comment' }), 'Reviewed with the KYC committee');
    await userEvent.click(approve());

    expect(await within(dock()).findByRole('status')).toHaveTextContent(/^✓ Approved\. In effect from/);
    const [decision] = currentProcessEngine().decisions;
    expect(decision).toEqual(expect.objectContaining({ action: 'approve', by: 'minh', ifMatch: '"2"', body: { expectedDefinitionHash: KYC_V3_HASH, comment: 'Reviewed with the KYC committee' } }));
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('CKP_024_the_requester_sees_approve_disabled_with_one_line_and_cannot_reject', async () => {
    setScenario('processes-requester');
    open('#/processes/kyc-onboarding/3');
    const line = await within(await screen.findByRole('region', { name: 'Your decision' })).findByText('You requested this change, so someone else approves it.');
    expect(approve()).toBeDisabled();
    expect(approve()).toHaveAccessibleDescription(line.textContent);
    expect(within(dock()).queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    expect(within(dock()).queryByRole('textbox')).not.toBeInTheDocument();
    expect(currentProcessEngine().decisions).toEqual([]);
  });

  it('CKP_024_a_reader_without_approve_sees_who_decides', async () => {
    setScenario('processes-reviewer');
    open('#/processes/kyc-onboarding/3');
    expect(await screen.findByText('Waiting for a second person to approve.')).toBeInTheDocument();
    expect(approve()).toBeDisabled();
  });

  it('CKP_024_a_cut_off_change_list_is_too_large_to_approve_here', async () => {
    resetProcessEngine({ truncatedDiff: true });
    setScenario('processes-approver');
    open('#/processes/kyc-onboarding/3');
    expect(await screen.findByText('Too large to approve here')).toBeInTheDocument();
    expect(screen.getByText('The change list stops at 1,000 values, so it is not the whole change.')).toBeInTheDocument();
    expect(approve()).toBeDisabled();
    expect(approve()).toHaveAccessibleDescription('Too large to approve here: the change list is cut off.');
  });

  it('CKP_024_reject_needs_a_reason_and_sends_the_version_back', async () => {
    setScenario('processes-approver');
    open('#/processes/kyc-onboarding/3');
    const reject = await within(await screen.findByRole('region', { name: 'Your decision' })).findByRole('button', { name: 'Reject' });
    expect(reject).toBeDisabled();
    await userEvent.type(within(dock()).getByRole('textbox', { name: 'Comment' }), 'Keep the one-day wait');
    await userEvent.click(reject);
    expect(await within(dock()).findByRole('status')).toHaveTextContent('Rejected. Version 3 is a draft again.');
    expect(currentProcessEngine().decisions).toEqual([expect.objectContaining({ action: 'reject', body: { reason: 'Keep the one-day wait' } })]);
  });

  it('CKP_024_a_first_version_has_nothing_in_effect_to_compare', async () => {
    setScenario('processes-approver');
    open('#/processes/card-dispute/1/changes');
    expect(await screen.findByText('First version: nothing in effect to compare.')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Your decision' })).not.toBeInTheDocument();
  });

  it('CKP_024_vietnamese_reads_the_change_in_words_and_money_with_dots', async () => {
    window.localStorage.setItem('horus.cockpit.locale', 'vi');
    resetProcessEngine({ locale: 'vi' });
    setScenario('processes-approver');
    open('#/processes/kyc-onboarding/3');
    const list = await screen.findByRole('region', { name: 'Những gì thay đổi' });
    expect(list).toHaveTextContent('Người được giao › Vai trò');
    expect(list).toHaveTextContent('500.000.000 VND');
    expect(within(list).getByRole('button', { name: 'Bỏ đi Chờ một ngày Hẹn giờ' })).toBeInTheDocument();
  });
});
