import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { setScenario } from '../../mocks/node';

function open(hash: string, scenario: 'rules-author' | 'rules-approver') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App />);
}

describe('withdraw and start from an earlier version (#502)', () => {
  it('PD_089_the_requester_withdraws_a_pending_request_from_its_version_and_it_returns_to_draft', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2', 'rules-author');
    await user.click(await screen.findByRole('button', { name: /Withdraw/ }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/Reason/), 'Fee needs another look');
    await user.click(within(dialog).getByRole('button', { name: 'Withdraw' }));
    expect(await screen.findByText('Request for Card fee version 2 withdrawn')).toBeInTheDocument();
    await waitFor(() => { expect(screen.getByRole('button', { name: 'Request publish' })).toBeEnabled(); });
    expect(screen.queryByRole('button', { name: /Withdraw/ })).not.toBeInTheDocument();
  });

  it('PD_089_an_approver_who_did_not_request_sees_no_withdraw', async () => {
    open('#/rules/card-fee/2', 'rules-approver');
    await screen.findByRole('button', { name: 'Approve and publish' });
    expect(screen.queryByRole('button', { name: /Withdraw/ })).not.toBeInTheDocument();
  });

  it('RLE_062_a_published_version_offers_start_from_this_version', async () => {
    open('#/rules/card-fee/1', 'rules-author');
    expect(await screen.findByRole('button', { name: 'Start from this version' })).toBeEnabled();
  });
});
