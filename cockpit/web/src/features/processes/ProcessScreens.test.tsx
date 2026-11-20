import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { currentProcessEngine, resetProcessEngine } from '../../mocks/handlers';
import { setScenario } from '../../mocks/node';
import { FORM_TASK_ID, MASKED_TASK_ID, REVIEW_TASK_ID } from '../../mocks/processDemo';

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

    await userEvent.click(screen.getByRole('tab', { name: 'Steps (13)' }));
    const steps = within(screen.getByRole('list', { name: 'Steps in flow order' })).getAllByRole('listitem');
    expect(steps[0]).toHaveTextContent('Application receivedStart');
    expect(steps).toHaveLength(13);
  });

  it('CKP_070_an_unknown_node_type_renders_as_a_generic_node_naming_its_type', async () => {
    setScenario('processes-reviewer');
    open('#/processes/loan-origination/3');

    const diagram = await screen.findByRole('group', { name: /Diagram of Loan origination/ });
    const generic = within(diagram).getByRole('img', { name: 'No documents in 2 days, boundaryEvent:timer' });
    expect(generic).toHaveClass('dg-node--generic');
    expect(generic).toHaveAttribute('data-type', 'boundaryEvent:timer');
    expect(generic.querySelector('.dg-node__type')).toHaveTextContent(/^boundaryEvent:timer$/);
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

describe('CKP-007 agent task inspector', () => {
  it('CKP_007_agent_task_inspector_shows_guardrail_and_override_controls', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${REVIEW_TASK_ID}`);

    expect(await screen.findByRole('heading', { level: 1, name: 'Assess documents: review' })).toBeInTheDocument();
    // Masked inputs stay masked: the fingerprint's start only, never a value.
    const inputs = screen.getByRole('article', { name: 'What the agent received' });
    expect(within(inputs).getByText('Masked')).toBeInTheDocument();
    expect(within(inputs).getByText('hmac:k2026:9f4a…')).toBeInTheDocument();
    expect(within(inputs).queryByText(/9f4a1c7e2b8d4f60/)).not.toBeInTheDocument();
    expect(within(inputs).getByText('850,000,000 VND')).toBeInTheDocument();
    // The schema verdict, the structured answer and the guardrail rule.
    const answer = screen.getByRole('article', { name: 'Agent answer' });
    expect(within(answer).getByText('Schema check passed')).toBeInTheDocument();
    expect(within(answer).getByText('APPROVE')).toBeInTheDocument();
    const why = screen.getByRole('article', { name: 'Why it was stopped' });
    expect(within(why).getByText('A guardrail rule refused the answer.')).toBeInTheDocument();
    expect(within(why).getByText('Rule R2')).toBeInTheDocument();
    expect(within(why).getByText('Declared income is 38% above the income on the salary slips')).toBeInTheDocument();

    // Take the review, then override with a reason.
    await userEvent.click(within(dock()).getByRole('button', { name: 'Start review' }));
    await userEvent.click(await within(dock()).findByRole('button', { name: 'Override' }));
    const editor = screen.getByRole('article', { name: 'Your answer' });
    await userEvent.clear(within(editor).getByLabelText('recommendation'));
    await userEvent.type(within(editor).getByLabelText('recommendation'), 'REFER');
    await userEvent.clear(within(editor).getByLabelText('riskScore'));
    await userEvent.type(within(editor).getByLabelText('riskScore'), '72');
    const changes = within(dock()).getByRole('list', { name: 'Your changes' });
    expect(changes).toHaveTextContent('riskScore38 → changed to72');
    expect(changes).toHaveTextContent('recommendationAPPROVE → changed toREFER');

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

    expect(await within(await screen.findByRole('region', { name: 'Your decision' })).findByText('You started this case, so another person must decide it.')).toBeInTheDocument();
    expect(within(dock()).queryByRole('button', { name: 'Start review' })).not.toBeInTheDocument();
    expect(within(dock()).queryByRole('button', { name: 'Approve answer' })).not.toBeInTheDocument();
  });

  it('CKP_007_the_reporter_of_the_refused_answer_cannot_decide_its_review', async () => {
    setScenario('processes-reviewer');
    resetProcessEngine({ reportedBy: 'hoa' });
    open(`#/tasks/${REVIEW_TASK_ID}`);

    expect(await within(await screen.findByRole('region', { name: 'Your decision' })).findByText('You reported this answer, so another person must decide it.')).toBeInTheDocument();
    expect(within(dock()).queryByRole('button', { name: 'Start review' })).not.toBeInTheDocument();
  });

  it('CKP_007_a_masked_answer_cannot_be_approved_as_is_and_the_override_needs_the_masked_value', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${MASKED_TASK_ID}`);

    const region = await screen.findByRole('region', { name: 'Your decision' });
    expect(await within(region).findByText(/cannot be approved as is/)).toBeInTheDocument();
    expect(within(region).getByRole('button', { name: 'Approve answer' })).toBeDisabled();
    expect(within(screen.getByRole('article', { name: 'Agent answer' })).getByText('Masked')).toBeInTheDocument();

    await userEvent.click(within(region).getByRole('button', { name: 'Override' }));
    const editor = screen.getByRole('article', { name: 'Your answer' });
    expect(within(editor).getByLabelText('summary')).toHaveValue('');
    expect(within(editor).getByLabelText('summary')).toHaveAttribute('placeholder', 'Masked. Type your value');
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
    await userEvent.clear(within(editor).getByLabelText('riskScore'));
    await userEvent.type(within(editor).getByLabelText('riskScore'), '140');
    await userEvent.type(within(dock()).getByLabelText('Reason'), 'High risk');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Record override' }));

    expect(await within(dock()).findByText('The engine refused your answer: it does not meet the output schema.')).toBeInTheDocument();
    await waitFor(() => { expect(within(editor).getByText('must be between 0 and 100')).toBeInTheDocument(); });
    expect(within(editor).getByLabelText('riskScore')).toHaveAttribute('aria-invalid', 'true');
  });

  it('CKP_007_a_task_without_an_agent_answer_says_so', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${FORM_TASK_ID}`);

    expect(await screen.findByText(/Only escalated agent steps open here/)).toBeInTheDocument();
  });
});
