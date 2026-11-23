import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { setScenario } from '../../mocks/node';
import { draftSeed, type StoredVersion } from '../../mocks/ruleEngine';

/** card-fee draft 2 with the condition of R1's Tier cell replaced, as the engine would hold it. */
function seedWith(tierCell: string | undefined): StoredVersion[] {
  return draftSeed().map((stored) => {
    if (stored.ruleSet !== 'card-fee' || stored.version !== 2 || tierCell === undefined) return stored;
    const rules = stored.definition.rules.map((rule) => (rule.id === 'R1' ? { ...rule, when: { ...rule.when, tier: tierCell } } : rule));
    const definition = { ...stored.definition, rules };
    return { ...stored, definition, source: JSON.stringify(definition, null, 2) };
  });
}

let engine: ReturnType<typeof resetRuleEngine>;

function open(scenario: Scenario, tierCell?: string, locale?: 'vi') {
  engine = resetRuleEngine(seedWith(tierCell));
  setScenario(scenario);
  window.location.hash = '#/rules/card-fee/2/edit';
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const opener = (cell = 'Row R1, Tier') => screen.findByRole('button', { name: `And/Or condition builder for ${cell}` });
const builder = () => screen.getByRole('region', { name: 'Condition of Row R1, Tier' });

beforeEach(() => { window.location.hash = ''; });

describe('nested condition builder', () => {
  it('CK_F_45_ui_a_the_builder_is_off_until_the_host_turns_it_on', async () => {
    open('rules-author');
    await screen.findByRole('button', { name: /^Row R1, Tier/ });
    expect(screen.queryByRole('button', { name: /And\/Or condition builder/ })).not.toBeInTheDocument();
  });

  it('CK_F_45_ui_b_a_text_condition_opens_as_groups_and_reads_back_in_words_without_the_expression', async () => {
    const user = userEvent.setup();
    open('rules-author-builder', "(txCount >= 20 or balance >= 5000000 VND) and not (tier == 'CLASSIC')");
    // The cell shows the words, never the expression.
    const summary = await screen.findByRole('button', { name: /^Row R1, Tier: .*Open the builder to change it\.$/ });
    expect(summary).toHaveTextContent('(Tx count is at least 20 or Balance is at least 5,000,000 VND) and not Tier is Classic');
    expect(screen.queryByText(/txCount >= 20/)).not.toBeInTheDocument();
    await user.click(summary);
    const panel = builder();
    const top = within(panel).getByRole('group', { name: 'And group, level 1' });
    expect(within(top).getByRole('group', { name: 'Or group, level 2' })).toBeInTheDocument();
    expect(within(top).getByRole('group', { name: 'Not, level 2' })).toBeInTheDocument();
    // The reading is a live region; the expression is only under Details.
    expect(within(panel).getByText(/Reads as/).closest('p')).toHaveAttribute('aria-live', 'polite');
    const details = within(panel).getByText('Details').closest('details') as HTMLDetailsElement;
    expect(details.open).toBe(false);
    expect(within(details).getByText("(txCount >= 20 or balance >= 5000000 VND) and not (tier == 'CLASSIC')")).toBeInTheDocument();
  });

  it('CK_F_45_ui_c_a_condition_the_builder_cannot_show_stays_text_and_says_why', async () => {
    const user = userEvent.setup();
    open('rules-author-builder', "startsWith(tier, 'G') and txCount > 1");
    // The text editor stays in the cell.
    const cell = await screen.findByLabelText('Row R1, Tier', { exact: true });
    expect(cell).toHaveValue("startsWith(tier, 'G') and txCount > 1");
    await user.click(await opener());
    const panel = builder();
    expect(within(panel).getByRole('status')).toHaveTextContent('This condition stays as text.It calls a function, which the builder cannot show.');
    expect(within(panel).queryByRole('group', { name: /group, level/ })).not.toBeInTheDocument();
    expect(cell).toHaveValue("startsWith(tier, 'G') and txCount > 1");
  });

  it('CK_F_45_ui_d_a_nested_condition_is_built_by_keyboard_and_saved_as_the_engines_expression', async () => {
    const user = userEvent.setup();
    open('rules-author-builder');
    const button = await opener();
    button.focus();
    await user.keyboard('{Enter}');
    const panel = builder();
    expect(button).toHaveAttribute('aria-expanded', 'true');

    // R1 is Tier == PLATINUM: one row inside the And group.
    const top = within(panel).getByRole('group', { name: 'And group, level 1' });
    await user.click(within(top).getByRole('button', { name: 'Add condition' }));
    // The new row takes the focus, on its attribute.
    const rows = within(top).getAllByRole('group', { name: /^Tier is/ });
    expect(rows.length).toBeGreaterThan(0);
    const newest = within(top).getAllByRole('combobox', { name: 'Attribute' }).at(-1) as HTMLElement;
    await waitFor(() => { expect(newest).toHaveFocus(); });
    await user.selectOptions(newest, 'Tx count');
    const row = newest.closest('[role="group"]') as HTMLElement;
    await user.selectOptions(within(row).getByRole('combobox', { name: 'Operator' }), '≥');
    await user.type(within(row).getByRole('textbox', { name: 'Value' }), '20000');
    expect(within(row).getByRole('textbox', { name: 'Value' })).toHaveValue('20,000');
    expect(within(panel).getByText(/Reads as/).closest('p')).toHaveTextContent("Tier is Platinum and Tx count is at least 20,000");

    // A group chosen from the menu with the keyboard, then wrapped in Not.
    const addGroup = within(top).getByRole('button', { name: 'Add group' });
    addGroup.focus();
    await user.keyboard('{Enter}');
    await user.keyboard('{ArrowDown}{Enter}');
    expect(within(panel).getByRole('group', { name: 'Or group, level 2' })).toBeInTheDocument();
    const nested = within(panel).getByRole('group', { name: 'Or group, level 2' });
    const inner = within(nested).getAllByRole('combobox', { name: 'Attribute' })[0] as HTMLElement;
    await user.selectOptions(inner, 'Balance');
    const innerRow = inner.closest('[role="group"]') as HTMLElement;
    await user.selectOptions(within(innerRow).getByRole('combobox', { name: 'Operator' }), 'lt');
    await user.type(within(innerRow).getByRole('textbox', { name: 'Value' }), '1000000');
    await user.click(within(nested).getAllByRole('button', { name: /^Actions for Balance/ })[0] as HTMLElement);
    await user.click(screen.getByRole('menuitem', { name: 'Wrap in Not' }));
    expect(within(panel).getByRole('group', { name: 'Not, level 2' })).toBeInTheDocument();

    // The stored text is the engine's expression, shown only under Details.
    await user.click(within(panel).getByText('Details'));
    expect(within(panel).getByText("tier == 'PLATINUM' and txCount >= 20000 and not (balance < 1000000 VND)")).toBeInTheDocument();

    // Escape closes the builder and gives the focus back to the button that opened it.
    await user.keyboard('{Escape}');
    await waitFor(() => { expect(screen.queryByRole('region', { name: 'Condition of Row R1, Tier' })).not.toBeInTheDocument(); });
    await waitFor(() => { expect(screen.getByRole('button', { name: /^Row R1, Tier: / })).toHaveFocus(); });

    // The check passes and the draft saves the expression text.
    const save = screen.getByRole('button', { name: 'Save draft' });
    await waitFor(() => { expect(save).toBeEnabled(); });
    await user.click(save);
    await waitFor(() => {
      const saved = engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 2);
      expect(saved?.definition.rules.find((rule) => rule.id === 'R1')?.when['tier']).toBe("tier == 'PLATINUM' and txCount >= 20000 and not (balance < 1000000 VND)");
    });
  });

  it('CK_F_45_ui_e_an_engine_problem_points_at_the_row_it_belongs_to', async () => {
    const user = userEvent.setup();
    // The balance has no currency: the engine refuses that comparison, at its operator.
    open('rules-author-builder', "(txCount >= 20 or balance >= 5000000) and tier == 'GOLD'");
    await user.click(await screen.findByRole('button', { name: /^Row R1, Tier: / }));
    const panel = builder();
    const count = within(panel).getAllByRole('textbox', { name: 'Value' })[0] as HTMLElement;
    await user.clear(count);
    await user.type(count, '30');
    await user.keyboard('{Enter}');
    const bad = await waitFor(() => {
      const row = within(panel).getByRole('group', { name: 'Balance is at least 5,000,000' });
      expect(within(row.parentElement as HTMLElement).getByText(/The cell mixes values of different types/)).toBeInTheDocument();
      return row;
    });
    // Only that row is marked; the row beside it is not.
    expect(bad).toHaveClass('cb-leaf--bad');
    expect(within(panel).getByRole('group', { name: 'Tx count is at least 30' })).not.toHaveClass('cb-leaf--bad');
    expect(bad).toHaveAccessibleDescription(/The cell mixes values of different types/);
    // The save stays blocked until it is fixed.
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
  });

  it('CK_F_45_ui_f_the_builder_speaks_vietnamese', async () => {
    const user = userEvent.setup();
    open('rules-author-builder', "(txCount >= 20 or balance >= 5000000 VND) and not (tier == 'CLASSIC')", 'vi');
    await user.click(await screen.findByRole('button', { name: /^Dòng R1, Hạng: / }));
    const panel = screen.getByRole('region', { name: 'Điều kiện của Dòng R1, Hạng' });
    expect(within(panel).getByRole('group', { name: 'Nhóm Và, cấp 1' })).toBeInTheDocument();
    expect(within(panel).getByRole('group', { name: 'Nhóm Hoặc, cấp 2' })).toBeInTheDocument();
    expect(within(panel).getByText(/Đọc là/).closest('p')).toHaveTextContent('(Giao dịch ít nhất là 20 hoặc Số dư ít nhất là 5.000.000 VND) và không phải Hạng là Classic');
  });
});
