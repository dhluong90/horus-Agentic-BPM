import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { currentProcessEngine, resetProcessEngine } from '../../mocks/handlers';
import { setScenario } from '../../mocks/node';
import { INCIDENT_INSTANCE_ID, KYC_INSTANCE_ID, STUCK_UNDO_INCIDENT_ID, STUCK_UNDO_INSTANCE_ID, UNDONE_INSTANCE_ID } from '../../mocks/caseDemo';
import { FORM_TASK_ID, INSTANCE_ID, MASKED_TASK_ID, REVIEW_TASK_ID } from '../../mocks/processDemo';

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

const dock = () => screen.getByRole('region', { name: 'Your decision' });

describe('CKP-069 and CKP-070 definition diagram', () => {
  it('CKP_069_processes_list_opens_the_version_in_effect_as_a_diagram_with_its_steps', async () => {
    setScenario('processes-reviewer');
    open('#/processes');

    const row = await screen.findByRole('link', { name: 'Loan origination' });
    expect(row).toHaveAttribute('href', '#/processes/loan-origination/3');
    expect(screen.getByRole('link', { name: 'Card dispute' })).toHaveAttribute('href', '#/processes/card-dispute/1');
    await userEvent.click(row);

    expect(await screen.findByRole('heading', { level: 1, name: 'Loan origination' })).toBeInTheDocument();
    expect(screen.getByText('Version 3, laid out automatically')).toBeInTheDocument();
    const diagram = await screen.findByRole('group', { name: 'Diagram of Loan origination, 13 steps' });
    expect(diagram).toHaveAttribute('data-layout', 'auto');
    expect(within(diagram).getByRole('img', { name: 'Assess documents, Agent task' })).toBeInTheDocument();
    expect(within(diagram).getByRole('img', { name: 'Capture application, Person task' })).toBeInTheDocument();
    expect(within(diagram).getByRole('img', { name: 'Credit decision, Decision' })).toBeInTheDocument();
    // The breadcrumb names the process, not its key.
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByText('Loan origination')).toBeInTheDocument();
    expect(within(crumbs).queryByText('loan-origination')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'Steps (13)' }));
    const steps = within(screen.getByRole('list', { name: 'Steps in flow order' })).getAllByRole('listitem');
    expect(steps[0]).toHaveTextContent('Application receivedStart');
    expect(steps).toHaveLength(13);
  });

  it('CKP_070_an_unknown_node_type_renders_as_a_generic_node_naming_its_type', async () => {
    setScenario('processes-reviewer');
    open('#/processes/loan-origination/3');

    const diagram = await screen.findByRole('group', { name: /Diagram of Loan origination/ });
    const generic = within(diagram).getByRole('img', { name: 'No documents in 2 days, Timer' });
    expect(generic).toHaveClass('dg-node--generic');
    expect(generic).toHaveAttribute('data-type', 'boundaryEvent:timer');
    // Named in words with the timer icon, never by the key the engine sent; the whole name is drawn, wrapped.
    expect(generic.querySelector('.dg-node__type')).toHaveTextContent(/^Timer$/);
    expect(generic.querySelector('.dg-node__name')).toHaveTextContent('No documents in 2 days');
    expect(diagram).not.toHaveTextContent(/boundaryEvent/);
    // The rest of the diagram is intact.
    expect(within(diagram).getByRole('img', { name: 'Remind applicant, Service task' })).toBeInTheDocument();
  });

  it('CKP_070_vietnamese_names_every_known_type_in_words', async () => {
    setScenario('processes-reviewer');
    window.localStorage.setItem('horus.cockpit.locale', 'vi');
    open('#/processes/kyc-onboarding/2');

    const diagram = await screen.findByRole('group', { name: /Sơ đồ Customer onboarding/ });
    expect(within(diagram).getByRole('img', { name: 'Verify identity documents, Việc của tác tử AI' })).toBeInTheDocument();
    expect(within(diagram).getByRole('img', { name: 'Wait one day, Hẹn giờ' })).toBeInTheDocument();
  });

  it('CKP_069_a_user_without_processes_read_is_told_and_no_list_is_requested', async () => {
    setScenario('processes-task-only');
    open('#/processes');

    expect(await screen.findByText(/Your permissions show no process definitions here/)).toBeInTheDocument();
    expect(currentProcessEngine().requests.some((request) => request.path.endsWith('/definitions'))).toBe(false);
  });
});

