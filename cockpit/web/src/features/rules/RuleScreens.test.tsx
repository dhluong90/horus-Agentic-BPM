import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { mockHost, setScenario } from '../../mocks/node';

/** Engine calls the screens made, in order: method and path. */
let calls: { method: string; path: string; key: string | null }[] = [];
let contentTypes: string[] = [];

beforeEach(() => {
  calls = [];
  contentTypes = [];
  mockHost.events.on('request:start', ({ request }) => {
    const path = decodeURIComponent(new URL(request.url).pathname);
    if (path.startsWith('/api/bpm/rules') && request.method === 'POST') contentTypes.push(request.headers.get('Content-Type') ?? '');
    if (path.startsWith('/api/bpm/rules')) calls.push({ method: request.method, path: path.replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key') });
  });
  return () => { mockHost.events.removeAllListeners(); };
});

function open(hash: string, scenario: Scenario = 'rules-author', locale?: 'vi') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

/** card-fee v2 as the engine answers it, for overrides that change one field. */
const pendingV2 = {
  ruleSet: 'card-fee', version: 2, status: 'PENDING_APPROVAL', hitPolicy: 'FIRST', effectiveFrom: null, lockVersion: 1, canonicalHash: 'c'.repeat(64),
  sourceFormat: 'YAML', sourceHash: 'd'.repeat(64), requestedBy: 'lan', requestedAt: '2026-11-18T08:30:00Z', approvedBy: null, approvedAt: null,
  createdBy: 'lan', createdAt: '2026-11-18T08:00:00Z',
  definition: { schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }],
    rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }] },
};

const writes = () => calls.filter((c) => c.method === 'POST');

describe('rule set list and version view', () => {
  it('CKP_030_list_shows_each_rule_set_with_latest_status_and_published_version', async () => {
    open('#/rules');
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    await waitFor(() => { expect(within(rows[0] as HTMLElement).getByText('Waiting for approval')).toBeInTheDocument(); });
    expect(rows[0]).toHaveTextContent('card-fee');
    expect(rows[0]).toHaveTextContent('v2');
    expect(rows[0]).toHaveTextContent('v1');
    expect(rows[1]).toHaveTextContent('kyc-risk');
    expect(screen.getByRole('button', { name: 'Import file' })).toBeInTheDocument();
  });

  it('RLE_039_a_cursor_the_engine_refuses_offers_to_start_the_list_again', async () => {
    const user = userEvent.setup();
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets', () => HttpResponse.json(
      { type: 'x', title: 'Bad Request', status: 400, code: 'QUERY_INVALID', detail: 'The cursor was not issued by this endpoint', correlationId: '01JQUERY000000000000000000' },
      { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
    ), { once: true }));
    open('#/rules');
    expect(await screen.findByText('The list changed while you were reading it.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Start the list again' }));
    expect(await screen.findByRole('table')).toHaveTextContent('kyc-risk');
  });

  it('TR_STD_01_a_json_file_is_sent_as_json', async () => {
    const user = userEvent.setup();
    const view = { container: document.body };
    open('#/rules');
    await screen.findByRole('table');
    const input = view.container.querySelector<HTMLInputElement>('input[type=file]');
    if (!input) throw new Error('file input missing');
    await user.upload(input, new File(['{"schemaVersion":1}'], 'card-fee-v3.json', { type: 'application/json' }));
    // This engine release imports YAML only and says so; the cockpit shows its answer.
    expect(await screen.findByText('The service did not accept an input.')).toBeInTheDocument();
    const sent = calls.find((c) => c.method === 'POST');
    expect(sent?.path).toBe('/rule-sets');
    expect(contentTypes.at(-1)).toBe('application/json');
  });

  it('CKP_030_reader_without_author_permission_sees_no_import', async () => {
    open('#/rules', 'ready-approver');
    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: 'Import file' })).not.toBeInTheDocument();
  });

  it('CKP_030_version_reads_as_a_table_and_the_source_only_on_purpose', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('card-fee Version 1');
    expect(screen.getByText('50,000,000 VND')).toBeInTheDocument();
    expect(screen.getAllByText('Any value').length).toBeGreaterThan(0);
    expect(screen.queryByText(/schemaVersion/)).not.toBeInTheDocument();
    expect(screen.queryByText("== 'GOLD'")).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Source' }));
    expect(await screen.findByText(/"schemaVersion": 1/)).toBeInTheDocument();
    expect(calls.at(-1)?.path).toBe('/rule-sets/card-fee/versions/1');
  });

  it('TR_UI_04_vietnamese_screen_is_one_language_with_vietnamese_amounts', async () => {
    open('#/rules/card-fee/1', 'rules-author', 'vi');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('card-fee Phiên bản 1');
    expect(screen.getByText('50.000.000 VND')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Tạo bản nháp' })).toBeInTheDocument();
    expect(screen.queryByText('New draft')).not.toBeInTheDocument();
  });
});

