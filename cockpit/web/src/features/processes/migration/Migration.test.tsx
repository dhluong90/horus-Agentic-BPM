import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../../App';
import { currentProcessEngine, resetProcessEngine } from '../../../mocks/handlers';
import { MIGRATION_PLAN_ID } from '../../../mocks/migrationDemo';
import { setScenario } from '../../../mocks/node';

const PLAN = `#/migrations/${MIGRATION_PLAN_ID}`;

function open(hash: string, locale?: 'vi') {
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const dock = () => screen.getByRole('region', { name: 'Next step' });
const checkPlan = async () => { await userEvent.click(await within(await screen.findByRole('region', { name: 'Next step' })).findByRole('button', { name: 'Check plan' })); };

/** A plan made straight on the engine, as another client would, so the screen reads it back. */
function createPlan(user: string, body: Record<string, unknown>): string {
  const answer = currentProcessEngine().handle({
    method: 'POST', path: 'http://host.test/api/bpm/processes/v1/migrations', headers: { 'Idempotency-Key': 'k-1' }, body: JSON.stringify(body),
    user, permissions: ['processes:migration:manage'],
  });
  return (JSON.parse(answer.body) as { id: string }).id;
}

describe('CKP-027 the migration plan as two diagrams', () => {
  it('CKP_027_migration_plan_side_by_side_diagrams', async () => {
    resetProcessEngine({ migration: { state: 'planned' } });
    setScenario('migration-operator');
    open(PLAN);

    expect(await screen.findByRole('heading', { level: 1, name: 'Move running cases' })).toBeInTheDocument();
    expect(screen.getByText('Customer onboarding (KYC), version 2 to version 3')).toBeInTheDocument();
    const now = await screen.findByRole('group', { name: /^Diagram of version 2/ });
    const next = await screen.findByRole('group', { name: /^Diagram of version 3/ });
    // The step the plan carries over is marked on both sides, in words.
    expect(within(now).getByRole('button', { name: /^Approve high-risk customer.*Carried over/ })).toBeInTheDocument();
    expect(within(next).getByRole('button', { name: /^Senior credit approval.*Carried over/ })).toBeInTheDocument();

    await checkPlan();
    expect(await screen.findByRole('heading', { name: /50 cases can move\./ })).toBeInTheDocument();
    // After the check the steps carry their cases: linked by the badge on each side.
    expect(await within(now).findByRole('button', { name: /14 cases wait here/ })).toBeInTheDocument();
    expect(within(next).getByRole('button', { name: /Senior credit approval.*14 cases arrive here/ })).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'Steps' }));
    const table = screen.getByRole('table', { name: 'How each changed step is carried over' });
    const row = within(table).getByRole('button', { name: 'Approve high-risk customer' }).closest('tr');
    expect(row).toHaveTextContent('Senior credit approval');
    expect(row).toHaveTextContent('14 cases');
    expect(table).toHaveTextContent('Wait one day');
    expect(table).toHaveTextContent('2 stay: a call is running');
  });

  it('CKP_027_unmapped_steps_with_waiting_cases_are_flagged_and_block_the_move', async () => {
    resetProcessEngine({ migration: { state: 'none' } });
    setScenario('migration-operator');
    const id = createPlan('lan', { definitionKey: 'kyc-onboarding', sourceVersion: 2, targetVersion: 3, mappings: [{ from: 'approve', to: 'senior-approval' }], cancellations: [] });
    open(`#/migrations/${id}`);

    await checkPlan();
    expect(await screen.findByRole('heading', { name: /22 cases can move\. 30 cannot\./ })).toBeInTheDocument();
    expect(screen.getByText(/30 cases wait at steps the plan does not cover\./)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Steps' }));
    const row = within(screen.getByRole('table', { name: 'How each changed step is carried over' })).getByRole('button', { name: 'Wait one day' }).closest('tr');
    expect(row).toHaveTextContent('Not mapped');
    // The one action is shown disabled with one line saying why, and the way out.
    const propose = within(dock()).getByRole('button', { name: /Ask approval to move 22 cases/ });
    expect(propose).toBeDisabled();
    expect(propose).toHaveAccessibleDescription(/Some cases wait at steps the plan does not cover\./);
    expect(within(dock()).getByRole('link', { name: 'Make a new plan' })).toHaveAttribute('href', '#/processes/kyc-onboarding/3/move?from=2');
  });
});

