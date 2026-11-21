import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { draftSeed } from '../../mocks/ruleEngine';
import { placeProblems } from './model/engineErrors';
import { Toast } from './components/Toast';
import { I18nProvider } from '../../i18n/I18nProvider';

/** Rule Engine calls the screens made: method, path, and the two headers a write is bound by. */
let calls: { method: string; path: string; key: string | null; ifMatch: string | null }[] = [];
let engine: ReturnType<typeof resetRuleEngine>;

beforeEach(() => {
  calls = [];
  engine = resetRuleEngine(draftSeed());
  mockHost.events.on('request:start', ({ request }) => {
    const path = decodeURIComponent(new URL(request.url).pathname);
    if (path.startsWith('/api/bpm/rules')) {
      calls.push({ method: request.method, path: path.replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key'), ifMatch: request.headers.get('If-Match') });
    }
  });
  return () => { mockHost.events.removeAllListeners(); };
});

function open(hash: string, scenario: Scenario = 'rules-author', locale?: 'vi') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const saves = () => calls.filter((c) => c.method === 'PUT');
const checks = () => calls.filter((c) => c.path.endsWith(':validate'));

/** Opens the grid of card-fee draft 2 and empties the amount of R2's balance condition: an incomplete comparison. */
async function breakR2Balance(user: ReturnType<typeof userEvent.setup>) {
  const cell = await screen.findByLabelText('Row R2, Balance', { exact: true });
  await user.clear(cell);
  await user.keyboard('{Enter}');
  await waitFor(() => { expect(cell).toHaveAttribute('aria-invalid', 'true'); });
  return cell;
}

describe('grid editor', () => {
  it('CKP_006_grid_editor_shows_row_validation_errors', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/edit');
    const cell = await breakR2Balance(user);
    // What to type, with the cell's saved amount as the example; the engine's catalogue sentence stays as the detail.
    expect(cell).toHaveAccessibleDescription('Enter an amount, for example 50,000,000 VND. The cell is not a valid expression.');
    // The Changes card shows committed, valid cells only: never the half-edited "≥ 50,000,000 VND → ≥".
    const changes = screen.getByRole('region', { name: 'Changes in this draft' });
    expect(changes).not.toHaveTextContent('→');
    expect(changes).toHaveTextContent('Changed cells show here once you leave them.');
    expect(screen.queryByText(/SYNTAX_ERROR/)).not.toBeInTheDocument();
    expect(screen.getByText('1 to fix')).toBeInTheDocument();
    // Saving is blocked until the cell is fixed, and the button says why in one line.
    const save = screen.getByRole('button', { name: 'Save draft' });
    expect(save).toBeDisabled();
    expect(save).toHaveAccessibleDescription('Fix the marked cells to save.');
    // The check stored nothing: no write, no Idempotency-Key, no If-Match.
    expect(saves()).toEqual([]);
    expect(checks().at(-1)).toMatchObject({ method: 'POST', path: '/rule-sets/card-fee/versions/2:validate', key: null, ifMatch: null });
    expect(engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2)?.lockVersion).toBe(0);

    await user.type(cell, '40000000');
    await user.keyboard('{Enter}');
    await waitFor(() => { expect(cell).toHaveAttribute('aria-invalid', 'false'); });
    expect(screen.queryByText('1 to fix')).not.toBeInTheDocument();
    await waitFor(() => { expect(save).toBeEnabled(); });
    expect(changes).toHaveTextContent('Balance: ≥ 50,000,000 VND → ≥ 40,000,000 VND');
  });

  it('CKP_006_a_cell_being_typed_stays_out_of_the_changes_card', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/edit');
    const fee = await screen.findByLabelText('Row R3, Fee');
    await user.clear(fee);
    await user.type(fee, '400');
    const changes = screen.getByRole('region', { name: 'Changes in this draft' });
    // Not committed yet: the dock counts the change, the card waits for Enter or leaving the cell.
    expect(screen.getByText('1 unsaved change')).toBeInTheDocument();
    expect(changes).not.toHaveTextContent('Fee');
    await user.type(fee, '00{Enter}');
    await waitFor(() => { expect(changes).toHaveTextContent('Fee: 35,000 VND → 40,000 VND'); });
  });

  it('CKP_006_the_dock_says_once_that_there_is_nothing_to_save', async () => {
    open('#/rules/card-fee/2/edit');
    const save = await screen.findByRole('button', { name: 'Save draft' });
    // The row's ⋯ menu is named, and shows its name as a tooltip on hover and keyboard focus.
    expect(screen.getByRole('button', { name: 'Actions for row R1' })).toHaveAttribute('data-tip', 'Actions for row R1');
    expect(save).toHaveAccessibleDescription('Nothing to save yet.');
    expect(screen.getAllByText(/Nothing to save yet|No changes yet/)).toHaveLength(1);
  });

  it('CKP_006_problems_read_in_the_language_of_the_screen', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/edit', 'rules-author', 'vi');
    const cell = await screen.findByLabelText('Dòng R2, Balance', { exact: true });
    await user.clear(cell);
    await user.keyboard('{Enter}');
    await waitFor(() => { expect(cell).toHaveAccessibleDescription('Nhập số tiền, ví dụ 50.000.000 VND. Ô này không phải là biểu thức hợp lệ.'); });
    expect(screen.queryByText(/The cell is not a valid expression/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lưu bản nháp' })).toHaveAccessibleDescription('Sửa các ô được đánh dấu để lưu.');
  });

  it('CKP_006_save_replaces_the_draft_with_its_lock_version', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/edit');
    const fee = await screen.findByLabelText('Row R3, Fee');
    expect(screen.getByRole('button', { name: 'Save draft' })).toHaveAccessibleDescription('Nothing to save yet.');
    await user.clear(fee);
    await user.type(fee, '40000');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Draft version 2 saved')).toBeInTheDocument();
    expect(saves().map((c) => [c.path, c.ifMatch])).toEqual([['/rule-sets/card-fee/versions/2', '"0"']]);
    expect(saves()[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
    // Saved: the draft reads as a table again, with Request publish as the separate next step.
    expect(await screen.findByText('40,000 VND')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request publish' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument();
    expect(engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2)?.history?.at(-1)?.action).toBe('DRAFT_UPDATED');
    // A keyboard user lands on the title beside the saved status; the message stays until it is closed.
    await waitFor(() => { expect(document.getElementById('rule-set-title')).toHaveFocus(); });
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByText('Draft version 2 saved')).not.toBeInTheDocument();
  });

  it('CKP_006_a_saved_message_stays_until_it_is_closed', () => {
    vi.useFakeTimers();
    try {
      const { rerender } = render(<I18nProvider><Toast text="Draft version 2 saved" sticky onClose={() => { throw new Error('closed on its own'); }} /></I18nProvider>);
      act(() => { vi.advanceTimersByTime(60_000); });
      expect(screen.getByText('Draft version 2 saved')).toBeInTheDocument();
      let closed = false;
      rerender(<I18nProvider><Toast text="Imported as version 3" onClose={() => { closed = true; }} /></I18nProvider>);
      act(() => { vi.advanceTimersByTime(6_000); });
      expect(closed).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('CKP_006_the_content_hash_is_in_history_not_in_details', async () => {
    open('#/rules/card-fee/2');
    expect(await screen.findByText('Hit policy')).toBeInTheDocument();
    expect(screen.queryByText('Content hash')).not.toBeInTheDocument();
    window.location.hash = '#/rules/card-fee/2/history';
    expect((await screen.findAllByText('Content hash')).length).toBeGreaterThan(0);
  });

  it('CKP_006_a_save_conflict_keeps_the_edits_until_the_latest_is_loaded', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/edit');
    const fee = await screen.findByLabelText('Row R3, Fee');
    await user.clear(fee);
    await user.type(fee, '40000');
    // Someone saves the same draft in the meantime.
    const stored = engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2);
    if (!stored) throw new Error('draft missing');
    stored.lockVersion = 1;
    stored.definition = { ...stored.definition, rules: stored.definition.rules.map((r) => (r.id === 'R5' ? { ...r, then: { fee: '45000 VND', waived: false } } : r)) };
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByText('Someone else saved this draft after you opened it.')).toBeInTheDocument();
    expect(screen.getByLabelText('Row R3, Fee')).toHaveValue('40,000');
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
    // The warning sits in the save dock, so the grid does not move, and names the cells the other save changed.
    expect(screen.getByRole('button', { name: 'Save draft' })).toHaveAccessibleDescription(
      'Someone else saved this draft after you opened it. They changed R5 Fee. Loading the latest version replaces your edits.');
    expect(screen.getByRole('alert')).toBe(document.querySelector('.edit-dock__conflict'));
    // Never retried with the newer lock version on its own.
    expect(saves().map((c) => c.ifMatch)).toEqual(['"0"']);

    await user.click(await screen.findByRole('button', { name: 'Load the latest version' }));
    expect(screen.getByLabelText('Row R3, Fee')).toHaveValue('35,000');
    expect(screen.getByLabelText('Row R5, Fee')).toHaveValue('45,000');
    expect(screen.queryByText('Someone else saved this draft after you opened it.')).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText('Row R3, Fee'));
    await user.type(screen.getByLabelText('Row R3, Fee'), '40000');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Draft version 2 saved')).toBeInTheDocument();
    expect(saves().map((c) => c.ifMatch)).toEqual(['"0"', '"1"']);
  });

  it('CKP_006_arrow_keys_move_between_cells_and_escape_undoes_a_cell', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/edit');
    const fee = await screen.findByLabelText('Row R3, Fee');
    await user.click(fee);
    await user.keyboard('{ArrowDown}');
    expect(screen.getByLabelText('Row R4, Fee')).toHaveFocus();
    await user.keyboard('{ArrowUp}{ArrowUp}');
    expect(screen.getByLabelText('Row R2, Fee')).toHaveFocus();
    // Left inside text moves the caret; at the start it leaves the cell for the previous control of the row.
    await user.keyboard('{Home}{ArrowLeft}');
    expect(document.activeElement).toHaveAccessibleName(/^Row R2, .+: any value\./);
    await user.keyboard('{End}{ArrowRight}');
    expect(screen.getByLabelText('Row R2, Fee')).toHaveFocus();
    // Escape puts back the value the cell had when it took the focus.
    await user.click(screen.getByLabelText('Row R3, Fee'));
    await user.keyboard('{Control>}a{/Control}99');
    expect(screen.getByLabelText('Row R3, Fee')).toHaveValue('99');
    await user.keyboard('{Escape}');
    expect(screen.getByLabelText('Row R3, Fee')).toHaveValue('35,000');
    expect(screen.getByText('Arrow keys move between cells. Esc undoes a cell.')).toBeInTheDocument();
  });

  it('CKP_006_a_draft_of_another_author_is_read_only_with_the_reason', async () => {
    open('#/rules/kyc-risk/2');
    const edit = await screen.findByRole('button', { name: /Edit draft/ });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAccessibleDescription('Only hoa can change this draft.');
    window.location.hash = '#/rules/kyc-risk/2/edit';
    // The reason is said once, beside the disabled Edit draft.
    await waitFor(() => { expect(screen.getAllByText('Only hoa can change this draft.')).toHaveLength(1); });
    expect(screen.getByRole('button', { name: /Edit draft/ })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument();
    expect(saves()).toEqual([]);
  });

  it('CKP_006_an_approved_version_is_never_edited', async () => {
    open('#/rules/card-fee/1/edit');
    expect(await screen.findByText('Approved versions never change. Start a new draft.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New draft' })).toBeEnabled();
  });

  it('CKP_006_the_reviewer_reads_a_pending_version_without_any_editor', async () => {
    resetRuleEngine();
    open('#/rules/card-fee/2/edit', 'rules-approver');
    // Edit draft is shown disabled, with the reason beside it, so the reviewer reads whose turn it is.
    const edit = await screen.findByRole('button', { name: /Edit draft/ });
    expect(edit).toBeDisabled();
    expect(edit).toHaveAccessibleDescription('Waiting for approval, so it cannot change.');
    expect(screen.getAllByText('Waiting for approval, so it cannot change.')).toHaveLength(1);
    const main = document.body.querySelector('.workbench__main');
    if (!(main instanceof HTMLElement)) throw new Error('table missing');
    expect(within(main).queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Approve and publish/ })).toBeInTheDocument();
  });
});

