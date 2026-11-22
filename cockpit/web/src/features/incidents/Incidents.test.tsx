import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { currentProcessEngine, resetProcessEngine } from '../../mocks/handlers';
import { INCIDENTS } from '../../mocks/incidentDemo';
import { setScenario } from '../../mocks/node';
import type { Scenario } from '../../mocks/fixtures';

function open(hash: string, scenario: Scenario) {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App />);
}

const inspector = (name: string) => screen.getByRole('complementary', { name });
const commands = () => currentProcessEngine().incidentCommands;

describe('CKP-008 incident queue with retry and resolve', () => {
  it('CKP_008_incident_queue_retries_and_updates_status', async () => {
    open(`#/incidents?incident=${INCIDENTS.agentTimeout}`, 'incidents-operator');

    expect(await screen.findByRole('tab', { name: 'Open (5)' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Waiting for approval (1)' })).toBeInTheDocument();
    // Newest first; each step by its name in the process, the cause in words, the retry count as a number; no code.
    await waitFor(() => {
      const names = within(screen.getByRole('table')).getAllByRole('rowheader').map((cell) => cell.textContent);
      expect(names).toEqual(['Screen sanctions lists', 'Credit decision', 'Book loan', 'Assess documents', 'Credit decision']);
    });
    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1);
    const agent = rows[3] as HTMLElement;
    expect(within(agent).getByText('The AI agent did not answer in time')).toBeInTheDocument();
    expect(within(agent).getByRole('cell', { name: '2' })).toBeInTheDocument();
    expect(screen.getByRole('table')).not.toHaveTextContent(/AGENT_TIMEOUT|_/);

    const selected = inspector('Assess documents');
    expect(await within(selected).findByRole('link', { name: 'APP-1045' })).toHaveAttribute('href', '#/instances/01JD4I5NC1DENT00000000IN05');
    await userEvent.click(within(selected).getByRole('button', { name: 'Retry' }));
    const dialog = await screen.findByRole('dialog', { name: 'Retry Assess documents' });
    const send = within(dialog).getByRole('button', { name: 'Retry' });
    expect(send).toHaveAttribute('aria-disabled', 'true');
    expect(send).toHaveAccessibleDescription('Enter a reason to continue.');
    await userEvent.click(send);
    expect(commands()).toHaveLength(0);
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Agent service back after maintenance');
    await userEvent.click(send);

    expect(await screen.findByText('Retry scheduled for Assess documents. The result shows on the next refresh.')).toBeInTheDocument();
    const retry = commands()[0];
    expect(retry).toMatchObject({ incidentId: INCIDENTS.agentTimeout, command: 'retry', actor: 'tuan', body: { reason: 'Agent service back after maintenance' }, ifMatch: '"0"' });
    expect(retry?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    // Only after the engine's answer: the row shows the retry running until the step is accepted (the next refresh,
    // checked end to end in incidents.spec.ts), and Retry is not offered again meanwhile.
    expect(await within(screen.getByRole('table')).findByText('Retrying')).toBeInTheDocument();
    const retried = inspector('Assess documents');
    await waitFor(() => { expect(within(retried).getByRole('button', { name: 'Retry' })).toHaveAccessibleDescription('A retry is running. The result shows on the next refresh.'); });
  });

  it('CKP_008_a_stale_incident_reads_as_a_plain_sentence_and_is_read_again', async () => {
    open(`#/incidents?incident=${INCIDENTS.coreTimeout}`, 'incidents-operator');
    const selected = await screen.findByRole('complementary', { name: 'Screen sanctions lists' });
    await userEvent.click(await within(selected).findByRole('button', { name: 'Retry' }));
    currentProcessEngine().touchIncident(INCIDENTS.coreTimeout);
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Core banking back');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Retry' }));
    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('Someone changed this incident. It has been read again; check it, then try again.');
    expect(within(alert).getByText(/VERSION_CONFLICT/)).not.toBeVisible();
  });

  it('CKP_008_a_manual_undo_offers_resolve_only_and_says_why_retry_is_unavailable', async () => {
    open(`#/incidents?incident=${INCIDENTS.manualUndo}`, 'incidents-operator');
    const selected = await screen.findByRole('complementary', { name: 'Book loan' });
    const retry = await within(selected).findByRole('button', { name: 'Retry' });
    expect(retry).toHaveAttribute('aria-disabled', 'true');
    expect(retry).toHaveAccessibleDescription('Nothing to run again: undo the step by hand, then resolve.');
    expect(within(selected).getByRole('button', { name: 'Resolve' })).toBeEnabled();
  });

  it('CKP_008_a_reader_sees_the_queue_and_no_action', async () => {
    open('#/incidents', 'processes-reviewer');
    const selected = await screen.findByRole('complementary', { name: 'Screen sanctions lists' });
    expect(await within(selected).findByText('You can view incidents; fixing them needs the incident permission.')).toBeInTheDocument();
    expect(within(selected).queryByRole('button', { name: /Retry|Resolve/ })).not.toBeInTheDocument();
  });
});

