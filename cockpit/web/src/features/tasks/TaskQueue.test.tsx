import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { currentProcessEngine } from '../../mocks/handlers';
import { setScenario } from '../../mocks/node';
import { QUEUE_TASKS } from '../../mocks/taskQueueDemo';

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

const inspector = (name: string | RegExp) => screen.getByRole('complementary', { name });
const tasksRequests = () => currentProcessEngine().requests.filter((request) => request.path.includes('/tasks'));

describe('CKP-046 claim from Available to me', () => {
  it('CKP_046_available_to_me_lists_open_tasks_in_due_order_and_claim_moves_one_after_the_200', async () => {
    setScenario('tasks-operator');
    open('#/tasks?view=candidate');

    const tab = await screen.findByRole('tab', { name: 'Available to me (3)' });
    expect(tab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'My tasks (2)' })).toBeInTheDocument();
    // An holds only processes:task:work: All tasks is not offered.
    expect(screen.queryByRole('tab', { name: /All tasks/ })).not.toBeInTheDocument();

    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1);
    expect(rows.map((row) => within(row).getByRole('rowheader').textContent)).toEqual([
      'Credit reviewCase LN-2026-0142', 'Credit reviewCase LN-2026-0145', 'Verify income documentsCase LN-2026-0147',
    ]);
    // Overdue in words, from the engine's slaBreached list; the role in words, never role:credit-officer.
    expect(within(rows[0] as HTMLElement).getByText('Overdue')).toBeInTheDocument();
    expect(screen.getByText('1 overdue')).toBeInTheDocument();
    expect(within(rows[0] as HTMLElement).getByText('Role: Credit officer')).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText('Group: Loans operations')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/role:|group:|credit-officer/);
    const query = tasksRequests().find((request) => request.path.includes('assignment=candidate'));
    expect(query?.path).toContain('assignment=candidate&status=OPEN&limit=50');
    expect(tasksRequests().some((request) => request.path.includes('slaBreached=true'))).toBe(true);

    const selected = inspector('Credit review');
    await userEvent.click(await within(selected).findByRole('button', { name: 'Claim' }));

    expect(await screen.findByText('You claimed Credit review. It is in My tasks.')).toBeInTheDocument();
    const claim = currentProcessEngine().requests.find((request) => request.path.endsWith(':claim'));
    expect(claim?.path).toBe(`/api/bpm/processes/v1/tasks/${QUEUE_TASKS.creditReviewOverdue}:claim`);
    expect(claim?.headers['if-match']).toBe('"0"');
    expect(claim?.headers['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByRole('tab', { name: 'My tasks (3)' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Available to me (2)' })).toBeInTheDocument();
  });

  it('CKP_046_a_task_claimed_first_by_a_colleague_names_them_and_leaves_the_list', async () => {
    setScenario('tasks-operator');
    open('#/tasks?view=candidate');
    const claim = await within(await screen.findByRole('complementary', { name: 'Credit review' })).findByRole('button', { name: 'Claim' });
    currentProcessEngine().claimAs(QUEUE_TASKS.creditReviewOverdue, 'hoa');
    await userEvent.click(claim);

    expect(await screen.findByRole('alert')).toHaveTextContent('Hoa Tran claimed this task first.');
    await waitFor(() => { expect(screen.getByRole('tab', { name: 'Available to me (2)' })).toBeInTheDocument(); });
    expect(screen.queryByText('Case LN-2026-0142')).not.toBeInTheDocument();
    // No code, no raw body on the surface.
    expect(document.body).not.toHaveTextContent(/INVALID_STATE|409|\{/);
  });

  it('CKP_046_a_task_the_user_may_not_claim_shows_claim_unavailable_with_the_reason', async () => {
    setScenario('tasks-supervisor');
    open(`#/tasks?view=all&task=${QUEUE_TASKS.collateral}`);
    const selected = await screen.findByRole('complementary', { name: 'Collateral valuation' });
    await within(selected).findByText('Claimed by Hoa Tran', { selector: '.badge' });
    // Sau may hand it on, so Reassign is offered; there is no Claim for a task someone else holds.
    expect(within(selected).getByRole('button', { name: 'Reassign' })).toBeEnabled();
    expect(within(selected).queryByRole('button', { name: 'Claim' })).not.toBeInTheDocument();
  });
});

describe('CKP-047 release', () => {
  it('CKP_047_release_hands_my_task_back_to_available_after_the_200', async () => {
    setScenario('tasks-operator');
    open(`#/tasks?view=mine&task=${QUEUE_TASKS.myCreditReview}`);
    const selected = await screen.findByRole('complementary', { name: 'Credit review' });
    expect(await within(selected).findByRole('link', { name: 'Open task' })).toHaveAttribute('href', `#/tasks/${QUEUE_TASKS.myCreditReview}`);
    await userEvent.click(within(selected).getByRole('button', { name: 'Release' }));

    expect(await screen.findByText('Credit review is back in Available to me.')).toBeInTheDocument();
    const release = currentProcessEngine().requests.find((request) => request.path.endsWith(':unclaim'));
    expect(release?.headers['if-match']).toBe('"1"');
    expect(release?.headers['idempotency-key']).toBeDefined();
    expect(await screen.findByRole('tab', { name: 'Available to me (4)' })).toBeInTheDocument();
  });
});