describe('minimal draft editing', () => {
  it('CKP_032_minimal_draft_editing_shows_errors_on_cells', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await user.click(await screen.findByRole('button', { name: 'New draft' }));
    await user.clear(screen.getByLabelText('Row R1, Tier'));
    await user.click(screen.getByLabelText('Row R1, Tier'));
    await user.paste("in ['GOLD', 'PLATINUM']");
    await user.click(screen.getByRole('button', { name: 'Copy row R2' }));
    await user.click(screen.getByRole('button', { name: 'Move row R6 down' }));
    await user.click(screen.getByRole('button', { name: 'Move row R6 up' }));
    await user.clear(screen.getByLabelText('Row R6, Balance'));
    await user.type(screen.getByLabelText('Row R6, Balance'), '>= 50000000');
    await user.clear(screen.getByLabelText('Row R6, Fee'));
    await user.type(screen.getByLabelText('Row R6, Fee'), '5000');
    await user.dblClick(screen.getByRole('button', { name: 'Save draft' }));

    const cell = await screen.findByLabelText('Row R6, Balance');
    await waitFor(() => { expect(cell).toHaveAttribute('aria-invalid', 'true'); });
    expect(screen.getByText('compares a money with a number')).toBeInTheDocument();
    // Every violation lands at once, output cells included.
    expect(screen.getByLabelText('Row R6, Fee')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText(/output fee is money and needs a currency/)).toBeInTheDocument();
    expect(screen.getByLabelText('Row R1, Tier')).toHaveValue("in ['GOLD', 'PLATINUM']");
    expect(new Set(writes().map((c) => c.key)).size).toBe(1);

    await user.clear(cell);
    await user.type(cell, '>= 50000000 VND');
    await user.clear(screen.getByLabelText('Row R6, Fee'));
    await user.type(screen.getByLabelText('Row R6, Fee'), '5000 VND');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('card-fee Version 3');
    expect(screen.getByText('Saved as draft version 3')).toBeInTheDocument();
    const ids = screen.getAllByRole('rowheader').map((th) => th.textContent);
    expect(ids).toEqual(['R1', 'R2', 'R6', 'R3', 'R4', 'R5']);
    expect(new Set(writes().map((c) => c.path))).toEqual(new Set(['/rule-sets/card-fee/versions']));
    expect(new Set(writes().map((c) => c.key)).size).toBe(2);
  });

  it('CKP_032_request_publish_sends_the_lock_version_and_waits_for_the_engine', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await user.click(await screen.findByRole('button', { name: 'New draft' }));
    await user.clear(screen.getByLabelText('Row R3, Fee'));
    await user.type(screen.getByLabelText('Row R3, Fee'), '35000 VND');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    await user.click(await screen.findByRole('button', { name: 'Request publish' }));
    expect(await screen.findByText('Version 3 is waiting for approval.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve and publish' })).toBeDisabled();
    expect(writes().at(-1)?.path).toBe('/rule-sets/card-fee/versions/3:request-publish');
  });
});

