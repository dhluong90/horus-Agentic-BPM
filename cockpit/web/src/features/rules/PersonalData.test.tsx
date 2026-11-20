import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { RuleSetDefinition, VersionWarning } from '../../api/rules/schemas';
import { I18nProvider } from '../../i18n/I18nProvider';
import { personalDataDemo } from '../../mocks/demoCatalogue';
import { metaRulesOnly, type Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import type { AcknowledgementSettings, StoredVersion } from '../../mocks/ruleEngine';
import { PersonalDataWarnings } from './components/PersonalDataWarnings';

function open(hash: string, scenario: Scenario = 'rules-approver', locale?: 'vi', seed: StoredVersion[] = personalDataDemo(), acknowledgement: AcknowledgementSettings = {}) {
  resetRuleEngine(seed, acknowledgement);
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

/** The bodies of the approve requests the screen sent. */
function approveBodies(): string[] {
  const bodies: string[] = [];
  mockHost.events.on('request:start', ({ request }) => {
    if (request.method === 'POST' && request.url.includes(':approve')) void request.clone().text().then((text) => { bodies.push(text); });
  });
  return bodies;
}

const dock = async () => (await screen.findByRole('heading', { name: 'Your decision' })).closest('section') as HTMLElement;
const ACCEPT = 'I accept that these inputs are logged without masking: National ID, Email';

describe('personal data on the approval screen (PD-099, PD-104)', () => {
  it('CKP_037_each_undeclared_input_is_one_short_line_in_the_dock_with_no_category_or_code', async () => {
    open('#/rules/customer-screening/2');
    const warning = within(await dock()).getByRole('group', { name: 'Personal data without a masking rule' });
    expect(within(warning).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['National ID: no masking rule', 'Email: no masking rule']);
    expect(warning).toHaveTextContent('These values will be kept unmasked in the decision log, which cannot be changed later.');
    expect(warning).not.toHaveTextContent('national id');
    expect(warning).not.toHaveTextContent('UNMASKED_PERSONAL_DATA');
  });

  it('CKP_037_approve_waits_for_the_acceptance_and_sends_the_accepted_code', async () => {
    const user = userEvent.setup();
    const bodies = approveBodies();
    open('#/rules/customer-screening/2');
    const card = await dock();
    const approve = within(card).getByRole('button', { name: 'Approve and publish' });
    await waitFor(() => { expect(approve).toBeDisabled(); });
    expect(approve).toHaveAccessibleDescription(ACCEPT);
    await user.click(within(card).getByRole('checkbox', { name: ACCEPT }));
    await waitFor(() => { expect(approve).toBeEnabled(); });
    await user.click(approve);
    expect(await screen.findByText('Version 2 is published.')).toBeInTheDocument();
    expect(bodies.map((body) => JSON.parse(body) as unknown)).toEqual([{ acknowledgedWarnings: [
      { code: 'UNMASKED_PERSONAL_DATA', input: 'nationalId' },
      { code: 'UNMASKED_PERSONAL_DATA', input: 'email' },
    ] }]);
  });

  it('CKP_037_a_refusal_shows_the_returned_set_with_the_box_cleared_and_is_never_retried', async () => {
    const user = userEvent.setup();
    const bodies = approveBodies();
    // Configuration changed between screen and click: the engine now also needs the phone number accepted.
    const returned = ['nationalId', 'email', 'phoneNumber'].map((input) => ({ code: 'UNMASKED_PERSONAL_DATA', input, matchedName: input }));
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/customer-screening/versions/2:approve', () => HttpResponse.json(
      { status: 409, code: 'WARNINGS_NOT_ACKNOWLEDGED', title: 'Conflict', detail: 'needs an acknowledgement', warnings: returned, setting: ['UNMASKED_PERSONAL_DATA'] },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    )));
    open('#/rules/customer-screening/2');
    const card = await dock();
    await user.click(within(card).getByRole('checkbox', { name: ACCEPT }));
    await user.click(within(card).getByRole('button', { name: 'Approve and publish' }));
    expect(await within(card).findByRole('alert')).toHaveTextContent('Approval needs this acceptance. Tick the box and approve again.');
    const warning = within(card).getByRole('group', { name: 'Personal data without a masking rule' });
    expect(within(warning).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['National ID: no masking rule', 'Email: no masking rule', 'Phone number: no masking rule']);
    const again = within(card).getByRole('checkbox', { name: 'I accept that these inputs are logged without masking: National ID, Email, Phone number' });
    expect(again).not.toBeChecked();
    expect(within(card).getByRole('button', { name: 'Approve and publish' })).toBeDisabled();
    await waitFor(() => { expect(bodies).toHaveLength(1); });
    // The approver ticks again; only then is the request sent, with the returned set.
    await user.click(again);
    await user.click(within(card).getByRole('button', { name: 'Approve and publish' }));
    await waitFor(() => { expect(bodies).toHaveLength(2); });
    expect((JSON.parse(bodies[1] ?? '{}') as { acknowledgedWarnings: unknown }).acknowledgedWarnings).toEqual(returned.map(({ code, input }) => ({ code, input })));
  });

  it('CKP_037_a_tenant_that_masks_undeclared_inputs_by_default_needs_no_acceptance', async () => {
    // The engine marks no warning for a tenant whose default masks undeclared inputs; /meta is not read for it.
    open('#/rules/customer-screening/2', 'rules-approver', undefined, personalDataDemo(), { maskDefault: true });
    const card = await dock();
    expect(within(card).getByRole('group', { name: 'Personal data without a masking rule' })).toBeInTheDocument();
    expect(within(card).queryByRole('checkbox')).not.toBeInTheDocument();
    await waitFor(() => { expect(within(card).getByRole('button', { name: 'Approve and publish' })).toBeEnabled(); });
  });

  it('CKP_037_the_checkbox_covers_exactly_the_warnings_the_engine_marks_and_ignores_the_meta_settings', async () => {
    const user = userEvent.setup();
    const bodies = approveBodies();
    // /meta says nothing needs accepting; the engine marks National ID only. The engine's answer wins.
    mockHost.use(http.get('*/api/bpm/meta', () => HttpResponse.json({
      ...metaRulesOnly, modules: [{ ...metaRulesOnly.modules[0], settings: { 'lifecycle.acknowledge-warnings': '', 'decision-log.mask-default': true } }],
    })));
    open('#/rules/customer-screening/2', 'rules-approver', undefined, personalDataDemo(), { requiresAcknowledgement: ({ input }) => input === 'nationalId' });
    const card = await dock();
    const warning = within(card).getByRole('group', { name: 'Personal data without a masking rule' });
    expect(within(warning).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['National ID: no masking rule', 'Email: no masking rule']);
    expect(within(card).getAllByRole('checkbox')).toHaveLength(1);
    const accept = within(card).getByRole('checkbox', { name: 'I accept that this input is logged without masking: National ID' });
    const approve = within(card).getByRole('button', { name: 'Approve and publish' });
    await waitFor(() => { expect(approve).toBeDisabled(); });
    await user.click(accept);
    await waitFor(() => { expect(approve).toBeEnabled(); });
    await user.click(approve);
    expect(await screen.findByText('Version 2 is published.')).toBeInTheDocument();
    expect(bodies.map((body) => JSON.parse(body) as unknown)).toEqual([{ acknowledgedWarnings: [{ code: 'UNMASKED_PERSONAL_DATA', input: 'nationalId' }] }]);
  });

  it('CKP_037_masking_changes_read_as_sentences_above_the_table_with_a_marker_on_the_header', async () => {
    open('#/rules/customer-screening/2');
    const notes = await screen.findByRole('list', { name: 'Changes outside the rows' });
    expect(within(notes).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'National ID: mask removed (masked → not masked by default)',
      'Condition column added: Email, not masked by default',
    ]);
    expect(screen.getByText('Mask removed on 1 column')).toBeInTheDocument();
    const table = screen.getByRole('table');
    const headers = within(table).getAllByRole('columnheader');
    const named = (label: string) => headers.filter((th) => th.textContent.startsWith(label));
    // The proposed side's National ID header carries the marker and its sentence; the base side's does not.
    expect(named('National ID').map((th) => th.querySelector('.masking-flag')?.getAttribute('title') ?? null))
      .toEqual([null, 'National ID: mask removed (masked → not masked by default)']);
    expect(named('Email').map((th) => th.querySelector('.masking-flag') !== null)).toEqual([true]);
    expect(named('Phone number').every((th) => th.querySelector('.masking-flag') === null)).toBe(true);
    expect(screen.queryByText(/Condition column National ID changed/)).not.toBeInTheDocument();
    expect(screen.queryByText('No row changes.')).not.toBeInTheDocument();
  });

  it('CKP_037_a_version_with_no_warnings_shows_no_warning_and_approves_without_an_acceptance', async () => {
    const user = userEvent.setup();
    const bodies = approveBodies();
    open('#/rules/card-fee/2');
    const card = await dock();
    expect(within(card).queryByRole('group')).not.toBeInTheDocument();
    expect(within(card).queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByText(/Mask removed/)).not.toBeInTheDocument();
    await user.click(within(card).getByRole('button', { name: 'Approve and publish' }));
    expect(await screen.findByText('Version 2 is published.')).toBeInTheDocument();
    expect(bodies).toEqual(['']);
  });

  it('CKP_037_a_host_that_needs_no_acceptance_shows_the_warning_without_the_box', async () => {
    open('#/rules/customer-screening/2', 'rules-approver', undefined, personalDataDemo(), { acknowledgeWarnings: [] });
    const card = await dock();
    expect(within(card).getByRole('group', { name: 'Personal data without a masking rule' })).toBeInTheDocument();
    expect(within(card).queryByRole('checkbox')).not.toBeInTheDocument();
    await waitFor(() => { expect(within(card).getByRole('button', { name: 'Approve and publish' })).toBeEnabled(); });
  });

  it('CKP_037_the_author_sees_the_same_warning_on_a_draft_before_requesting_publication', async () => {
    const draft = personalDataDemo().map((v) => (v.ruleSet === 'customer-screening' && v.version === 2 ? { ...v, status: 'DRAFT' as const, requestedBy: null, requestedAt: null, createdBy: 'lan' } : v));
    open('#/rules/customer-screening/2', 'rules-author', undefined, draft);
    const warning = await screen.findByRole('group', { name: 'Personal data without a masking rule' });
    expect(warning).toHaveTextContent('National ID: no masking rule');
    expect(within(warning).queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Request publish' })).toBeInTheDocument();
  });

  it('CKP_037_a_newer_masking_status_on_one_column_leaves_the_other_marks_in_place', async () => {
    const body = {
      ruleSet: 'customer-screening', version: 2, base: 1, canonicalHash: 'a'.repeat(64), baseCanonicalHash: 'b'.repeat(64), sourceHashChanged: true,
      effectiveFrom: { from: null, to: null }, header: [], outputs: { added: [], removed: [], changed: [], reordered: false }, rows: [], unchangedCount: 2,
      inputs: { added: ['email'], removed: [], changed: [{ name: 'nationalId', fields: [{ path: '/mask', from: true, to: null, nonAscii: false }] }], reordered: false },
      masking: [
        { input: 'nationalId', from: 'MASKED', to: 'NOT_MASKED_BY_DEFAULT', changed: true, personalDataName: 'national id' },
        { input: 'email', from: null, to: 'TOKENISED', changed: true, personalDataName: null },
      ],
    };
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/customer-screening/versions/2/diff', () => HttpResponse.json(body)));
    open('#/rules/customer-screening/2');
    const notes = await screen.findByRole('list', { name: 'Changes outside the rows' });
    expect(within(notes).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'National ID: mask removed (masked → not masked by default)',
      'Condition column added: Email',
    ]);
  });

  it('TR_UI_04_the_warning_acceptance_and_sentences_read_in_vietnamese', async () => {
    open('#/rules/customer-screening/2', 'rules-approver', 'vi');
    const warning = await screen.findByRole('group', { name: 'Dữ liệu cá nhân chưa có quy tắc che' });
    expect(within(warning).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['National ID: chưa có quy tắc che', 'Email: chưa có quy tắc che']);
    expect(warning).toHaveTextContent('Các giá trị này sẽ được lưu không che trong nhật ký quyết định và không thể sửa về sau.');
    expect(within(warning).getByRole('checkbox', { name: 'Tôi chấp nhận các trường sau được lưu không che trong nhật ký quyết định: National ID, Email' })).toBeInTheDocument();
    const notes = await screen.findByRole('list', { name: 'Thay đổi ngoài các dòng' });
    expect(within(notes).getAllByRole('listitem')[0]).toHaveTextContent('National ID: bỏ che (đã che → không che theo mặc định)');
    expect(screen.getByText('Bỏ che ở 1 cột')).toBeInTheDocument();
  });
});

