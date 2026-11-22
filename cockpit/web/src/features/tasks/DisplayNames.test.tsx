import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { currentProcessEngine, resetProcessEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { QUEUE_TASKS } from '../../mocks/taskQueueDemo';

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

/** Every display-names request the screen sent, as the query the host saw and the language it asked in. */
function lookups(): { query: string; language: string | null }[] {
  const seen: { query: string; language: string | null }[] = [];
  mockHost.events.on('request:start', ({ request }) => {
    if (request.url.includes('/api/bpm/display-names')) seen.push({ query: new URL(request.url).search, language: request.headers.get('accept-language') });
  });
  return seen;
}

describe('#415 and #442 display names in the task queue', () => {
  it('names_of_roles_groups_and_people_replace_identifiers_in_the_queue_and_the_inspector', async () => {
    const seen = lookups();
    setScenario('tasks-supervisor');
    open(`#/tasks?view=all&task=${QUEUE_TASKS.collateral}`);

    const rows = within(await screen.findByRole('table')).getAllByRole('row').slice(1);
    await waitFor(() => { expect(within(rows[0] as HTMLElement).getByText('Role: Credit officer')).toBeInTheDocument(); });
    expect(screen.getByText('Group: Loans operations')).toBeInTheDocument();
    const selected = await screen.findByRole('complementary', { name: 'Collateral valuation' });
    expect(await within(selected).findByText('Claimed by Hoa Tran', { selector: '.badge' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/credit-officer|loans-operations|\bhoa\b/);
    // One lookup for the queue, with every role, group and user it shows, in the language of the screen.
    expect(seen.length).toBeGreaterThan(0);
    expect(seen.some((call) => call.query.includes('roles=') && call.query.includes('groups=loans-operations') && call.query.includes('users='))).toBe(true);
    expect(seen.every((call) => call.language === 'en')).toBe(true);
  });

  it('a_role_in_vietnamese_is_the_titled_vietnamese_name_and_a_role_without_title_shows_its_key_in_words', async () => {
    window.localStorage.setItem('horus.cockpit.locale', 'vi');
    resetProcessEngine({ locale: 'vi' });
    setScenario('tasks-supervisor');
    open('#/tasks?view=all');

    expect(await screen.findAllByText('Vai trò: Cán bộ tín dụng')).not.toHaveLength(0);
    expect(screen.getByText('Nhóm: Vận hành tín dụng')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/Credit officer|Loans operations/);
  });

  it('a_lookup_that_fails_leaves_the_words_of_the_identifier_and_no_error', async () => {
    mockHost.use(http.get('*/api/bpm/display-names', () => HttpResponse.json({ status: 500, code: 'INTERNAL' }, { status: 500, headers: { 'Content-Type': 'application/problem+json' } })));
    setScenario('tasks-supervisor');
    open('#/tasks?view=all');

    expect(await screen.findAllByText('Role: Credit officer')).not.toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('#442 a refused hand-over says why in one plain sentence', () => {
  async function refuse(taskId: string, name: string, colleague: string) {
    setScenario('tasks-supervisor');
    open(`#/tasks?view=all&task=${taskId}`);
    const selected = await screen.findByRole('complementary', { name });
    await userEvent.click(await within(selected).findByRole('button', { name: 'Reassign' }));
    const dialog = await screen.findByRole('dialog', { name: `Reassign ${name}` });
    await userEvent.type(within(dialog).getByLabelText("Colleague's user id"), colleague);
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Covering leave');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reassign' }));
    return within(dialog).findByRole('alert');
  }

  it('MISSING_ROLE_names_the_person_and_the_role_they_lack_by_their_titles', async () => {
    const alert = await refuse(QUEUE_TASKS.creditReview, 'Credit review', 'minh');
    expect(alert).toHaveTextContent('Minh Vo does not have the role Credit officer.');
    expect(alert).not.toHaveTextContent(/MISSING_ROLE|role:|credit-officer/);
    expect(currentProcessEngine().handOvers).toEqual([]);
  });

  it('MISSING_GROUP_names_the_group_by_its_title', async () => {
    const alert = await refuse(QUEUE_TASKS.incomeCheck, 'Verify income documents', 'lan');
    expect(alert).toHaveTextContent('Lan Pham is not in the group Loans operations.');
  });

  it('ALREADY_ASSIGNED_says_the_colleague_holds_the_task', async () => {
    const alert = await refuse(QUEUE_TASKS.collateral, 'Collateral valuation', 'hoa');
    expect(alert).toHaveTextContent('Hoa Tran already holds this task.');
  });

  it('a_reason_this_cockpit_does_not_know_keeps_the_general_sentence', async () => {
    mockHost.use(http.post('*/api/bpm/processes/v1/tasks/:id\\:reassign', () => HttpResponse.json(
      { status: 409, code: 'ASSIGNEE_NOT_ALLOWED', reason: 'SOMETHING_NEW' }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } })));
    const alert = await refuse(QUEUE_TASKS.creditReview, 'Credit review', 'hoa');
    expect(alert).toHaveTextContent('Hoa Tran cannot take this task.');
    expect(alert).not.toHaveTextContent(/SOMETHING_NEW/);
  });
});
