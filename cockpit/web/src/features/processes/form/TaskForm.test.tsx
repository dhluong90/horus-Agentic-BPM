import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../../App';
import { currentProcessEngine } from '../../../mocks/handlers';
import { mockHost, setScenario } from '../../../mocks/node';
import { QUEUE_TASKS } from '../../../mocks/taskQueueDemo';
import { FORM_TASK_ID } from '../../../mocks/processDemo';
import { build, fieldsOf, initialDrafts } from './model';
import { QUEUE_FORMS } from '../../../mocks/taskQueueDemo';

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

const dock = () => screen.getByRole('region', { name: 'Complete the task' });
const completions = () => currentProcessEngine().completions;

describe('CKP-021 a task is completed through its declared form', () => {
  it('CKP_021_task_form_completion_and_link', async () => {
    setScenario('tasks-operator');
    open(`#/tasks/${QUEUE_TASKS.myCreditReview}`);

    const form = await screen.findByRole('form', { name: 'Credit review' });
    // Every kind of field the engine declares is drawn with the label of the language.
    expect(within(form).getByRole('radio', { name: 'APPROVE' })).toBeInTheDocument();
    expect(within(form).getByLabelText(/^Approved limit/)).toHaveValue('250,000,000');
    expect(within(form).getByLabelText(/^Review date/)).toHaveAttribute('type', 'date');
    expect(within(form).getByLabelText(/^Dependants/)).toHaveValue('2');
    expect(within(form).getByRole('radio', { name: 'Yes' })).toBeInTheDocument();

    await userEvent.click(within(form).getByRole('radio', { name: 'REJECT' }));
    await userEvent.type(within(form).getByLabelText(/^Review date/), '2026-11-21');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));

    // The form's own `if`/`then` makes a rejection need a reason, so the screen says so before sending (#623).
    const reason = within(form).getByLabelText(/^Reason/);
    await waitFor(() => { expect(reason).toHaveAttribute('aria-invalid', 'true'); });
    expect(within(form).getByText('Fill in this field.')).toBeInTheDocument();
    expect(await within(dock()).findByText(/Check 1 field/)).toBeInTheDocument();
    expect(completions()).toHaveLength(0);

    await userEvent.type(reason, 'Income is not verified');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));

    expect(await screen.findByText(/^Completed by an,/)).toBeInTheDocument();
    expect(completions()).toHaveLength(1);
    const sent = completions()[0];
    expect(sent?.result).toEqual({
      decision: 'REJECT', approvedLimit: { currency: 'VND', amount: 250000000 }, reviewDate: '2026-11-21', dependants: 2, reason: 'Income is not verified',
    });
    expect(sent?.ifMatch).toBe('"1"');
    expect(sent?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('CKP_021_a_task_declared_with_a_link_opens_the_hosts_screen_and_shows_no_form', async () => {
    setScenario('tasks-operator');
    open(`#/tasks/${QUEUE_TASKS.offerLetter}`);

    expect(await screen.findByRole('heading', { name: 'This task opens in your own screen' })).toBeInTheDocument();
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    await userEvent.click(within(dock()).getByRole('button', { name: 'Take task' }));
    const link = await within(dock()).findByRole('link', { name: /Open the screen/ });
    expect(link).toHaveAttribute('href', `/loans/offers/${QUEUE_TASKS.offerLetter}`);
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('CKP_021_a_task_whose_step_declares_no_form_says_so', async () => {
    setScenario('processes-reviewer');
    open(`#/tasks/${FORM_TASK_ID}`);

    expect(await screen.findByText(/has no form to complete here/)).toBeInTheDocument();
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
  });

  it('CKP_021_the_form_reads_in_vietnamese_with_vietnamese_separators', async () => {
    setScenario('tasks-operator');
    window.localStorage.setItem('horus.cockpit.locale', 'vi');
    open(`#/tasks/${QUEUE_TASKS.myCreditReview}`);

    const form = await screen.findByRole('form');
    expect(within(form).getByLabelText(/^Hạn mức duyệt/)).toHaveValue('250.000.000');
    expect(within(form).getByRole('radio', { name: 'Có' })).toBeInTheDocument();
    window.localStorage.removeItem('horus.cockpit.locale');
  });
});

describe('control: masked fields stay masked', () => {
  it('TR_SEC_05_a_masked_field_is_never_shown_or_sent_unless_the_operator_replaces_it', async () => {
    setScenario('tasks-operator');
    open(`#/tasks/${QUEUE_TASKS.myCreditReview}`);

    const form = await screen.findByRole('form', { name: 'Credit review' });
    // The prefilled value is the engine's placeholder; the screen shows one "Masked" chip and no text of it.
    expect(within(form).getByText('Masked')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('***');
    expect(within(form).queryByDisplayValue('***')).not.toBeInTheDocument();

    await userEvent.click(within(form).getByRole('radio', { name: 'APPROVE' }));
    await userEvent.type(within(form).getByLabelText(/^Review date/), '2026-11-21');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));
    await waitFor(() => { expect(completions()).toHaveLength(1); });
    // Left alone, the masked field is not in the result at all, and certainly not as the placeholder.
    expect(completions()[0]?.result).not.toHaveProperty('idNumber');
    expect(JSON.stringify(completions()[0]?.result)).not.toContain('***');
  });

  it('TR_SEC_05_a_masked_field_the_operator_replaces_goes_as_typed_and_never_prefilled', async () => {
    setScenario('tasks-operator');
    open(`#/tasks/${QUEUE_TASKS.myCreditReview}`);

    const form = await screen.findByRole('form', { name: 'Credit review' });
    await userEvent.click(within(form).getByRole('button', { name: 'Replace value' }));
    const field = within(form).getByLabelText(/^National ID number/);
    expect(field).toHaveValue('');
    await userEvent.type(field, '079012345678');
    await userEvent.click(within(form).getByRole('radio', { name: 'APPROVE' }));
    await userEvent.type(within(form).getByLabelText(/^Review date/), '2026-11-21');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));

    await waitFor(() => { expect(completions()).toHaveLength(1); });
    expect(completions()[0]?.result['idNumber']).toBe('079012345678');
  });

  it('TR_SEC_05_the_drafts_never_hold_a_masked_value_and_a_required_masked_field_asks_for_a_new_one', () => {
    const declared = QUEUE_FORMS[QUEUE_TASKS.myCreditReview];
    if (declared === undefined || !('schema' in declared)) throw new Error('demo form missing');
    const schema = { ...declared.schema, required: ['idNumber'] };
    const fields = fieldsOf({ ...declared, schema }, 'en');
    expect(initialDrafts(fields, declared.data, 'en')['idNumber']).toBeUndefined();
    expect(build(fields, initialDrafts(fields, declared.data, 'en'), declared.data, 'en', new Set()).problems['idNumber']).toBe('required');
  });
});

