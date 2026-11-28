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
    expect(within(table).getByRole('link', { name: 'Vn banking hours' })).toHaveAttribute('href', '#/calendars/vn-banking-hours');
    expect(within(table).getByText('Ho Chi Minh City (UTC+7)')).toBeInTheDocument();
    expect(table).not.toHaveTextContent('Asia/Ho_Chi_Minh');
    expect(table).not.toHaveTextContent('vn-banking-hours');
  });

  it('CKP_092_a_change_is_proposed_against_the_version_read_and_waits_for_a_second_person', async () => {
    const engine = resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars/vn-banking-hours');

    await userEvent.click(await screen.findByRole('button', { name: 'Edit calendar' }));
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
    await userEvent.click(await screen.findByRole('button', { name: 'Edit calendar' }));
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
    await userEvent.click(await screen.findByRole('link', { name: 'Vn banking hours' }));
    await userEvent.click(await screen.findByRole('tab', { name: 'History' }));
    const row = await screen.findByRole('link', { name: 'Holiday added' });
    expect(row.closest('li')).toHaveTextContent(/Approved.*Approved by .*Proposed by/);
    expect(JSON.parse(engine.handle({ method: 'GET', path: '/api/bpm/processes/v1/calendars/vn-banking-hours', headers: {}, body: '', user: 'lan', permissions: ['processes:read'] }).body)).toMatchObject({ version: 4, approvedBy: 'minh' });
  });

  it('CKP_092_the_proposer_cannot_approve_her_own_proposal', async () => {
    const engine = resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars/vn-banking-hours');
    await userEvent.click(await screen.findByRole('button', { name: 'Edit calendar' }));
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
    await userEvent.click(await screen.findByRole('button', { name: 'Edit calendar' }));
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

  it('CKP_092_a_pending_change_reads_as_waiting_with_who_proposed_it_and_when_and_no_chip', async () => {
    resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars/vn-banking-hours');
    await userEvent.click(await screen.findByRole('button', { name: 'Edit calendar' }));
    await userEvent.click(await screen.findByRole('tab', { name: /Holidays/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Add holiday' }));
    await userEvent.type(screen.getByLabelText('Holiday 3, date'), '2027-04-30');
    await userEvent.type(screen.getByLabelText('Reason for the change'), 'Holiday added');
    await userEvent.click(screen.getByRole('button', { name: 'Send for approval' }));
    await screen.findByRole('link', { name: 'Open the change' });
    window.location.hash = '#/calendar-changes';

    const row = (await screen.findByRole('link', { name: 'Vn banking hours' })).closest('li');
    expect(row).toHaveTextContent(/Waiting for a second person.*Proposed by .*, /);
    expect(row?.querySelector('.change-state__dot')).not.toBeNull();
  });

  it('CKP_092_hours_are_read_first_and_one_edit_control_opens_them', async () => {
    resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars/vn-banking-hours');

    await screen.findByText('Ho Chi Minh City (UTC+7)');
    expect(screen.queryByLabelText('Mon, range 1, from')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add hours on/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Edit calendar' }));
    expect(screen.getByLabelText('Mon, range 1, from')).toBeInTheDocument();
    expect(screen.getByLabelText('Time zone')).toBeInTheDocument();
  });

  it('CKP_092_a_reader_sees_the_calendar_and_no_way_to_change_it', async () => {
    resetProcessEngine();
    setScenario('processes-reviewer');
    open('#/calendars/vn-banking-hours');

    expect(await screen.findByText('You can read this calendar. Changing it needs the calendar permission.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send for approval' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit calendar' })).not.toBeInTheDocument();
    expect(screen.getByText('Ho Chi Minh City (UTC+7)')).toBeInTheDocument();
    expect(screen.queryByText('Asia/Ho_Chi_Minh')).not.toBeInTheDocument();
  });

  it('CKP_092_a_new_calendar_is_named_in_words_and_starts_against_version_zero', async () => {
    const engine = resetProcessEngine();
    setScenario('calendar-proposer');
    open('#/calendars');
    await userEvent.type(await screen.findByLabelText('New calendar name'), 'Singapore banking hours');
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    await userEvent.selectOptions(await screen.findByLabelText('Time zone'), 'Singapore (UTC+8)');
    await userEvent.type(screen.getByLabelText('Reason for the change'), 'First calendar for Singapore');
    await userEvent.click(screen.getByRole('button', { name: 'Send for approval' }));

    await waitFor(() => { expect(engine.calendarProposals).toHaveLength(1); });
    expect(engine.calendarProposals[0]?.ifMatch).toBe('"0"');
    expect(engine.calendarProposals[0]?.key).toBe('singapore-banking-hours');
  });
});