describe('CKP-017 instance on its diagram', () => {
  it('CKP_017_instance_diagram_shows_tokens_and_activities', async () => {
    setScenario('processes-reviewer');
    open(`#/instances/${KYC_INSTANCE_ID}`);

    expect(await screen.findByRole('heading', { level: 1, name: 'Case KYC-2207' })).toBeInTheDocument();
    const diagram = await screen.findByRole('group', { name: 'Diagram of Case KYC-2207' });
    // The waiting step carries a token badge in words, not colour alone, and opens first in the inspector.
    const approve = await within(diagram).findByRole('button', { name: 'Approve high-risk customer Person task, Waiting for a person' });
    expect(approve).toHaveAttribute('aria-pressed', 'true');
    const inspector = screen.getByRole('complementary', { name: 'Selected step' });
    expect(within(inspector).getByText('Waiting for a person')).toBeInTheDocument();
    expect(within(inspector).getByText('Credit officer')).toBeInTheDocument();
    expect(within(inspector).getByRole('link', { name: 'Open the task' })).toHaveAttribute('href', '#/tasks/01JD4T7APPR0VE00000000KY07');

    await userEvent.click(within(diagram).getByRole('button', { name: /^Verify identity documents Agent task/ }));
    expect(within(inspector).getByText('Completed, 2 attempts, 3 min 10 s, by svc-kyc')).toBeInTheDocument();
    expect(within(inspector).getByText('Completed', { selector: '.badge' })).toBeInTheDocument();

    // The timeline lists the same entries in time order, in words.
    await userEvent.click(screen.getByRole('tab', { name: 'Timeline' }));
    const rows = within(screen.getByRole('table', { name: 'History of the case in time order' })).getAllByRole('row').slice(1);
    expect(rows.map((row) => (row as HTMLTableRowElement).cells.item(1)?.textContent)).toEqual(['Case started', 'Step started', '✕ Attempt failed', 'Step completed', 'Decision made', 'Step started']);
    expect(rows[3]).toHaveTextContent('Verify identity documents');
    expect(rows[3]).toHaveTextContent('svc-kyc');
    // The activities came from the engine's endpoint; nothing is computed from variables.
    expect(currentProcessEngine().requests.some((request) => request.path.endsWith(`/instances/${KYC_INSTANCE_ID}/activities`))).toBe(true);
  });

  it('CKP_017_an_incident_shows_on_its_step_with_its_code_one_click_away', async () => {
    setScenario('processes-reviewer');
    open(`#/instances/${INCIDENT_INSTANCE_ID}`);

    const diagram = await screen.findByRole('group', { name: 'Diagram of Case APP-1045' });
    expect(await within(diagram).findByRole('button', { name: 'Assess documents Agent task, Incident: The agent did not answer in time.' })).toHaveAttribute('aria-pressed', 'true');
    const inspector = screen.getByRole('complementary', { name: 'Selected step' });
    expect(within(inspector).getByText('The agent did not answer in time.')).toBeInTheDocument();
    expect(within(inspector).getByText('3 attempts')).toBeInTheDocument();
    expect(within(inspector).getByText('Process Engine')).toBeInTheDocument();
    expect(inspector).not.toHaveTextContent('Process Engine (');
    await userEvent.click(within(inspector).getByText('Details'));
    expect(within(inspector).getByText('AGENT_TIMEOUT')).toBeVisible();
    expect(within(inspector).getByText('loans-7f9c')).toBeVisible();
    expect(diagram).not.toHaveTextContent('AGENT_TIMEOUT');
  });

  it('CKP_017_a_case_opens_from_the_cases_of_its_process', async () => {
    setScenario('processes-reviewer');
    open('#/processes/loan-origination/3');

    await userEvent.click(await screen.findByRole('tab', { name: 'Cases' }));
    expect(window.location.hash).toBe('#/processes/loan-origination/3/cases');
    const link = await screen.findByRole('link', { name: 'APP-1042' });
    expect(link).toHaveAttribute('href', `#/instances/${INSTANCE_ID}`);
    await userEvent.click(link);

    expect(await screen.findByRole('heading', { level: 1, name: 'Case APP-1042' })).toBeInTheDocument();
    expect(await screen.findByText('Loan origination, version 3')).toBeInTheDocument();
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumbs).getByRole('link', { name: 'Loan origination' })).toHaveAttribute('href', '#/processes/loan-origination/3/cases');
  });

  it('CKP_017_a_process_without_cases_says_so', async () => {
    setScenario('processes-reviewer');
    open('#/processes/card-dispute/1/cases');

    expect(await screen.findByText('No case of this process has started yet.')).toBeInTheDocument();
  });

  it('CKP_017_an_engine_without_activities_still_shows_where_the_case_waits', async () => {
    setScenario('processes-reviewer');
    resetProcessEngine({ activities: 'missing' });
    open(`#/instances/${INSTANCE_ID}`);

    const diagram = await screen.findByRole('group', { name: 'Diagram of Case APP-1042' });
    expect(await within(diagram).findByRole('button', { name: 'Assess documents Agent task, Waiting for a person' })).toBeInTheDocument();
    expect(await screen.findByText('This service does not report step activity yet.')).toBeInTheDocument();
    // With nothing to show, the task action is unavailable and says why, not a filled primary link.
    const inspector = screen.getByRole('complementary', { name: 'Selected step' });
    expect(within(inspector).queryByRole('link', { name: 'Open the task' })).not.toBeInTheDocument();
    const action = within(inspector).getByRole('button', { name: 'Open the task' });
    expect(action).toHaveAttribute('aria-disabled', 'true');
    expect(action).toHaveAccessibleDescription('Opens once step activity is reported.');
  });

  it('CKP_017_a_case_reads_in_vietnamese', async () => {
    setScenario('processes-reviewer');
    window.localStorage.setItem('horus.cockpit.locale', 'vi');
    resetProcessEngine({ locale: 'vi' });
    open(`#/instances/${INSTANCE_ID}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Hồ sơ APP-1042' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Mở công việc' })).toBeInTheDocument();
  });

  it('CKP_017_a_user_without_processes_read_is_told_and_no_case_is_requested', async () => {
    setScenario('processes-task-only');
    open(`#/instances/${INSTANCE_ID}`);

    expect(await screen.findByText('Your permissions do not include reading cases on this service.')).toBeInTheDocument();
    expect(currentProcessEngine().requests.some((request) => request.path.includes('/instances/'))).toBe(false);
  });

  it('CKP_017_an_unknown_case_is_not_found', async () => {
    setScenario('processes-reviewer');
    open('#/instances/01JD4I0N0SUCH0000000000000');

    expect(await screen.findByText('This service has no case with this reference.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Back to processes/ })).toHaveAttribute('href', '#/processes');
  });
});

async function openTimeline(instanceId: string) {
  setScenario('processes-reviewer');
  open(`#/instances/${instanceId}`);
  await userEvent.click(await screen.findByRole('tab', { name: 'Timeline' }));
  return screen.findByRole('table', { name: 'History of the case in time order' });
}

const rowTexts = (table: HTMLElement) => within(table).getAllByRole('row').slice(1).map((row) => row.textContent);

describe('CKP-033 and CKP-049 undo steps in the timeline', () => {
  it('CKP_033_compensation_runs_in_instance_timeline', async () => {
    const table = await openTimeline(UNDONE_INSTANCE_ID);
    const rows = rowTexts(table);
    const at = (text: string) => rows.findIndex((row) => row.includes(text));

    // The failure, then the undo steps newest forward step first, the second after a retry, in time order.
    expect(at('Attempt failed')).toBeGreaterThan(-1);
    expect(at('Attempt failed')).toBeLessThan(at('Undo started'));
    expect(at('Undo of Book loan: Reverse booking')).toBeLessThan(at('Undo of Credit decision: Release credit line'));
    expect(within(table).getAllByText('Attempt 2')).toHaveLength(1);
    expect(within(table).getAllByText('Done')).toHaveLength(2);
    expect(at('Undo finished')).toBeGreaterThan(at('Undo of Credit decision: Release credit line'));
    // Each undo entry is marked by the symbol and the word, and the engine's state of the scope is shown as given.
    expect(within(table).getAllByText('↺').length).toBeGreaterThanOrEqual(7);
    const scopes = screen.getByRole('region', { name: 'Undo of failed steps' });
    expect(within(scopes).getByText('Disburse: undone')).toBeInTheDocument();
    expect(within(scopes).getByText('After Send offer failed: 2 steps undone.')).toBeInTheDocument();
    // No error code, id or saga token reaches the page body; the read carries no variable or input value.
    const page = document.body.textContent;
    for (const hidden of ['ADAPTER_TIMEOUT', 'MAX_ATTEMPTS_REACHED', 'DISBURSE-SCOPE', 'reverseBooking', 'releaseCreditLine']) expect(page).not.toContain(hidden);
    expect(currentProcessEngine().requests.some((request) => request.path.endsWith(`/instances/${UNDONE_INSTANCE_ID}/activities`))).toBe(true);
  });

  it('CKP_033_the_diagram_marks_each_undone_step_with_a_label', async () => {
    setScenario('processes-reviewer');
    open(`#/instances/${UNDONE_INSTANCE_ID}`);
    const diagram = await screen.findByRole('group', { name: 'Diagram of Case APP-1047' });
    expect(await within(diagram).findByRole('button', { name: 'Book loan Service task, Undone' })).toBeInTheDocument();
    expect(within(diagram).getByRole('button', { name: /^Credit decision .*Undone$/ })).toBeInTheDocument();
  });

  it('CKP_049_a_failed_undo_reads_calmly_and_never_says_the_case_was_put_right', async () => {
    const table = await openTimeline(STUCK_UNDO_INSTANCE_ID);
    expect(within(table).getAllByText('Undo failed: Release credit line')).toHaveLength(3);
    expect(within(table).getByText('Failed after 3 attempts')).toBeInTheDocument();
    const scopes = screen.getByRole('region', { name: 'Undo of failed steps' });
    expect(within(scopes).getByText('Disburse: not fully undone')).toBeInTheDocument();
    expect(within(scopes).getByText('Undo of Credit decision did not finish and needs an operator.')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Disburse: undone|put right|ADAPTER_TIMEOUT|COMPENSATION_FAILED/);
    // The failure is words and a symbol, not the error hue.
    expect(table.querySelector('.timeline__row--undo-stuck')).not.toBeNull();
    expect(table.querySelector('.timeline__row--undo .timeline__what--bad')).toBeNull();

    // The one way forward opens the incident the undo raised, selected in the incident queue (CKP-008).
    const link = within(scopes).getByRole('link', { name: 'Open the incident' });
    expect(link).toHaveAttribute('href', `#/incidents?view=open&incident=${STUCK_UNDO_INCIDENT_ID}`);
    await userEvent.click(link);
    await waitFor(() => { expect(window.location.hash).toBe(`#/incidents?view=open&incident=${STUCK_UNDO_INCIDENT_ID}`); });
    expect(await screen.findByRole('tab', { name: /^Open \(\d+\)$/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('CKP_033_the_undo_reads_in_vietnamese', async () => {
    setScenario('processes-reviewer');
    window.localStorage.setItem('horus.cockpit.locale', 'vi');
    resetProcessEngine({ locale: 'vi' });
    open(`#/instances/${STUCK_UNDO_INSTANCE_ID}`);
    await userEvent.click(await screen.findByRole('tab', { name: 'Dòng thời gian' }));
    expect(await screen.findByText('Disburse: chưa hoàn tác hết')).toBeInTheDocument();
    expect(screen.getAllByText('Hoàn tác lỗi: Release credit line')).toHaveLength(3);
    expect(screen.getByText('Lỗi sau 3 lần thử')).toBeInTheDocument();
  });
});

describe('CKP-007 agent task inspector', () => {
  it('CKP_007_agent_task_inspector_shows_guardrail_and_override_controls', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${REVIEW_TASK_ID}`);

    expect(await screen.findByRole('heading', { level: 1, name: "Review the agent's answer" })).toBeInTheDocument();
    expect(screen.getByText('Step Assess documents')).toBeInTheDocument();
    expect(await screen.findByText('Loan origination, version 3')).toBeInTheDocument();
    // Masked inputs stay masked: one chip and a copy action, never the value or the fingerprint as text.
    const inputs = screen.getByRole('article', { name: 'What the agent received' });
    expect(within(inputs).getByText('Masked')).toBeInTheDocument();
    expect(within(inputs).getByRole('button', { name: 'Copy reference' })).toBeInTheDocument();
    expect(inputs).not.toHaveTextContent(/hmac|9f4a/);
    // Field names read as words.
    expect(within(inputs).getByText('Declared monthly income')).toBeInTheDocument();
    expect(within(inputs).getByText('National ID')).toBeInTheDocument();
    expect(within(inputs).queryByText('declaredMonthlyIncome')).not.toBeInTheDocument();
    expect(within(inputs).getByText('850,000,000 VND')).toBeInTheDocument();
    // The schema verdict, the structured answer and the guardrail rule.
    const answer = screen.getByRole('article', { name: 'Agent answer' });
    expect(within(answer).getByText('Schema check passed')).toBeInTheDocument();
    expect(within(answer).getByText('APPROVE')).toBeInTheDocument();
    const why = screen.getByRole('article', { name: 'Why it was stopped' });
    expect(within(why).getByText('A guardrail rule refused the answer.')).toBeInTheDocument();
    expect(within(why).getByText('Rule R2')).toBeInTheDocument();
    expect(within(why).getByText('Declared income is 38% above the income on the salary slips')).toBeInTheDocument();
    // The engine reads as a word; its node id is one click away under Details.
    expect(within(why).getByText('Process Engine')).toBeInTheDocument();
    expect(within(why).getByText('loans-7f9c')).not.toBeVisible();
    // The breadcrumb keeps the way back to the process and the case.
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(await within(crumbs).findByRole('link', { name: 'Loan origination' })).toHaveAttribute('href', '#/processes/loan-origination/3/cases');
    expect(within(crumbs).getByRole('link', { name: 'Case APP-1042' })).toHaveAttribute('href', `#/instances/${INSTANCE_ID}`);
    expect(within(crumbs).getByText('Agent review')).toHaveAttribute('aria-current', 'page');
    expect(within(why).getByText('…R2X7')).toHaveAttribute('title', '01JD4G2DEC1S10N0000000R2X7');

    // Take the review, then override with a reason.
    await userEvent.click(within(dock()).getByRole('button', { name: 'Start review' }));
    await userEvent.click(await within(dock()).findByRole('button', { name: 'Override' }));
    const editor = screen.getByRole('article', { name: 'Your answer' });
    await userEvent.clear(within(editor).getByLabelText('Recommendation'));
    await userEvent.type(within(editor).getByLabelText('Recommendation'), 'REFER');
    await userEvent.clear(within(editor).getByLabelText('Risk score'));
    await userEvent.type(within(editor).getByLabelText('Risk score'), '72');
    const changes = within(dock()).getByRole('list', { name: 'Your changes' });
    expect(changes).toHaveTextContent('Risk score38 → changed to72');
    expect(changes).toHaveTextContent('RecommendationAPPROVE → changed toREFER');

    await userEvent.click(within(dock()).getByRole('button', { name: 'Record override' }));
    expect(within(dock()).getByText('Give a reason.')).toBeInTheDocument();
    expect(currentProcessEngine().overrides).toHaveLength(0);

    await userEvent.type(within(dock()).getByLabelText('Reason'), 'Salary slips do not support the declared income');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Record override' }));

    expect(await within(dock()).findByText(/Decided by hoa/)).toBeInTheDocument();
    expect(currentProcessEngine().overrides).toEqual([
      expect.objectContaining({ reviewedBy: 'hoa', reason: 'Salary slips do not support the declared income', result: { riskScore: 72, recommendation: 'REFER', incomeVerified: true } }),
    ]);
    const complete = currentProcessEngine().requests.find((request) => request.path.endsWith(':complete'));
    expect(complete?.headers['if-match']).toBe('"1"');
    expect(complete?.headers['idempotency-key']).toMatch(/.+/);
  });

  it('CKP_007_approve_sends_the_agent_answer_unchanged_with_the_reason', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${REVIEW_TASK_ID}`);

    await userEvent.click(await within(await screen.findByRole('region', { name: 'Your decision' })).findByRole('button', { name: 'Start review' }));
    await userEvent.type(await within(dock()).findByLabelText('Reason'), 'Income confirmed by phone with the employer');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Approve answer' }));

    await within(dock()).findByText(/Decided by hoa/);
    expect(currentProcessEngine().overrides[0]?.result).toEqual({ riskScore: 38, recommendation: 'APPROVE', incomeVerified: true });
  });

  it('CKP_007_the_starter_of_the_case_cannot_decide_its_review', async () => {
    setScenario('processes-starter');
    open(`#/tasks/${REVIEW_TASK_ID}`);

    const line = await within(await screen.findByRole('region', { name: 'Your decision' })).findByText('You started this case, so someone else decides.');
    // The action stays in its place, disabled, and says why.
    const start = within(dock()).getByRole('button', { name: 'Start review' });
    expect(start).toHaveAttribute('aria-disabled', 'true');
    expect(start).toHaveAccessibleDescription('You started this case, so someone else decides.');
    expect(screen.getByText('A second person decides this answer, not you.')).toBeInTheDocument();
    await userEvent.click(start);
    expect(currentProcessEngine().requests.some((request) => request.path.endsWith(':claim'))).toBe(false);
    expect(line).toHaveAttribute('id', start.getAttribute('aria-describedby'));
    expect(within(dock()).queryByRole('button', { name: 'Approve answer' })).not.toBeInTheDocument();
  });

  it('CKP_007_the_reporter_of_the_refused_answer_cannot_decide_its_review', async () => {
    setScenario('processes-reviewer');
    resetProcessEngine({ reportedBy: 'hoa' });
    open(`#/tasks/${REVIEW_TASK_ID}`);

    expect(await within(await screen.findByRole('region', { name: 'Your decision' })).findByText('You reported this answer, so someone else decides.')).toBeInTheDocument();
    expect(within(dock()).getByRole('button', { name: 'Start review' })).toHaveAttribute('aria-disabled', 'true');
    expect(within(dock()).getByRole('button', { name: 'Start review' })).toHaveAccessibleDescription('You reported this answer, so someone else decides.');
  });

  it('CKP_007_a_masked_answer_cannot_be_approved_as_is_and_the_override_needs_the_masked_value', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${MASKED_TASK_ID}`);

    const region = await screen.findByRole('region', { name: 'Your decision' });
    // The review opens on the override, with one line saying why Approve is not offered.
    expect(await within(region).findByText('Part of the answer is masked, so only an override is possible.')).toBeInTheDocument();
    const approve = within(region).getByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAccessibleDescription('Part of the answer is masked, so only an override is possible.');
    expect(within(region).getByRole('button', { name: 'Override' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(region).queryByRole('button', { name: 'Approve answer' })).not.toBeInTheDocument();
    expect(within(region).getByRole('button', { name: 'Record override' })).toBeEnabled();

    const editor = screen.getByRole('article', { name: 'Your answer' });
    expect(within(editor).getByLabelText('Summary')).toHaveValue('');
    expect(within(editor).getByLabelText('Summary')).toHaveAttribute('placeholder', 'Masked. Type your value');
    await userEvent.type(within(dock()).getByLabelText('Reason'), 'Checked');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Record override' }));
    expect(within(editor).getByText('Masked values are never shown. Type the value you decide.')).toBeInTheDocument();
    expect(currentProcessEngine().requests.some((request) => request.path.endsWith(':complete'))).toBe(false);
  });

  it('CKP_007_the_engines_schema_refusal_is_shown_on_the_field', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${REVIEW_TASK_ID}`);

    await userEvent.click(await within(await screen.findByRole('region', { name: 'Your decision' })).findByRole('button', { name: 'Start review' }));
    await userEvent.click(await within(dock()).findByRole('button', { name: 'Override' }));
    const editor = screen.getByRole('article', { name: 'Your answer' });
    await userEvent.clear(within(editor).getByLabelText('Risk score'));
    await userEvent.type(within(editor).getByLabelText('Risk score'), '140');
    await userEvent.type(within(dock()).getByLabelText('Reason'), 'High risk');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Record override' }));

    expect(await within(dock()).findByText('The engine refused your answer: it does not meet the output schema.')).toBeInTheDocument();
    await waitFor(() => { expect(within(editor).getByText('must be between 0 and 100')).toBeInTheDocument(); });
    expect(within(editor).getByLabelText('Risk score')).toHaveAttribute('aria-invalid', 'true');
  });

  it('CKP_007_a_task_without_an_agent_answer_says_so', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${FORM_TASK_ID}`);

    expect(await screen.findByText(/Only escalated agent steps open here/)).toBeInTheDocument();
  });
});
