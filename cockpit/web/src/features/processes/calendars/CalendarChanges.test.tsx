import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../../App';
import { currentProcessEngine, resetProcessEngine } from '../../../mocks/handlers';
import { setScenario } from '../../../mocks/node';

const CHANGE = '01KB2R6Q3C0000000000000CAL';

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

/*
 * #473, PD-139: a calendar change waits for a second person in the approvals queue and under Processes. Lan proposed
 * the holiday 1 January 2028 for vn-banking-hours; Minh approves it, and only the change he was shown.
 */
describe('Calendar changes in the approvals queue (#473)', () => {
  it('CKP_091_a_pending_calendar_change_is_in_the_approvals_queue_and_opens_its_review', async () => {
    resetProcessEngine({ calendar: true });
    setScenario('calendar-approver');
    open('#/approvals');

    const card = await screen.findByRole('button', { name: /Calendar Vn banking hours/ });
    await userEvent.click(card);
    const preview = await screen.findByRole('article', { name: 'Calendar Vn banking hours' });
    expect(within(preview).getByText('Calendar')).toBeInTheDocument();
    expect(within(preview).getByText('New Year 2028 is a public holiday')).toBeInTheDocument();
    expect(within(preview).getByRole('link', { name: /Review and approve/ })).toHaveAttribute('href', `#/calendar-changes/${CHANGE}`);
  });

  it('CKP_091_approve_names_the_change_the_approver_was_shown', async () => {
    const engine = resetProcessEngine({ calendar: true });
    setScenario('calendar-approver');
    open(`#/calendar-changes/${CHANGE}`);

    expect(await screen.findByRole('heading', { level: 1, name: 'Calendar Vn banking hours' })).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    await waitFor(() => { expect(engine.calendarDecisions).toHaveLength(1); });
    const sent = engine.calendarDecisions[0];
    expect(sent?.command).toBe('approve');
    expect(sent?.body['expectedChangeHash']).toMatch(/^[0-9a-f]{64}$/);
    expect(await screen.findByRole('status')).toHaveTextContent(/Approved by .*Proposed by/);
  });

  it('CKP_091_the_proposer_cannot_approve_her_own_change_and_is_told_why', async () => {
    const engine = resetProcessEngine({ calendar: true });
    setScenario('calendar-proposer');
    open(`#/calendar-changes/${CHANGE}`);

    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    expect(screen.getByText('You made this change, so someone else approves it.')).toBeInTheDocument();
    expect(currentProcessEngine().calendarDecisions).toHaveLength(0);
    expect(engine.calendarDecisions).toHaveLength(0);
  });

  it('CKP_091_the_list_under_processes_opens_the_pending_change', async () => {
    resetProcessEngine({ calendar: true });
    setScenario('calendar-approver');
    open('#/processes');

    await userEvent.click(await screen.findByRole('link', { name: 'Calendar changes' }));
    expect(await screen.findByRole('link', { name: 'Vn banking hours' })).toHaveAttribute('href', `#/calendar-changes/${CHANGE}`);
  });
});