describe('CKP-048 reassign with a reason', () => {
  it('CKP_048_reassign_sends_reassign_for_an_open_task_and_delegate_for_a_claimed_one_with_the_reason', async () => {
    setScenario('tasks-supervisor');
    open(`#/tasks?view=all&task=${QUEUE_TASKS.creditReview}`);

    await userEvent.click(await within(await screen.findByRole('complementary', { name: 'Credit review' })).findByRole('button', { name: 'Reassign' }));
    let dialog = await screen.findByRole('dialog', { name: 'Reassign Credit review' });
    const submit = within(dialog).getByRole('button', { name: 'Reassign' });
    // Unavailable until a user id and a reason are entered, and it says so.
    expect(submit).toHaveAttribute('aria-disabled', 'true');
    expect(submit).toHaveAccessibleDescription('Enter a user id and a reason to reassign.');
    await userEvent.type(within(dialog).getByLabelText("Colleague's user id"), 'hoa');
    expect(submit).toHaveAccessibleDescription('Enter a reason to reassign.');
    await userEvent.click(submit);
    expect(currentProcessEngine().requests.some((request) => request.path.endsWith(':reassign'))).toBe(false);
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Covering leave');
    expect(within(dialog).getByText('14 of 500 characters')).toBeInTheDocument();
    expect(submit).not.toHaveAttribute('aria-disabled');
    await userEvent.click(submit);

    expect(await screen.findByText('Credit review is now with Hoa Tran.')).toBeInTheDocument();
    const reassign = currentProcessEngine().requests.find((request) => request.path.endsWith(':reassign'));
    expect(reassign?.path).toBe(`/api/bpm/processes/v1/tasks/${QUEUE_TASKS.creditReview}:reassign`);
    expect(JSON.parse(reassign?.body ?? '{}')).toEqual({ assignee: 'hoa', reason: 'Covering leave' });
    expect(reassign?.headers['if-match']).toBe('"0"');
    expect(reassign?.headers['idempotency-key']).toBeDefined();

    // A claimed task is delegated.
    window.location.hash = `#/tasks?view=all&task=${QUEUE_TASKS.myCreditReview}`;
    await waitFor(() => { expect(within(screen.getByRole('complementary', { name: 'Credit review' })).getByText(/Claimed by An Nguyen/, { selector: '.badge' })).toBeInTheDocument(); });
    const claimed = screen.getByRole('complementary', { name: 'Credit review' });
    await userEvent.click(within(claimed).getByRole('button', { name: 'Reassign' }));
    dialog = await screen.findByRole('dialog', { name: 'Reassign Credit review' });
    await userEvent.type(within(dialog).getByLabelText("Colleague's user id"), 'hoa');
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Covering leave');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reassign' }));
    await waitFor(() => { expect(currentProcessEngine().handOvers).toHaveLength(2); });
    expect(currentProcessEngine().handOvers).toEqual([
      { taskId: QUEUE_TASKS.creditReview, command: 'reassign', actor: 'sau', from: null, to: 'hoa', reason: 'Covering leave' },
      { taskId: QUEUE_TASKS.myCreditReview, command: 'delegate', actor: 'sau', from: 'an', to: 'hoa', reason: 'Covering leave' },
    ]);
  });

  it('CKP_048_a_target_the_engine_refuses_reads_as_a_plain_sentence_and_the_task_stays', async () => {
    setScenario('tasks-supervisor');
    open(`#/tasks?view=all&task=${QUEUE_TASKS.approveLimit}`);
    const selected = await screen.findByRole('complementary', { name: 'Approve credit limit' });
    expect(await within(selected).findByText('Four eyes: someone new to this case must do it.')).toBeInTheDocument();
    await userEvent.click(within(selected).getByRole('button', { name: 'Reassign' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reassign Approve credit limit' });
    await userEvent.type(within(dialog).getByLabelText("Colleague's user id"), 'hoa');
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Covering leave');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reassign' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Hoa Tran worked on this case, so someone else must take this task.');
    expect(dialog).not.toHaveTextContent(/ASSIGNEE_NOT_ALLOWED|409/);
    // The reason is kept for another colleague; the task did not move.
    expect(within(dialog).getByLabelText('Reason')).toHaveValue('Covering leave');
    expect(currentProcessEngine().handOvers).toEqual([]);
  });

  it('CKP_048_without_the_manage_permission_all_tasks_and_reassign_are_not_shown', async () => {
    setScenario('tasks-operator');
    open(`#/tasks?view=all&task=${QUEUE_TASKS.myCreditReview}`);
    // The link asks for All tasks; An lands on My tasks and is never offered All or Reassign.
    expect(await screen.findByRole('tab', { name: /My tasks/, selected: true })).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: /All tasks/ })).not.toBeInTheDocument();
    const selected = await screen.findByRole('complementary', { name: 'Credit review' });
    await within(selected).findByRole('button', { name: 'Release' });
    expect(screen.queryByRole('button', { name: 'Reassign' })).not.toBeInTheDocument();
    expect(tasksRequests().some((request) => request.path.includes('assignment=any'))).toBe(false);
  });

  it('CKP_048_masked_values_of_a_task_stay_masked_and_are_never_shown', async () => {
    setScenario('tasks-supervisor');
    open('#/tasks?view=all&task=01JD4T5REV1EW000000000AT05');
    const selected = await screen.findByRole('complementary', { name: 'Assess documents' });
    expect(await within(selected).findByText('Some values of this task are masked.')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/hmac|\*\*\*|9f4a/);
    expect(within(selected).getByText('Role: Compliance reviewer')).toBeInTheDocument();
  });
});