describe('four eyes', () => {
  it('CKP_032_requester_sees_approve_disabled_with_the_reason', async () => {
    open('#/rules/card-fee/2');
    const approve = await screen.findByRole('button', { name: 'Approve and publish' });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAccessibleDescription(/Approve is unavailable\. You requested this change\. Another approver must decide\./);
  });

  it('CKP_032_approver_reads_the_changes_and_approves', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2', 'rules-approver');
    expect(await screen.findByText('Compared with version 1')).toBeInTheDocument();
    expect(screen.getByText('1 added, 2 changed, 0 moved, 0 removed')).toBeInTheDocument();
    expect(screen.getByText(/Aid: compared in this browser/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Record: version 2 as stored by the engine' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled();
    await user.type(screen.getByLabelText('Comment or reason'), 'Matches the tariff');
    await user.click(screen.getByRole('button', { name: 'Approve and publish' }));
    expect(await screen.findByText('Version 2 is published.')).toBeInTheDocument();
  });

  it('RLE_025_approver_rejects_with_a_reason_and_the_history_shows_it', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2', 'rules-approver');
    const reject = await screen.findByRole('button', { name: 'Reject' });
    expect(reject).toBeDisabled();
    expect(reject).toHaveAccessibleDescription('Reject needs a reason; it is kept in the history.');
    await user.type(screen.getByLabelText('Comment or reason'), 'R2 waiver needs pricing committee minutes');
    await user.click(reject);
    expect(await screen.findByText('Version 2 is back with its author.')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Approval' })).getByText('Draft')).toBeInTheDocument();
    await user.click(screen.getByText('History (3)'));
    expect(screen.getByText('Rejected by minh')).toBeInTheDocument();
    expect(screen.getByText('R2 waiver needs pricing committee minutes')).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.path)).toEqual(['/rule-sets/card-fee/versions/2:reject']);
  });

  it('RLE_043_PD_089_the_requester_never_rejects_their_own_request', async () => {
    open('#/rules/card-fee/2', 'rules-author-approver');
    // Lan holds rules:approve here but wrote and requested v2: no Approve, and no Reject either.
    expect(await screen.findByRole('button', { name: 'Approve and publish' })).toBeDisabled();
    expect(screen.getByText(/Approve is unavailable\. You requested this change/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Comment or reason')).not.toBeInTheDocument();
  });

  it('RLE_043_a_disabled_approve_always_says_why', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2', () => HttpResponse.json({ ...pendingV2, allowedActions: ['reject'] })));
    open('#/rules/card-fee/2', 'rules-approver');
    const approve = await screen.findByRole('button', { name: 'Approve and publish' });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAccessibleDescription(/Approve is not available to you for this version\./);
  });

  it('PD_075_cockpit_drafts_off_hides_editing_and_keeps_import', async () => {
    open('#/rules/card-fee/1', 'rules-author-drafts-off');
    expect(await screen.findByText('Changes to this environment arrive through your release pipeline.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New draft' })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('link', { name: /All rule sets/ }));
    expect(await screen.findByRole('button', { name: 'Import file' })).toBeInTheDocument();
  });

  it('TR_UI_02_a_stale_click_gets_the_engine_403_with_code_and_reference', async () => {
    const user = userEvent.setup();
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2\\:approve', () => HttpResponse.json(
      { type: 'x', title: 'Forbidden', status: 403, code: 'SELF_APPROVAL_FORBIDDEN', detail: 'The requester or author of card-fee v2 cannot approve it', correlationId: '01JSTALE0000000000000000000' },
      { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
    )));
    open('#/rules/card-fee/2', 'rules-approver');
    await user.click(await screen.findByRole('button', { name: 'Approve and publish' }));
    expect(await screen.findByText('You cannot approve a change you wrote or requested.')).toBeInTheDocument();
    expect(screen.getByTestId('problem-code')).toHaveTextContent('SELF_APPROVAL_FORBIDDEN');
    expect(screen.getByText('01JSTALE0000000000000000000')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Approval' })).getByText('Waiting for approval')).toBeInTheDocument();
  });

  it('TR_UI_02_a_version_conflict_offers_reload', async () => {
    const user = userEvent.setup();
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2\\:approve', () => HttpResponse.json(
      { type: 'x', title: 'Conflict', status: 409, code: 'VERSION_CONFLICT', detail: 'Expected lock version 1 but card-fee v2 is at 2', correlationId: '01JCONFLICT00000000000000000' },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    )));
    open('#/rules/card-fee/2', 'rules-approver');
    await user.click(await screen.findByRole('button', { name: 'Approve and publish' }));
    expect(await screen.findByText('Someone changed this version. Reload it and try again.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument();
  });
});