describe('CKP-080 resolving an incident needs a second person', () => {
  it('CKP_080_a_proposed_result_is_built_from_fields_and_waits_for_a_colleague', async () => {
    open(`#/incidents?incident=${INCIDENTS.limitRefused}`, 'incidents-operator');
    const selected = await screen.findByRole('complementary', { name: 'Credit decision' });
    await userEvent.click(await within(selected).findByRole('button', { name: 'Resolve' }));
    const dialog = await screen.findByRole('dialog', { name: 'Resolve Credit decision' });
    expect(dialog).toHaveTextContent('A colleague approves it before the case moves on.');

    // A wrong field name: the engine names the failing path, and the field says so; nothing moved.
    await userEvent.type(within(dialog).getByLabelText('Field 1 name'), 'limit');
    await userEvent.selectOptions(within(dialog).getByLabelText('Field 1 kind'), 'amount');
    await userEvent.type(within(dialog).getByLabelText('Field 1 value'), '30000000');
    expect(within(dialog).getByLabelText('Field 1 value')).toHaveValue('30,000,000');
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Limit raised by credit committee, memo CC-118');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Propose result' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('The result does not match what the step expects: check Approved limit.');

    await userEvent.clear(within(dialog).getByLabelText('Field 1 name'));
    await userEvent.type(within(dialog).getByLabelText('Field 1 name'), 'approvedLimit');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Propose result' }));

    expect(await screen.findByText('Result proposed for Credit decision. A colleague approves it next.')).toBeInTheDocument();
    const proposal = commands().at(-1);
    expect(proposal).toMatchObject({ command: 'resolve', body: { output: { approvedLimit: { amount: 30000000, currency: 'VND' } }, reason: 'Limit raised by credit committee, memo CC-118' } });
    expect(await screen.findByRole('tab', { name: 'Waiting for approval (2)' })).toHaveAttribute('aria-selected', 'true');
    const pending = await screen.findByRole('complementary', { name: 'Credit decision' });
    expect(await within(pending).findByText('30,000,000 VND')).toBeInTheDocument();
    // Tuan proposed it: Approve is unavailable to him, with the reason beside it.
    const approve = within(pending).getByRole('button', { name: 'Approve result' });
    expect(approve).toHaveAttribute('aria-disabled', 'true');
    expect(approve).toHaveAccessibleDescription('You proposed this result, so a colleague approves it.');
  }, 15000);

  it('CKP_080_a_second_person_approves_the_proposal_and_the_proposer_cannot', async () => {
    open(`#/incidents?view=pending&incident=${INCIDENTS.proposed}`, 'incidents-operator');
    const selected = await screen.findByRole('complementary', { name: 'Verify identity documents' });
    expect(await within(selected).findByText(/^Proposed 21/)).toBeInTheDocument();
    expect(within(selected).getAllByText('Proposed by linh').length).toBeGreaterThan(0);
    expect(within(selected).getByText('50,000 VND')).toBeInTheDocument();
    expect(within(selected).getByText('Yes')).toBeInTheDocument();
    expect(selected).not.toHaveTextContent(/\{|\}|documentsVerified/);
    await userEvent.click(within(selected).getByRole('button', { name: 'Approve result' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve the result for Verify identity documents' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve result' }));
    expect(await screen.findByText('Result approved for Verify identity documents. The case moves on.')).toBeInTheDocument();
    expect(commands().at(-1)).toMatchObject({ incidentId: INCIDENTS.proposed, command: 'approve-resolution', actor: 'tuan', body: { comment: null }, ifMatch: '"1"' });
    expect(await screen.findByText('No result is waiting for approval.')).toBeInTheDocument();
  });

  it('CKP_080_the_proposer_sees_approve_unavailable_with_the_reason', async () => {
    open(`#/incidents?view=pending&incident=${INCIDENTS.proposed}`, 'incidents-proposer');
    const selected = await screen.findByRole('complementary', { name: 'Verify identity documents' });
    const approve = await within(selected).findByRole('button', { name: 'Approve result' });
    expect(approve).toHaveAttribute('aria-disabled', 'true');
    expect(approve).toHaveAccessibleDescription('You proposed this result, so a colleague approves it.');
    expect(within(selected).queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
  });

  it('CKP_080_a_rejected_proposal_opens_the_incident_again_with_the_reason', async () => {
    open(`#/incidents?view=pending&incident=${INCIDENTS.proposed}`, 'incidents-operator');
    const selected = await screen.findByRole('complementary', { name: 'Verify identity documents' });
    await userEvent.click(await within(selected).findByRole('button', { name: 'Reject' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Memo KYC-77 is not signed');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reject' }));
    expect(await screen.findByText('Result rejected for Verify identity documents. The incident is open again.')).toBeInTheDocument();
    expect(commands().at(-1)).toMatchObject({ command: 'reject-resolution', body: { reason: 'Memo KYC-77 is not signed' } });
  });

  it('CKP_080_under_the_single_setting_of_a_dev_host_a_resolution_applies_at_once', async () => {
    resetProcessEngine({ resolveApproval: 'single' });
    setScenario('incidents-operator');
    window.location.hash = `#/incidents?incident=${INCIDENTS.manualUndo}`;
    const { container } = render(<App />);
    expect(container).toBeInTheDocument();
    const selected = await screen.findByRole('complementary', { name: 'Book loan' });
    await userEvent.click(await within(selected).findByRole('button', { name: 'Resolve' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Record how the step was undone by hand');
    await userEvent.type(within(dialog).getByLabelText('Field 1 name'), 'reversalReference');
    await userEvent.type(within(dialog).getByLabelText('Field 1 value'), 'RV-2026-0042');
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Reversed in core banking');
    // The host's /meta still says four eyes here, so the dialog proposes; the engine applied it at once.
    await userEvent.click(within(dialog).getByRole('button', { name: /Propose result|Resolve/ }));
    expect(await screen.findByText('Book loan resolved. The case moves on.')).toBeInTheDocument();
  });
});