describe('placing the engine problems', () => {
  const message = { en: 'The cell is not a valid expression.', vi: 'Ô này không phải là biểu thức hợp lệ.' };
  const rows = new Set(['R1', 'R2']);
  const columns = new Set(['tier', 'balance']);

  it('CKP_006_a_problem_goes_to_its_cell_its_row_or_the_table', () => {
    expect(placeProblems([
      { ruleId: 'R2', column: 'balance', message },
      { ruleId: 'R2', column: null, message },
      { ruleId: 'R2', column: 'removedColumn', message },
      { ruleId: 'R9', column: 'balance', message },
      { ruleId: null, column: null, message },
    ], rows, columns, 'en', 'fallback')).toEqual([
      { kind: 'cell', ruleId: 'R2', column: 'balance', message: message.en },
      { kind: 'row', ruleId: 'R2', message: message.en },
      { kind: 'row', ruleId: 'R2', message: message.en },
      { kind: 'table', message: message.en },
      { kind: 'table', message: message.en },
    ]);
  });

  it('CKP_006_a_language_the_engine_lacks_falls_back_to_one_cockpit_sentence', () => {
    expect(placeProblems([{ ruleId: 'R1', column: 'tier', message: { en: 'x' } }], rows, columns, 'vi', 'Dịch vụ đã từ chối giá trị này.'))
      .toEqual([{ kind: 'cell', ruleId: 'R1', column: 'tier', message: 'Dịch vụ đã từ chối giá trị này.' }]);
  });
});
