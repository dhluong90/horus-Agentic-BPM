import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../../App';
import { formatRoute, parseRoute } from '../../../app/route';
import { resetProcessEngine } from '../../../mocks/handlers';
import { setScenario } from '../../../mocks/node';
import { liveFigures } from '../model/liveCounts';

function open(hash: string) {
  window.location.hash = hash;
  render(<App />);
}

/* #91: the cases now at each step and its open incidents, written under the step of the diagram. */
describe('Live counts on the diagram (#91)', () => {
  it('sums the tokens of every status and keeps a step that has only incidents', () => {
    const figures = liveFigures({
      definitionKey: 'k', version: 1, mode: 'live', asOf: null,
      nodes: { a: { ACTIVE: 2, WAITING_ASYNC: 1 }, b: { WAITING_USER: 0 } },
      openIncidents: { c: 2 },
    });
    expect(figures).toEqual([{ node: 'a', tokens: 3, incidents: 0 }, { node: 'c', tokens: 0, incidents: 2 }]);
  });

  it('reads an engine without open incidents as none', () => {
    expect(liveFigures({ definitionKey: 'k', version: 1, mode: 'live', asOf: null, nodes: { a: { ACTIVE: 1 } } })).toEqual([{ node: 'a', tokens: 1, incidents: 0 }]);
  });

  it('keeps the process, version and step of an incident list in its link, and ignores a bad one', () => {
    const route = { name: 'incidents', process: 'kyc-onboarding', version: 2, step: 'screen-sanctions' } as const;
    expect(formatRoute(route)).toBe('#/incidents?process=kyc-onboarding&version=2&step=screen-sanctions');
    expect(parseRoute(formatRoute(route))).toEqual(route);
    expect(parseRoute('#/incidents?process=../x&step=a')).toEqual({ name: 'incidents' });
  });

  it('CKP_091_counts_sit_under_their_steps_as_plain_figures_that_open_the_lists', async () => {
    const engine = resetProcessEngine();
    setScenario('processes-reviewer');
    open('#/processes/kyc-onboarding/2');

    const tokens = await screen.findByRole('link', { name: /12 at Approve high-risk customer/ });
    expect(tokens).toHaveAttribute('href', '#/processes/kyc-onboarding/2/cases');
    expect(tokens.textContent).toContain('12');
    expect(screen.getByRole('link', { name: /6 at Verify identity documents/ })).toBeInTheDocument();
    const incident = screen.getByRole('link', { name: /1 open at Screen sanctions lists/ });
    expect(incident).toHaveAttribute('href', '#/incidents?process=kyc-onboarding&version=2&step=screen-sanctions');
    expect(engine.requests.some((request) => /node-stats\?mode=live/.test(request.path))).toBe(true);
    expect(screen.getByRole('list', { name: 'Live figures' })).toBeInTheDocument();
  });

  it('CKP_091_a_count_opens_the_incidents_of_that_process_and_step_only', async () => {
    const engine = resetProcessEngine();
    setScenario('processes-reviewer');
    open('#/incidents?process=kyc-onboarding&version=2&step=screen-sanctions');

    const table = await screen.findByRole('table');
    await waitFor(() => { expect(within(table).getAllByRole('row').length).toBe(2); });
    expect(within(table).getByText('Screen sanctions lists')).toBeInTheDocument();
    expect(engine.requests.some((request) => /\/incidents\?.*definitionKey=kyc-onboarding.*version=2/.test(request.path))).toBe(true);
    expect(await screen.findByText('Show all incidents')).toBeInTheDocument();
    await userEvent.click(screen.getByText('Show all incidents'));
    await waitFor(() => { expect(window.location.hash).toBe('#/incidents'); });
  });

  it('CKP_091_a_process_without_live_figures_draws_none', async () => {
    resetProcessEngine({ liveCounts: 'empty' });
    setScenario('processes-reviewer');
    open('#/processes/kyc-onboarding/2');

    await screen.findByLabelText(/Rate customer risk/);
    await waitFor(() => { expect(document.querySelectorAll('.dg-live__link').length).toBe(1); });
  });
});
