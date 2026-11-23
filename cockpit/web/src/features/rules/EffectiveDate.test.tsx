import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { draftSeed } from '../../mocks/ruleEngine';

let sent: { path: string; body: string }[] = [];

beforeEach(() => {
  sent = [];
  mockHost.events.on('request:start', ({ request }) => {
    const path = decodeURIComponent(new URL(request.url).pathname);
    if (path.endsWith(':request-publish')) void request.clone().text().then((body) => { sent.push({ path, body }); });
  });
  return () => { mockHost.events.removeAllListeners(); };
});

async function openDialog() {
  resetRuleEngine(draftSeed());
  setScenario('rules-author');
  window.location.hash = '#/rules/card-fee/2';
  render(<App />);
  await userEvent.click(await screen.findByRole('button', { name: 'Choose a start date' }));
  const dialog = await screen.findByRole('dialog');
  await userEvent.click(within(dialog).getByRole('radio', { name: 'On a date' }));
  return dialog;
}

/* RLE-210 (#470): the author sets the date the version takes effect when requesting publish. */
describe('Effective date on request publish (#470)', () => {
  it('CKP_090_the_request_carries_the_date_the_author_chose', async () => {
    const dialog = await openDialog();
    fireEvent.change(within(dialog).getByLabelText('Date and time'), { target: { value: '2027-01-01T09:00' } });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Request publish' }));
    await waitFor(() => { expect(sent).toHaveLength(1); });
    const body = JSON.parse(sent[0]?.body ?? '{}') as { effectiveFrom?: string };
    expect(Date.parse(body.effectiveFrom ?? '')).toBe(new Date('2027-01-01T09:00').getTime());
  });

  it('CKP_090_a_date_that_has_passed_cannot_be_requested', async () => {
    const dialog = await openDialog();
    fireEvent.change(within(dialog).getByLabelText('Date and time'), { target: { value: '2020-01-01T09:00' } });
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Choose a time that has not passed.');
    expect(within(dialog).getByRole('button', { name: 'Request publish' })).toBeDisabled();
    expect(sent).toHaveLength(0);
  });

  it('CKP_090_without_a_date_the_one_click_request_is_unchanged', async () => {
    resetRuleEngine(draftSeed());
    setScenario('rules-author');
    window.location.hash = '#/rules/card-fee/2';
    render(<App />);
    await userEvent.click(await screen.findByRole('button', { name: 'Request publish' }));
    await waitFor(() => { expect(sent).toHaveLength(1); });
    expect(sent[0]?.body).toBe('');
  });
});
