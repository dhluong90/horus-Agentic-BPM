import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { draftSeed } from '../../mocks/ruleEngine';

let calls: { method: string; path: string; key: string | null; ifMatch: string | null; body: string }[] = [];

beforeEach(() => {
  calls = [];
  mockHost.events.on('request:start', ({ request }) => {
    const path = decodeURIComponent(new URL(request.url).pathname);
    if (path.startsWith('/api/bpm/rules') && request.method === 'POST') {
      void request.clone().text().then((body) => {
        calls.push({ method: request.method, path: path.replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key'), ifMatch: request.headers.get('If-Match'), body });
      });
    }
  });
  return () => { mockHost.events.removeAllListeners(); };
});

/** card-fee v1 in effect and v2 a draft Lan wrote (draftSeed). */
function open(hash: string, scenario: Scenario) {
  resetRuleEngine(draftSeed());
  setScenario(scenario);
  window.location.hash = hash;
  render(<App />);
}

describe('CKP-081 discard a draft', () => {
  it('CKP_081_the_author_discards_a_draft_after_confirming_and_returns_to_the_version_in_front', async () => {
    open('#/rules/card-fee/2', 'rules-author');
    const discard = await screen.findByRole('button', { name: 'Discard draft' }, { timeout: 5000 });
    await userEvent.click(discard);
    const dialog = await screen.findByRole('dialog', { name: 'Discard the version 2 draft?' });
    // Nothing is sent before Confirm; Cancel sends nothing and closes.
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(calls.filter((call) => call.path.endsWith(':discard'))).toHaveLength(0);

    await userEvent.click(screen.getByRole('button', { name: 'Discard draft' }));
    const again = await screen.findByRole('dialog');
    await userEvent.type(within(again).getByLabelText('Reason (optional)'), 'Started from the wrong version');
    await userEvent.click(within(again).getByRole('button', { name: 'Discard draft' }));

    expect(await screen.findByText('Version 2 draft discarded.')).toBeInTheDocument();
    await waitFor(() => { expect(window.location.hash).toBe('#/rules/card-fee'); });
    const sent = calls.find((call) => call.path.endsWith(':discard'));
    expect(sent).toMatchObject({ path: '/rule-sets/card-fee/versions/2:discard', ifMatch: '"0"' });
    expect(sent?.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse(sent?.body ?? '{}')).toEqual({ reason: 'Started from the wrong version' });
    // The draft left the picker; the version in front is the one in effect.
    const picker = await screen.findByRole('combobox', { name: 'Versions' });
    expect(within(picker).queryByRole('option', { name: /Version 2/ })).not.toBeInTheDocument();
  });

  it('CKP_081_a_draft_without_discard_in_allowed_actions_shows_no_discard_button', { timeout: 15000 }, async () => {
    // Minh may not author: the engine lists no discard for him on Lan's draft.
    open('#/rules/card-fee/2', 'rules-approver');
    expect(await screen.findByRole('heading', { level: 1, name: /card-fee/ }, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Discard draft' })).not.toBeInTheDocument();
    // A published version never offers it either.
    window.location.hash = '#/rules/card-fee/1';
    await waitFor(() => { expect(screen.getByRole('combobox', { name: 'Versions' })).toHaveValue('1'); }, { timeout: 4000 });
    expect(screen.queryByRole('button', { name: 'Discard draft' })).not.toBeInTheDocument();
  });
});
