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

/* #123: how long each step takes over a chosen period, read from the engine's hourly rollup. */
describe('Step timing (#123)', () => {
  it('CKP_026_each_step_shows_its_typical_and_slow_time_in_flow_order', async () => {
    const engine = resetProcessEngine();
    setScenario('processes-reviewer');
    open('#/processes/kyc-onboarding/2/timing');

    await userEvent.click(await screen.findByRole('button', { name: 'As a table' }));
    const table = await screen.findByRole('table', { name: 'How long each step takes' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((row) => within(row).getByRole('rowheader').textContent)).toEqual([
      expect.stringContaining('Onboarding requested'), expect.stringContaining('Screen sanctions lists'), expect.stringContaining('Verify identity documents'),
      expect.stringContaining('Rate customer risk'), expect.stringContaining('Approve high-risk customer'), expect.stringContaining('Open account'),
    ]);
    const approve = rows[4];
    expect(approve).toBeDefined();
    const cells = within(approve as HTMLElement).getAllByRole('cell').map((cell) => cell.textContent);
    expect(cells).toEqual(['187', '1 h 20 min', '2 h 45 min', '—', '—']);
    expect(engine.requests.some((request) => /node-stats\?.*mode=window/.test(request.path))).toBe(true);
    expect(screen.getByText(/Figures up to .*newer cases are not counted yet/)).toBeInTheDocument();
  });

  it('CKP_026_the_diagram_tints_each_step_by_slow_time_and_names_the_key', async () => {
    resetProcessEngine();
    setScenario('processes-reviewer');
    open('#/processes/kyc-onboarding/2/timing');

    const box = await screen.findByLabelText(/Approve high-risk customer.*slow time 2 h 45 min/);
    expect(box).toHaveAttribute('data-heat', '4');
    const key = screen.getByRole('list', { name: 'Slow time against the slowest step' });
    expect(within(key).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Under 25%', '25 to 50%', '50 to 75%', '75% and over']);
  });

  it('CKP_026_a_longer_period_asks_the_engine_again', async () => {
    const engine = resetProcessEngine();
    setScenario('processes-reviewer');
    open('#/processes/kyc-onboarding/2/timing');

    await userEvent.selectOptions(await screen.findByLabelText('Period'), 'Last 7 days');
    await waitFor(() => { expect(engine.requests.filter((request) => request.path.includes('/node-stats')).length).toBe(2); });
  });

  it('CKP_026_a_period_without_activity_says_so', async () => {
    resetProcessEngine({ stepTiming: 'empty' });
    setScenario('processes-reviewer');
    open('#/processes/kyc-onboarding/2/timing');

    expect(await screen.findByText('No step ran in this period.')).toBeInTheDocument();
  });
});