describe('import of the next version (CKP-030)', () => {
  const v3 = JSON.stringify({ schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }, { name: 'balance', type: 'money' }],
    outputs: [{ name: 'fee', type: 'money' }], rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }, { id: 'R4', when: { balance: '>= 50000000' }, then: { fee: '1000 VND' } }] });
  const upload = async (content: string, name: string) => {
    const input = document.body.querySelector<HTMLInputElement>('.rs-head input[type=file]');
    if (!input) throw new Error('file input missing');
    await userEvent.setup().upload(input, new File([content], name, { type: 'application/yaml' }));
  };

  it('CKP_030_import_a_file_as_the_next_version_from_the_rule_set_page', async () => {
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    await upload(v3.replace('>= 50000000', '>= 50000000 VND'), 'card-fee-v3.yaml');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('card-fee Version 3');
    expect(screen.getByText('Draft version 3 created')).toBeInTheDocument();
    const posts = calls.filter((c) => c.method === 'POST');
    expect(posts.map((c) => c.path)).toEqual(['/rule-sets/card-fee/versions']);
    expect(posts[0]?.key).toBeTruthy();
    expect(contentTypes).toEqual(['application/yaml']);
  });

  it('CKP_030_a_bad_file_is_located_by_row_and_column_and_creates_nothing', async () => {
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    await upload(v3, 'card-fee-bad.yaml');
    expect(await screen.findByText('Row R4, column balance')).toBeInTheDocument();
    expect(screen.getByText(': compares a money with a number')).toBeInTheDocument();
    expect(screen.queryByText(/Draft version 3 created/)).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('card-fee Version 1');
  });

  it('PD_075_import_stays_available_when_cockpit_drafts_are_off', async () => {
    open('#/rules/card-fee/1', 'rules-author-drafts-off');
    expect(await screen.findByRole('button', { name: 'Import new version' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New draft' })).not.toBeInTheDocument();
  });
});

describe('payload limits (413)', () => {
  const tooLarge = (path: string) => http.post(path, () => HttpResponse.json(
    { type: 'x', title: 'Content Too Large', status: 413, code: 'PAYLOAD_TOO_LARGE', detail: 'The document is larger than 16384 bytes', correlationId: '01JTOOLARGE0000000000000000' },
    { status: 413, headers: { 'Content-Type': 'application/problem+json' } },
  ));

  it('RLE_044_an_import_over_the_limit_is_shown_in_plain_words', async () => {
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    const input = document.body.querySelector<HTMLInputElement>('.rs-head input[type=file]');
    if (!input) throw new Error('file input missing');
    await userEvent.setup().upload(input, new File(['#'.repeat(1_048_577)], 'huge.yaml', { type: 'application/yaml' }));
    expect(await screen.findByText('The text is too long for the service.')).toBeInTheDocument();
    expect(screen.getByTestId('problem-code')).toHaveTextContent('PAYLOAD_TOO_LARGE');
  });

  it('RLE_044_a_save_over_the_limit_keeps_the_edits', async () => {
    const user = userEvent.setup();
    mockHost.use(tooLarge('*/api/bpm/rules/v1/rule-sets/card-fee/versions'));
    open('#/rules/card-fee/1');
    await user.click(await screen.findByRole('button', { name: 'New draft' }));
    await user.clear(screen.getByLabelText('Row R3, Fee'));
    await user.type(screen.getByLabelText('Row R3, Fee'), '35000 VND');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('The text is too long for the service.')).toBeInTheDocument();
    expect(screen.getByLabelText('Row R3, Fee')).toHaveValue('35000 VND');
  });

  it('RLE_044_a_reject_reason_over_the_limit_is_shown_in_plain_words', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2', 'rules-approver');
    await user.click(await screen.findByLabelText('Comment or reason'));
    await user.paste('x'.repeat(16_500));
    await user.click(screen.getByRole('button', { name: 'Reject' }));
    expect(await screen.findByText('The text is too long for the service.')).toBeInTheDocument();
    expect(screen.getByTestId('problem-code')).toHaveTextContent('PAYLOAD_TOO_LARGE');
  });
});

describe('Try it', () => {
  it('CKP_030_try_it_shows_the_matched_row_and_formatted_outputs', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await user.selectOptions(await screen.findByLabelText('Tier'), 'GOLD');
    await user.type(screen.getByLabelText('Balance'), '60000000');
    expect(screen.getByLabelText('Balance')).toHaveValue('60,000,000');
    await user.click(screen.getByRole('button', { name: 'Run' }));
    expect(await screen.findByText('Matched row R2')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Try it' })).getByText('0 VND')).toBeInTheDocument();
    await waitFor(() => { expect(screen.getByText('Matched', { selector: '.match-mark' })).toBeInTheDocument(); });
    expect(screen.getByText('Version in effect: 1')).toBeInTheDocument();
    // The raw decision id is not on screen until Details is opened.
    expect(screen.getByText('Details').closest('details')).not.toHaveAttribute('open');
    const evaluate = calls.find((c) => c.path === '/decisions/card-fee:evaluate');
    expect(evaluate?.key).toBeTruthy();
  });

  it('CKP_030_try_it_needs_the_evaluate_permission', async () => {
    open('#/rules/card-fee/1', 'ready');
    expect(await screen.findByText('You cannot run rules on this service.')).toBeInTheDocument();
  });
});