describe('CKP-056 the dry run in plain words', () => {
  it('CKP_056_dry_run_counts_the_cases_in_words_and_changes_nothing', async () => {
    resetProcessEngine({ migration: { state: 'planned' } });
    setScenario('migration-operator');
    open(PLAN);

    expect(await screen.findByText('Not checked yet')).toBeInTheDocument();
    // Before a check the plan cannot be proposed: only Check plan is offered.
    expect(within(dock()).queryByRole('textbox')).not.toBeInTheDocument();
    await checkPlan();

    const lead = await screen.findByRole('heading', { name: '50 cases can move. 2 cannot.' });
    const card = lead.closest('article');
    expect(card).toHaveTextContent('14 cases move to a renamed step.');
    expect(card).toHaveTextContent('28 cases leave a removed step and continue at a new one.');
    expect(card).toHaveTextContent('2 cases stay on version 2 while a call finishes.');
    expect(card).toHaveTextContent(/Checked at \d\d:\d\d\. Good until \d\d:\d\d\./);
    // Plain words only: no engine code and no case id on the surface.
    expect(document.body.textContent).not.toMatch(/STEP_IN_FLIGHT|MAPPED|01JD4/);
    const calls = currentProcessEngine().migrations.calls;
    expect(calls.map((call) => call.command)).toEqual(['validate']);
    expect(currentProcessEngine().migrations.plan(MIGRATION_PLAN_ID)?.status).toBe('PLANNED');
  });
});

