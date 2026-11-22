import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { personalDataDemo } from '../../mocks/demoCatalogue';
import { resetProcessEngine, resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { UnclassifiedNote } from '../../components/UnclassifiedNote';
import { I18nProvider } from '../../i18n/I18nProvider';

/** PD-146: unclassified fields are a calm note in the review, never a block, never a value. */
describe('#438 not classified in the review', () => {
  it('a_pending_rule_set_version_lists_its_unclassified_inputs_calmly_and_approval_still_works', async () => {
    const user = userEvent.setup();
    const bodies: string[] = [];
    mockHost.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && request.url.includes(':approve')) void request.clone().text().then((text) => { bodies.push(text); });
    });
    resetRuleEngine(personalDataDemo(), { warnUnclassified: true });
    setScenario('rules-approver');
    window.location.hash = '#/rules/customer-screening/2';
    render(<App />);

    const note = await screen.findByRole('group', { name: 'Not classified' });
    expect(note).toHaveTextContent('Masking is not set for these fields. This reminder never blocks approval.');
    expect(within(note).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['National ID', 'Email', 'Segment']);
    expect(note).not.toHaveTextContent(/UNCLASSIFIED_INPUT|undefined/);
    // Not a dialog, and not an alert: nothing interrupts the reviewer.
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(note).not.toHaveAttribute('role', 'alert');

    // The personal-data acceptance is its own thing and only it is sent back.
    const dock = (await screen.findByRole('heading', { name: 'Your decision' })).closest('section') as HTMLElement;
    const approve = within(dock).getByRole('button', { name: 'Approve and publish' });
    await user.click(within(dock).getByRole('checkbox', { name: /I accept that these inputs are logged without masking/ }));
    await waitFor(() => { expect(approve).toBeEnabled(); });
    await user.click(approve);
    expect(await screen.findByText('Version 2 is published.')).toBeInTheDocument();
    expect(JSON.parse(bodies[0] ?? '{}')).toMatchObject({ acknowledgedWarnings: [{ code: 'UNMASKED_PERSONAL_DATA' }, { code: 'UNMASKED_PERSONAL_DATA' }] });
    expect(bodies[0]).not.toContain('UNCLASSIFIED');
  });

  it('with_the_warning_off_the_engine_sends_none_and_no_note_shows', async () => {
    resetRuleEngine(personalDataDemo());
    setScenario('rules-approver');
    window.location.hash = '#/rules/customer-screening/2';
    render(<App />);
    await screen.findByRole('heading', { name: 'Your decision' });
    expect(screen.queryByRole('group', { name: 'Not classified' })).not.toBeInTheDocument();
  });

  it('a_pending_process_version_lists_its_unclassified_fields_by_step_name_and_never_blocks_approval', async () => {
    resetProcessEngine({ unclassified: true });
    setScenario('processes-approver');
    window.location.hash = '#/processes/kyc-onboarding/3/changes';
    render(<App />);

    const note = await screen.findByRole('group', { name: 'Not classified' });
    expect(within(note).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Verify identity documents: Document type (input)', 'Verify identity documents: Summary (output)',
    ]);
    expect(note).not.toHaveTextContent(/UNCLASSIFIED_FIELD/);
    const dock = screen.getByRole('region', { name: 'Your decision' });
    expect(within(dock).getByRole('button', { name: /Approve change/ })).toBeInTheDocument();
  });

  it('a_long_list_names_four_and_counts_the_rest_in_vietnamese', () => {
    render(<I18nProvider initialLocale="vi"><UnclassifiedNote fields={['a', 'b', 'c', 'd', 'e', 'f']} /></I18nProvider>);
    const note = screen.getByRole('group', { name: 'Chưa phân loại' });
    expect(within(note).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['a', 'b', 'c', 'd', 'và 2 mục khác']);
  });
});