describe('control: completing is idempotent', () => {
  it('TR_IDEM_01_a_retry_of_the_same_completion_sends_the_same_key_and_the_same_version', async () => {
    setScenario('tasks-operator');
    const seen: { key: string | null; ifMatch: string | null }[] = [];
    const { http, HttpResponse } = await import('msw');
    mockHost.use(http.post('*/api/bpm/processes/v1/tasks/*:complete', ({ request }) => {
      seen.push({ key: request.headers.get('Idempotency-Key'), ifMatch: request.headers.get('If-Match') });
      return HttpResponse.json({ type: 'about:blank', title: 'UNAVAILABLE', status: 503, code: 'UNAVAILABLE' }, { status: 503, headers: { 'Content-Type': 'application/problem+json' } });
    }, { once: true }));
    open(`#/tasks/${QUEUE_TASKS.myCreditReview}`);

    const form = await screen.findByRole('form', { name: 'Credit review' });
    await userEvent.click(within(form).getByRole('radio', { name: 'APPROVE' }));
    await userEvent.type(within(form).getByLabelText(/^Review date/), '2026-11-21');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));
    // The engine did not answer; the operator tries again with the same form.
    await waitFor(() => { expect(seen).toHaveLength(1); });
    await within(dock()).findByRole('alert');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));

    await waitFor(() => { expect(completions()).toHaveLength(1); });
    expect(completions()[0]?.idempotencyKey).toBe(seen[0]?.key);
    expect(completions()[0]?.ifMatch).toBe(seen[0]?.ifMatch);
  });

  it('TR_IDEM_01_a_changed_result_gets_a_new_key', async () => {
    setScenario('tasks-operator');
    const keys: (string | null)[] = [];
    const { http, HttpResponse } = await import('msw');
    mockHost.use(http.post('*/api/bpm/processes/v1/tasks/*:complete', ({ request }) => {
      keys.push(request.headers.get('Idempotency-Key'));
      return HttpResponse.json({ type: 'about:blank', title: 'UNAVAILABLE', status: 503, code: 'UNAVAILABLE' }, { status: 503, headers: { 'Content-Type': 'application/problem+json' } });
    }, { once: true }));
    open(`#/tasks/${QUEUE_TASKS.myCreditReview}`);

    const form = await screen.findByRole('form', { name: 'Credit review' });
    await userEvent.click(within(form).getByRole('radio', { name: 'APPROVE' }));
    await userEvent.type(within(form).getByLabelText(/^Review date/), '2026-11-21');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));
    await within(dock()).findByRole('alert');
    await userEvent.click(within(form).getByRole('radio', { name: 'REJECT' }));
    await userEvent.type(within(form).getByLabelText(/^Reason/), 'Not enough income');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));

    await waitFor(() => { expect(completions()).toHaveLength(1); });
    expect(completions()[0]?.idempotencyKey).not.toBe(keys[0]);
  });
});