describe('CKP-057 execute with a reason, approved by a second person', () => {
  it('CKP_057_the_move_needs_a_fresh_check_and_a_reason_then_waits_for_a_second_person', async () => {
    resetProcessEngine({ migration: { state: 'planned' } });
    setScenario('migration-operator');
    open(PLAN);
    await checkPlan();

    const ask = await within(dock()).findByRole('button', { name: 'Ask approval to move 50 cases' });
    expect(ask).toBeDisabled();
    await userEvent.type(within(dock()).getByRole('textbox', { name: 'Why you are moving them' }), 'Moving to new KYC review');
    await userEvent.click(ask);

    expect(await screen.findByText('Waiting for approval')).toBeInTheDocument();
    const [, proposal] = currentProcessEngine().migrations.calls;
    expect(proposal).toEqual(expect.objectContaining({ command: 'propose', by: 'lan', body: { reason: 'Moving to new KYC review' } }));
    expect(proposal?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.getByText(/Proposed by/).closest('p')).toHaveTextContent(/Proposed by .*Moving to new KYC review/);
  });

  it('CKP_057_the_proposer_sees_approve_disabled_with_one_line_and_cannot_send_it_back', async () => {
    resetProcessEngine({ migration: { state: 'proposed', proposedBy: 'lan' } });
    setScenario('migration-operator');
    open(PLAN);

    const line = await within(await screen.findByRole('region', { name: 'Next step' })).findByText('You proposed this move, so a second person approves it.');
    const approve = within(dock()).getByRole('button', { name: /Approve moving 50 cases/ });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAccessibleDescription(line.textContent);
    expect(within(dock()).queryByRole('button', { name: 'Send back' })).not.toBeInTheDocument();
    expect(currentProcessEngine().migrations.calls.map((call) => call.command)).not.toContain('approve');
  });

  it('CKP_057_a_second_person_approves_exactly_the_proposal_they_were_shown', async () => {
    resetProcessEngine({ migration: { state: 'proposed', proposedBy: 'lan' } });
    setScenario('migration-approver');
    open(PLAN);

    expect((await screen.findByText(/Proposed by/)).closest('p')).toHaveTextContent(/Proposed by .*Moving to new KYC review/);
    const approve = await within(dock()).findByRole('button', { name: 'Approve moving 50 cases' });
    await waitFor(() => { expect(approve).toBeEnabled(); });
    await userEvent.click(approve);

    expect(await screen.findByText('The move runs in batches. You can leave this page.')).toBeInTheDocument();
    const call = currentProcessEngine().migrations.calls.find((item) => item.command === 'approve');
    // The proposal the approver saw is the one named: its lockVersion, as If-Match.
    expect(call).toEqual(expect.objectContaining({ by: 'minh', ifMatch: '"1"' }));
    expect(call?.idempotencyKey).toBeTruthy();
  });

  it('CKP_057_a_proposal_that_moved_on_is_refused_and_never_approved_blind', async () => {
    const engine = resetProcessEngine({ migration: { state: 'proposed', proposedBy: 'lan' } });
    setScenario('migration-approver');
    open(PLAN);

    const approve = await within(await screen.findByRole('region', { name: 'Next step' })).findByRole('button', { name: 'Approve moving 50 cases' });
    await waitFor(() => { expect(approve).toBeEnabled(); });
    // Someone else changes the proposal while the approver reads.
    engine.migrations.touch(MIGRATION_PLAN_ID);
    await userEvent.click(approve);

    expect(await within(dock()).findByText('The proposal changed. Open the plan again.')).toBeInTheDocument();
    expect(engine.migrations.plan(MIGRATION_PLAN_ID)?.status).toBe('PROPOSED');
    expect(engine.migrations.calls.find((item) => item.command === 'approve')?.ifMatch).toBe('"1"');
  });

  it('CKP_057_a_reader_may_see_the_plan_but_not_run_it', async () => {
    resetProcessEngine({ migration: { state: 'proposed', proposedBy: 'lan' } });
    setScenario('migration-reader');
    open(PLAN);

    expect(await within(await screen.findByRole('region', { name: 'Next step' })).findByText('You can read this plan, not run it.')).toBeInTheDocument();
    expect(within(dock()).queryByRole('button')).not.toBeInTheDocument();
    expect(currentProcessEngine().migrations.calls).toEqual([]);
  });

  it('CKP_057_progress_reads_from_the_engine_while_the_run_goes', async () => {
    resetProcessEngine({ migration: { state: 'running' } });
    setScenario('migration-operator');
    open(PLAN);

    expect(await screen.findByRole('heading', { name: '40 of 52 moved' })).toBeInTheDocument();
    const bar = screen.getByRole('progressbar', { name: 'Progress of the move' });
    expect(bar).toHaveAttribute('value', '40');
    expect(bar).toHaveAttribute('max', '52');
    // Calm: nothing is announced as an alert while cases are moving.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(within(dock()).queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('CKP-058 cases not moved and the record', () => {
  it('CKP_058_cases_left_behind_are_listed_with_their_reason_and_masked_values_stay_masked', async () => {
    resetProcessEngine({ migration: { state: 'completed' } });
    setScenario('migration-operator');
    open(PLAN);

    expect(await screen.findByRole('heading', { name: '49 moved. 3 not moved.' })).toBeInTheDocument();
    expect(await screen.findByRole('tab', { name: 'Not moved (3)', selected: true })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Cases that stayed on version 2' });
    const rows = await within(table).findAllByRole('row');
    expect(rows).toHaveLength(4);
    expect(await within(table).findByText('KYC-2207')).toBeInTheDocument();
    expect(within(table).getAllByText('A call at its step was still running.')).toHaveLength(2);
    expect(within(table).getByText('Another action was changing the case. Move it again later.')).toBeInTheDocument();
    // The reference the engine masked is shown as Masked, never as its stars or anything derived from them.
    await waitFor(() => { expect(within(table).getByText('Masked')).toBeInTheDocument(); });
    expect(table).not.toHaveTextContent('***');
    expect(document.body.textContent).not.toMatch(/LOCK_CONFLICT|STEP_IN_FLIGHT|01JD4/);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(within(dock()).getByText('Each moved case notes the move in its history.')).toBeInTheDocument();
  });
});

describe('planning a migration', () => {
  it('CKP_027_the_operator_says_what_happens_to_each_step_that_went_away', async () => {
    const engine = resetProcessEngine({ migration: { state: 'none' } });
    setScenario('migration-operator');
    open('#/processes/kyc-onboarding/3/move?from=2');

    expect(await screen.findByRole('heading', { level: 1, name: 'Move running cases' })).toBeInTheDocument();
    const create = await screen.findByRole('button', { name: 'Create plan' });
    // Steps that need an answer are flagged and the plan waits for them.
    expect(create).toBeDisabled();
    expect(create).toHaveAccessibleDescription('Steps still open: 2');
    const approve = screen.getByRole('combobox', { name: /What happens: Approve high-risk customer/ });
    const cooling = screen.getByRole('combobox', { name: /What happens: Wait one day/ });
    await userEvent.selectOptions(approve, 'move:senior-approval');
    await userEvent.selectOptions(cooling, 'stop:open-account');
    expect(create).toBeDisabled();
    await userEvent.type(screen.getByRole('textbox', { name: 'Why it is cancelled' }), 'The wait was retired');
    await waitFor(() => { expect(create).toBeEnabled(); });
    await userEvent.click(create);

    await waitFor(() => { expect(window.location.hash).toMatch(/^#\/migrations\/[0-9A-Z]{26}$/); });
    const [created] = engine.migrations.calls;
    expect(created?.body).toEqual({
      definitionKey: 'kyc-onboarding', sourceVersion: 2, targetVersion: 3,
      mappings: [{ from: 'approve', to: 'senior-approval' }],
      cancellations: [{ step: 'cooling', moveTo: 'open-account', reason: 'The wait was retired' }],
    });
  });

  it('CKP_027_the_definition_page_offers_the_move_only_to_who_may_run_it', async () => {
    resetProcessEngine({ migration: { state: 'none' } });
    setScenario('migration-operator');
    open('#/processes/kyc-onboarding/3');
    expect(await screen.findByRole('link', { name: 'Move running cases' })).toHaveAttribute('href', '#/processes/kyc-onboarding/3/move');
  });

  it('CKP_027_a_reader_without_migration_permission_is_not_offered_the_move', async () => {
    resetProcessEngine({ migration: { state: 'none' } });
    setScenario('migration-reader');
    open('#/processes/kyc-onboarding/3');
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Move running cases' })).not.toBeInTheDocument();
  });
});

describe('one language', () => {
  it('CKP_057_the_whole_screen_reads_in_vietnamese', async () => {
    resetProcessEngine({ migration: { state: 'planned' }, locale: 'vi' });
    setScenario('migration-operator');
    open(PLAN, 'vi');

    expect(await screen.findByRole('heading', { level: 1, name: 'Chuyển các hồ sơ đang chạy' })).toBeInTheDocument();
    await userEvent.click(await within(await screen.findByRole('region', { name: 'Bước tiếp theo' })).findByRole('button', { name: 'Kiểm tra kế hoạch' }));
    expect(await screen.findByRole('heading', { name: '50 hồ sơ có thể chuyển. 2 hồ sơ không thể.' })).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Check plan|cases can move|Next step/);
  });
});
