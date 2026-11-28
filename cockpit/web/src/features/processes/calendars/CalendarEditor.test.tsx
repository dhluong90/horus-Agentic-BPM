import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../../App';
import { resetProcessEngine } from '../../../mocks/handlers';
import { setScenario } from '../../../mocks/node';

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

/*
 * #125, PD-131: a holder of processes:calendar:manage edits a calendar and proposes the change with a reason; it waits
 * for a second holder, who approves it before it applies. The fake engine answers as the engine does.
 */
describe('The business calendar editor (#125)', () => {
  it('CKP_092_the_list_shows_each_calendar_and_who_approved_its_last_change', async () => {
    resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars');

    const table = await screen.findByRole('table', { name: 'Business calendars' });
    expect(within(table).getByRole('link', { name: 'vn-banking-hours' })).toHaveAttribute('href', '#/calendars/vn-banking-hours');
    expect(within(table).getByText('Asia/Ho_Chi_Minh')).toBeInTheDocument();
  });

  it('CKP_092_a_change_is_proposed_against_the_version_read_and_waits_for_a_second_person', async () => {
    const engine = resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars/vn-banking-hours');

    const send = await screen.findByRole('button', { name: 'Send for approval' });
    expect(send).toBeDisabled();
    await userEvent.click(screen.getByRole('tab', { name: /Holidays/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Add holiday' }));
    await userEvent.type(screen.getByLabelText('Holiday 3, date'), '2027-04-30');
    await userEvent.type(screen.getByLabelText('Holiday 3, name'), 'Reunification Day');
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Reason for the change'), 'Holiday added');
    expect(screen.getByText(/A second person approves before it applies/)).toBeInTheDocument();
    await userEvent.click(send);

    expect(await screen.findByRole('status')).toHaveTextContent('Sent. A second person must approve it before it applies.');
    expect(engine.calendarProposals).toHaveLength(1);
    const sent = engine.calendarProposals[0];
    expect(sent?.ifMatch).toBe('"3"');
    expect(sent?.idempotencyKey).toBeTruthy();
    expect(sent?.body['reason']).toBe('Holiday added');
    expect((sent?.body['calendar'] as { holidays: Record<string, string> }).holidays['2027-04-30']).toBe('Reunification Day');
  });

  it('CKP_092_the_second_person_approves_and_the_calendar_moves_to_its_next_version_with_the_change_in_its_history', async () => {
    const engine = resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars/vn-banking-hours');
    await userEvent.click(await screen.findByRole('tab', { name: /Holidays/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Add holiday' }));
    await userEvent.type(screen.getByLabelText('Holiday 3, date'), '2027-04-30');
    await userEvent.type(screen.getByLabelText('Reason for the change'), 'Holiday added');
    await userEvent.click(screen.getByRole('button', { name: 'Send for approval' }));
    const link = await screen.findByRole('link', { name: 'Open the change' });

    // Another person, Minh, reads the change he is shown and approves it.
    const id = /calendar-changes\/([^/]+)$/.exec(link.getAttribute('href') ?? '')?.[1] ?? '';
    const read = engine.handle({ method: 'GET', path: `/api/bpm/processes/v1/calendar-changes/${id}`, headers: {}, body: '', user: 'minh', permissions: ['processes:read', 'processes:calendar:manage'] });
    const hash = (JSON.parse(read.body) as { contentHash: string }).contentHash;
    const approved = engine.handle({ method: 'POST', path: `/api/bpm/processes/v1/calendar-changes/${id}:approve`, headers: { 'Idempotency-Key': 'k1' }, body: JSON.stringify({ expectedChangeHash: hash }), user: 'minh', permissions: ['processes:read', 'processes:calendar:manage'] });
    expect(approved.status).toBe(200);

    // The calendar is now one version on, and its history names who proposed and who approved the change.
    window.location.hash = '#/calendars';
    await userEvent.click(await screen.findByRole('link', { name: 'vn-banking-hours' }));
    await userEvent.click(await screen.findByRole('tab', { name: 'History' }));
    const row = await screen.findByRole('link', { name: 'Holiday added' });
    expect(row.closest('li')).toHaveTextContent(/proposed by .*approved by/);
    expect(JSON.parse(engine.handle({ method: 'GET', path: '/api/bpm/processes/v1/calendars/vn-banking-hours', headers: {}, body: '', user: 'lan', permissions: ['processes:read'] }).body)).toMatchObject({ version: 4, approvedBy: 'minh' });
  });

  it('CKP_092_the_proposer_cannot_approve_her_own_proposal', async () => {
    const engine = resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars/vn-banking-hours');
    await userEvent.click(await screen.findByRole('tab', { name: /Holidays/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Add holiday' }));
    await userEvent.type(screen.getByLabelText('Holiday 3, date'), '2027-04-30');
    await userEvent.type(screen.getByLabelText('Reason for the change'), 'Holiday added');
    await userEvent.click(screen.getByRole('button', { name: 'Send for approval' }));
    await userEvent.click(await screen.findByRole('link', { name: 'Open the change' }));

    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    expect(screen.getByText('You made this change, so someone else approves it.')).toBeInTheDocument();
    expect(engine.calendarDecisions).toHaveLength(0);
  });

  it('CKP_092_a_calendar_that_moved_on_is_refused_and_offers_to_load_it_again', async () => {
    resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars/vn-banking-hours');
    await userEvent.click(await screen.findByRole('tab', { name: /Holidays/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Add holiday' }));
    await userEvent.type(screen.getByLabelText('Holiday 3, date'), '2027-04-30');
    await userEvent.type(screen.getByLabelText('Reason for the change'), 'Holiday added');
    // Another person's change is approved between the read and the send.
    const { currentProcessEngine } = await import('../../../mocks/handlers');
    currentProcessEngine().advanceCalendar('vn-banking-hours');
    await userEvent.click(screen.getByRole('button', { name: 'Send for approval' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The calendar changed since you opened it. Load it again.');
    await userEvent.click(screen.getByRole('button', { name: 'Load again' }));
    await waitFor(() => { expect(screen.queryByRole('alert')).not.toBeInTheDocument(); });
  });

  it('CKP_092_a_reader_sees_the_calendar_and_no_way_to_change_it', async () => {
    resetProcessEngine();
    setScenario('processes-reviewer');
    open('#/calendars/vn-banking-hours');

    expect(await screen.findByText('You can read this calendar. Changing it needs the calendar permission.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send for approval' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Time zone')).toBeDisabled();
  });

  it('CKP_092_a_new_key_starts_a_calendar_against_version_zero', async () => {
    const engine = resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars');
    await userEvent.type(await screen.findByLabelText('New calendar key'), 'sg-banking-hours');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    await userEvent.type(await screen.findByLabelText('Time zone'), 'Asia/Singapore');
    await userEvent.type(screen.getByLabelText('Reason for the change'), 'First calendar for Singapore');
    await userEvent.click(screen.getByRole('button', { name: 'Send for approval' }));

    await waitFor(() => { expect(engine.calendarProposals).toHaveLength(1); });
    expect(engine.calendarProposals[0]?.ifMatch).toBe('"0"');
    expect(engine.calendarProposals[0]?.key).toBe('sg-banking-hours');
  });
});