describe('control: validation is enforced by the engine, not the screen', () => {
  it('PRE_031_the_screen_shows_the_engines_refusal_under_the_field_it_names_and_sends_no_value_back', async () => {
    setScenario('tasks-operator');
    open(`#/tasks/${QUEUE_TASKS.myCreditReview}`);

    const form = await screen.findByRole('form', { name: 'Credit review' });
    await userEvent.click(within(form).getByRole('radio', { name: 'REJECT' }));
    await userEvent.type(within(form).getByLabelText(/^Review date/), '2026-11-21');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));

    const reason = within(form).getByLabelText(/^Reason/);
    await waitFor(() => { expect(reason).toHaveAttribute('aria-invalid', 'true'); });
    expect(reason).toHaveAccessibleDescription('Fill in this field.');
    // The decision the operator chose is still chosen; nothing was cleared or completed.
    expect(within(form).getByRole('radio', { name: 'REJECT' })).toBeChecked();
    expect(completions()).toHaveLength(0);
    // Editing the field clears the engine's old sentence about it.
    await userEvent.type(reason, 'x');
    expect(reason).toHaveAttribute('aria-invalid', 'false');
  });

  it('PRE_031_a_format_error_is_shown_before_anything_is_sent', async () => {
    setScenario('tasks-operator');
    open(`#/tasks/${QUEUE_TASKS.myCreditReview}`);

    const form = await screen.findByRole('form', { name: 'Credit review' });
    await userEvent.type(within(form).getByLabelText(/^Dependants/), '.5');
    await userEvent.click(within(dock()).getByRole('button', { name: 'Complete task' }));

    expect(await within(form).findByText('Enter a whole number.')).toBeInTheDocument();
    expect(within(form).getAllByText('Fill in this field.').length).toBeGreaterThanOrEqual(2);
    expect(currentProcessEngine().requests.some((request) => request.path.endsWith(':complete'))).toBe(false);
  });
});