describe('the warning text follows the number of inputs', () => {
  const definition: RuleSetDefinition = { schemaVersion: 1, ruleSet: 'x', hitPolicy: 'first', inputs: [{ name: 'nationalId', type: 'string', description: 'National ID' }], outputs: [], rules: [] };
  const one: VersionWarning[] = [{ code: 'UNMASKED_PERSONAL_DATA', input: 'nationalId', matchedName: 'national id', message: 'm', requiresAcknowledgement: true }];

  it.each([
    ['en', 'Its values will be kept unmasked in the decision log, which cannot be changed later.', 'I accept that this input is logged without masking: National ID'],
    ['vi', 'Giá trị của trường này sẽ được lưu không che trong nhật ký quyết định và không thể sửa về sau.', 'Tôi chấp nhận trường sau được lưu không che trong nhật ký quyết định: National ID'],
  ] as const)('TR_UI_04_one_input_reads_in_the_singular_%s', (locale, why, accept) => {
    render(<I18nProvider initialLocale={locale}><PersonalDataWarnings warnings={one} definition={definition} acceptance={{ checked: false, onChange: () => undefined, needed: false, id: 'a', inputs: ['nationalId'] }} /></I18nProvider>);
    expect(screen.getByText(why)).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: accept })).toBeInTheDocument();
  });
});
