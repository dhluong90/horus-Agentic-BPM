import { render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import { personalDataDemo } from '../../mocks/demoCatalogue';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import type { StoredVersion } from '../../mocks/ruleEngine';

function open(hash: string, scenario: Scenario = 'rules-approver', locale?: 'vi', seed: StoredVersion[] = personalDataDemo()) {
  resetRuleEngine(seed);
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const decisionCard = async () => (await screen.findByRole('heading', { name: 'Your decision' })).closest('section') as HTMLElement;

describe('personal data on the approval screen (PD-099)', () => {
  it('CKP_037_an_undeclared_personal_data_input_is_named_beside_approve_in_plain_words', async () => {
    open('#/rules/customer-screening/2');
    const card = await decisionCard();
    const note = within(card).getByRole('note');
    expect(note).toHaveTextContent('Personal data without a masking rule');
    expect(within(note).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'National ID looks like personal data (national id) but has no masking rule.',
      'Email looks like personal data (email) but has no masking rule.',
    ]);
    expect(note).toHaveTextContent('kept unmasked in the decision log');
    // Plain words only: no engine code on screen.
    expect(card).not.toHaveTextContent('UNMASKED_PERSONAL_DATA');
    expect(within(card).getByRole('button', { name: 'Approve and publish' })).toBeEnabled();
  });

  it('CKP_037_the_diff_shows_masked_to_not_masked_and_flags_the_removed_mask', async () => {
    open('#/rules/customer-screening/2');
    const table = await screen.findByRole('table');
    const header = (name: string) => within(table).getByRole('columnheader', { name: new RegExp(`^${name}`) });
    expect(await within(table).findByText('Mask removed')).toBeInTheDocument();
    expect(header('National ID')).toHaveTextContent('Mask removed');
    expect(header('National ID')).toHaveTextContent(/Masked\s*→.*Not masked by default/);
    expect(header('Email')).toHaveTextContent('New column: Not masked by default');
    // Unchanged masking carries no mark.
    expect(header('Phone number').textContent).toBe('Phone number');
    expect(header('Segment').textContent).toBe('Segment');
    expect(screen.getByText('Mask removed on 1 column')).toBeInTheDocument();
  });

  it('CKP_037_a_version_with_no_warnings_shows_no_warning_area_and_no_masking_marks', async () => {
    open('#/rules/card-fee/2');
    await decisionCard();
    expect(await screen.findByText('1 added, 2 changed, 0 removed, 3 unchanged')).toBeInTheDocument();
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
    expect(screen.queryByText('Personal data without a masking rule')).not.toBeInTheDocument();
    expect(screen.queryByText(/Mask removed/)).not.toBeInTheDocument();
    expect(screen.queryByText('Masking changes are not available from this service.')).not.toBeInTheDocument();
  });

  it('CKP_037_the_author_sees_the_same_warning_on_a_draft_before_requesting_publication', async () => {
    const draft = personalDataDemo().map((v) => (v.ruleSet === 'customer-screening' && v.version === 2 ? { ...v, status: 'DRAFT' as const, requestedBy: null, requestedAt: null, createdBy: 'lan' } : v));
    open('#/rules/customer-screening/2', 'rules-author', undefined, draft);
    const note = await screen.findByRole('note');
    expect(note).toHaveTextContent('National ID looks like personal data (national id) but has no masking rule.');
    expect(screen.getByRole('button', { name: 'Request publish' })).toBeInTheDocument();
  });

  it('CKP_037_a_host_without_the_diff_endpoint_says_so_quietly_and_keeps_the_rows', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/customer-screening/versions/2/diff', () =>
      HttpResponse.json({ status: 404, code: 'VERSION_NOT_FOUND', title: 'Not Found' }, { status: 404, headers: { 'Content-Type': 'application/problem+json' } })));
    open('#/rules/customer-screening/2');
    expect(await screen.findByText('Masking changes are not available from this service.')).toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.queryByText('Mask removed')).not.toBeInTheDocument();
  });

  it('TR_UI_04_the_warning_and_the_masking_change_read_in_vietnamese', async () => {
    open('#/rules/customer-screening/2', 'rules-approver', 'vi');
    const note = await screen.findByRole('note');
    expect(note).toHaveTextContent('Dữ liệu cá nhân chưa có quy tắc che');
    expect(note).toHaveTextContent('National ID có vẻ là dữ liệu cá nhân (national id) nhưng chưa có quy tắc che.');
    expect(await screen.findByText('Bỏ che')).toBeInTheDocument();
    expect(screen.getByText('Bỏ che ở 1 cột')).toBeInTheDocument();
  });
});
