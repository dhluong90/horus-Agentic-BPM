import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { mockHost, setScenario } from '../../mocks/node';
import { formatDateTime } from './model/dates';
import { createFakeEngine, demoSeed } from '../../mocks/ruleEngine';

/** Engine calls the screens made, in order: method and path. */
let calls: { method: string; path: string; key: string | null; ifMatch: string | null }[] = [];
let contentTypes: string[] = [];

beforeEach(() => {
  calls = [];
  contentTypes = [];
  mockHost.events.on('request:start', ({ request }) => {
    const path = decodeURIComponent(new URL(request.url).pathname);
    if (path.startsWith('/api/bpm/rules') && request.method === 'POST') contentTypes.push(request.headers.get('Content-Type') ?? '');
    if (path.startsWith('/api/bpm/rules')) calls.push({ method: request.method, path: path.replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key'), ifMatch: request.headers.get('If-Match') });
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

/** The version line under the rule set name, for example "Version 3 · draft". */
const versionLine = async (n: number, words = 'Version') => screen.findByText(new RegExp(`^${words} ${String(n)} ·`), { selector: '.rs-head__sub' });

async function openMore(user: ReturnType<typeof userEvent.setup>, item: string) {
  await user.click(await screen.findByRole('button', { name: 'More' }));
  await user.click(screen.getByRole('menuitem', { name: item }));
}

async function rowAction(user: ReturnType<typeof userEvent.setup>, row: string, item: string) {
  await user.click(screen.getByRole('button', { name: `Actions for row ${row}` }));
  await user.click(screen.getByRole('menuitem', { name: item }));
}

const writes = () => calls.filter((c) => c.method === 'POST' && !c.path.endsWith(':validate'));

describe('rule set list and version view', () => {
  it('CKP_030_list_shows_each_rule_set_with_latest_status_and_published_version', async () => {
    open('#/rules');
    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row').slice(1);
    await waitFor(() => { expect(within(rows[0] as HTMLElement).getByText('Waiting for approval')).toBeInTheDocument(); });
    expect(rows[0]).toHaveTextContent('Card fee');
    expect(rows[1]).toHaveTextContent('Kyc risk');
    expect(screen.getByRole('button', { name: 'Import rule set' })).toBeInTheDocument();
  });

  it('RLE_039_a_list_cut_short_by_the_page_guard_says_so', async () => {
    let n = 0;
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets', () => HttpResponse.json({
      data: [{ ruleSet: `rs-${String((n += 1))}`, displayName: `Rs ${String(n)}`, latestVersion: 1, latestStatus: 'DRAFT', publishedVersion: null, lastChangedAt: '2026-11-18T09:00:00Z' }],
      nextCursor: 'more', hasNext: true,
    })));
    open('#/rules');
    expect(await screen.findByText(/^Showing the first \d+ rule sets\. Use Find to reach the others\.$/)).toBeInTheDocument();
  });

  it('TR_STD_01_a_json_file_is_sent_as_yaml_text_until_the_engine_imports_json', async () => {
    const user = userEvent.setup();
    open('#/rules');
    await screen.findByRole('table');
    const input = document.body.querySelector<HTMLInputElement>('input[type=file]');
    if (!input) throw new Error('file input missing');
    const document_ = { schemaVersion: 1, ruleSet: 'fx-margin', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }],
      rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }] };
    await user.upload(input, new File([JSON.stringify(document_)], 'fx-margin.json', { type: 'application/json' }));
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Fx margin');
    expect(await versionLine(1)).toBeInTheDocument();
    expect(contentTypes).toEqual(['application/yaml']);
  });

  it('CKP_030_reader_without_author_permission_sees_no_import', async () => {
    open('#/rules', 'ready-approver');
    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: 'Import rule set' })).not.toBeInTheDocument();
  });

  it('CKP_030_version_reads_as_a_table_and_the_source_only_on_purpose', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Card fee');
    expect(await versionLine(1)).toHaveTextContent('Version 1 · in effect · first matching row wins');
    expect(screen.getByText('50,000,000 VND')).toBeInTheDocument();
    expect(screen.getAllByText('Gold').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Any value').length).toBeGreaterThan(0);
    expect(screen.queryByText(/schemaVersion/)).not.toBeInTheDocument();
    expect(screen.queryByText("== 'GOLD'")).not.toBeInTheDocument();
    await openMore(user, 'Source');
    expect(await screen.findByText(/"schemaVersion": 1/)).toBeInTheDocument();
    expect(calls.at(-1)?.path).toBe('/rule-sets/card-fee/versions/1');
  });

  it('TR_UI_04_vietnamese_screen_is_one_language_with_vietnamese_amounts', async () => {
    open('#/rules/card-fee/1', 'rules-author', 'vi');
    expect(await versionLine(1, 'Phiên bản')).toBeInTheDocument();
    expect(screen.getByText('50.000.000 VND')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Bắt đầu từ phiên bản này' })).toBeInTheDocument();
    expect(screen.queryByText('Start from this version')).not.toBeInTheDocument();
  });
});

describe('minimal draft editing', () => {
  it('CKP_032_minimal_draft_editing_shows_errors_on_cells', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await user.click(await screen.findByRole('button', { name: 'Start from this version' }));
    // The draft is stored first, as a copy of version 1; the grid edits that draft, never version 1.
    expect(await screen.findByText('Editing version 3', { selector: '.rs-head__sub' })).toBeInTheDocument();
    expect(writes().map((c) => c.path)).toEqual(['/rule-sets/card-fee/versions']);
    // Cells edit as values: a tier picker, a comparison and an amount with its currency, Yes or No.
    await user.click(screen.getByRole('button', { name: 'Row R1, Tier: Platinum' }));
    await user.click(screen.getByRole('checkbox', { name: 'Gold' }));
    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: 'Row R1, Tier: Gold, Platinum' })).toBeInTheDocument();
    await rowAction(user, 'R2', 'Duplicate row R2');
    await rowAction(user, 'R6', 'Move row R6 down');
    await rowAction(user, 'R6', 'Move row R6 up');
    expect(screen.getByText('Changes in this draft')).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Breadcrumb' })).getByText('Version 3 draft')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'View changes' })).toBeInTheDocument();
    // An operator with no amount: the engine's check marks the cell in plain words, and Save says why it waits.
    await user.clear(screen.getByLabelText('Row R6, Balance', { exact: true }));
    await user.keyboard('{Enter}');
    const cell = await screen.findByLabelText('Row R6, Balance', { exact: true });
    await waitFor(() => { expect(cell).toHaveAttribute('aria-invalid', 'true'); });
    expect(cell).toHaveAccessibleDescription(/The cell is not a valid expression\./);
    expect(screen.getByText('1 to fix')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Save draft' })).toHaveAccessibleDescription('Fix the marked cells to save.');
    expect(screen.getByRole('button', { name: 'Row R1, Tier: Gold, Platinum' })).toBeInTheDocument();

    await user.type(cell, '20000000');
    expect(cell).toHaveValue('20,000,000');
    await user.keyboard('{Enter}');
    await waitFor(() => { expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled(); });
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('Draft version 3 saved')).toBeInTheDocument();
    expect(await versionLine(3)).toHaveTextContent('Version 3 · draft');
    const ids = screen.getAllByRole('rowheader').map((th) => th.textContent);
    expect(ids).toEqual(['R1', 'R2', 'R6', 'R3', 'R4', 'R5']);
    expect(screen.getByText('20,000,000 VND')).toBeInTheDocument();
    // The save replaced draft 3 in place, with the lock version the author loaded.
    const saves = calls.filter((c) => c.method === 'PUT');
    expect(saves.map((c) => [c.path, c.ifMatch])).toEqual([['/rule-sets/card-fee/versions/3', '"0"']]);
    expect(saves[0]?.key).toBeTruthy();
    // The checks stored nothing: no Idempotency-Key, no If-Match.
    const checks = calls.filter((c) => c.path.endsWith(':validate'));
    expect(checks.length).toBeGreaterThan(0);
    expect(checks.every((c) => c.key === null && c.ifMatch === null)).toBe(true);
  });

  it('CKP_032_request_publish_sends_the_lock_version_and_waits_for_the_engine', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await user.click(await screen.findByRole('button', { name: 'Start from this version' }));
    await user.clear(await screen.findByLabelText('Row R3, Fee'));
    await user.type(screen.getByLabelText('Row R3, Fee'), '35000');
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

  it('CKP_511_a_disabled_approve_says_why_in_plain_text_and_no_amber_paragraph', async () => {
    open('#/rules/card-fee/2');
    const approve = await screen.findByRole('button', { name: 'Approve and publish' });
    // #609: the self-approval reason is visible text beside the button, not only a tooltip.
    expect(await screen.findByText('You requested this. Another approver decides.')).toBeVisible();
    expect(approve.parentElement).not.toHaveAttribute('data-tip');
    expect(approve.closest('.decision')?.querySelector('.gate__reason')).toBeNull();
    // The dock is the sticky one of the changes view: the decision card stays on screen while the diff scrolls.
    expect(approve.closest('.decision')).toHaveClass('decision--dock');
  });

  it('CKP_002_approver_sees_both_versions_side_by_side_before_the_approval_action', async () => {
    open('#/rules/card-fee/2', 'rules-approver');
    const table = await screen.findByRole('table', { name: 'Version 1 and version 2 of card-fee, side by side' });
    expect(screen.getByText('1 added, 1 changed, 1 removed, 3 unchanged')).toBeInTheDocument();
    // The engine compares: one diff request against the version in effect, and no other version is changed.
    expect(calls.filter((c) => c.path.includes('/diff'))).toEqual([{ method: 'GET', path: '/rule-sets/card-fee/versions/2/diff', key: null, ifMatch: null }]);
    const marks = within(table).getAllByRole('rowheader').map((th) => th.textContent.replace(/[＋✎⇅−]/g, '').replace(/\s+/g, ' ').trim());
    expect(marks).toEqual(['R1', 'R2', 'R6 Added', 'R3 Changed', 'R4 Removed', 'R5']);
    expect(within(table).getByText('35,000 VND').closest('td')).toHaveClass('diff-td--mod');
    // The diff comes before the decision in reading order, and the decision is the only other panel.
    const approve = screen.getByRole('button', { name: 'Approve and publish' });
    expect(table.compareDocumentPosition(approve) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByRole('region', { name: 'Inspector' })).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('CKP_002_a_proposed_effective_date_is_named_beside_approve', async () => {
    const effectiveFrom = '2027-01-01T00:00:00Z';
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2', () => HttpResponse.json({ ...pendingV2, effectiveFrom, allowedActions: ['approve', 'reject'] })));
    open('#/rules/card-fee/2/table', 'rules-approver');
    expect(await screen.findByText(`Takes effect ${formatDateTime(effectiveFrom, 'en') ?? ''} once approved.`)).toBeInTheDocument();
  });

  it('CKP_002_approve_is_off_when_the_diff_on_screen_is_of_other_content', async () => {
    // The engine's diff of card-fee v2, but computed over content other than the version the cockpit holds.
    const other = createFakeEngine(demoSeed());
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2/diff', ({ request }) => {
      const answer = other.handle({ method: 'GET', path: request.url, headers: {}, body: '', user: 'minh', permissions: ['rules:read'] });
      return HttpResponse.json({ ...(JSON.parse(answer.body) as Record<string, unknown>), canonicalHash: 'f'.repeat(64) });
    }));
    open('#/rules/card-fee/2', 'rules-approver');
    await screen.findByRole('table', { name: 'Version 1 and version 2 of card-fee, side by side' });
    const approve = screen.getByRole('button', { name: 'Approve and publish' });
    await waitFor(() => { expect(approve).toHaveAccessibleDescription('Version 2 changed while you had it open. Check the table again.'); });
    expect(approve).toBeDisabled();
    expect(writes()).toEqual([]);
  });

  it('CKP_002_a_refused_diff_is_shown_with_its_code_and_no_table', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2/diff', () => HttpResponse.json(
      { type: 'x', title: 'Not Found', status: 404, code: 'VERSION_NOT_FOUND', detail: 'Version 1 of rule set card-fee not found', correlationId: '01JDIFF00000000000000000000' },
      { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
    )));
    open('#/rules/card-fee/2', 'rules-approver');
    expect(await screen.findByTestId('problem-code')).toHaveTextContent('VERSION_NOT_FOUND');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('CKP_002_the_first_version_has_nothing_to_compare_and_calls_no_diff', async () => {
    open('#/rules/kyc-risk/1/changes', 'rules-approver');
    expect(await screen.findByText('First version: every row is new.')).toBeInTheDocument();
    expect(calls.some((c) => c.path.includes('/diff'))).toBe(false);
  });

  it('CKP_032_approver_reads_the_changes_and_approves', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2', 'rules-approver');
    expect(await screen.findByText('1 added, 1 changed, 1 removed, 3 unchanged')).toBeInTheDocument();
    expect(screen.queryByText(/look-alike/i)).not.toBeInTheDocument();
    // One hint line at most, and only for a scheduled date: v2 has none, and the reason hint is spoken, not shown.
    expect(screen.queryByText(/Takes effect/)).not.toBeInTheDocument();
    expect(screen.getByText('Reject needs a reason; it is kept in the history.')).toHaveClass('sr-only');
    // One table: both versions side by side; each side's header carries its content hash.
    expect(screen.getAllByRole('table')).toHaveLength(1);
    const hashes = [...document.querySelectorAll('.sbs__hash')].map((el) => el.textContent);
    expect(hashes).toHaveLength(2);
    hashes.forEach((text) => { expect(text).toMatch(/^Content hash [0-9a-f]{12}$/); });
    expect(new Set(hashes).size).toBe(2);
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled();
    await user.type(screen.getByLabelText('Comment'), 'Matches the tariff');
    await user.click(screen.getByRole('button', { name: 'Approve and publish' }));
    expect(await screen.findByText('Version 2 is published.')).toBeInTheDocument();
  });

  it('RLE_084_approve_and_reject_send_the_hash_of_the_content_the_reviewer_opened_without_reading_it_again', async () => {
    const user = userEvent.setup();
    const opened = (JSON.parse(createFakeEngine(demoSeed()).handle({ method: 'GET', path: '/api/bpm/rules/v1/rule-sets/card-fee/versions/2', headers: {}, body: '', user: 'minh', permissions: ['rules:read'] }).body) as { canonicalHash: string }).canonicalHash;
    const bodies: Record<string, unknown>[] = [];
    mockHost.events.on('request:start', ({ request }) => {
      if (request.method === 'POST' && /:(approve|reject)$/.test(new URL(request.url).pathname)) void request.clone().text().then((text) => { bodies.push(JSON.parse(text) as Record<string, unknown>); });
    });
    open('#/rules/card-fee/2', 'rules-approver');
    await screen.findByText('1 added, 1 changed, 1 removed, 3 unchanged');
    const approve = screen.getByRole('button', { name: 'Approve and publish' });
    await waitFor(() => { expect(approve).toBeEnabled(); });
    const before = calls.length;
    await user.click(approve);
    expect(await screen.findByText('Version 2 is published.')).toBeInTheDocument();
    // The first call after the click is the approval itself: no read of the version stands between review and decision.
    expect(calls[before]).toMatchObject({ method: 'POST', path: '/rule-sets/card-fee/versions/2:approve' });
    await waitFor(() => { expect(bodies).toHaveLength(1); });
    expect(bodies[0]).toMatchObject({ expectedCanonicalHash: opened });
  });

  it('RLE_084_a_stale_hash_is_answered_CONTENT_CHANGED_and_the_reviewer_is_told_to_review_again', async () => {
    const user = userEvent.setup();
    const engine = createFakeEngine(demoSeed());
    // The content the reviewer opened is not what is stored when the decision arrives.
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2\\:approve', ({ request }) => {
      const answer = engine.handle({ method: 'POST', path: request.url, headers: { 'if-match': '"1"' }, body: JSON.stringify({ expectedCanonicalHash: 'e'.repeat(64) }), user: 'minh', permissions: ['rules:read', 'rules:approve'] });
      return new HttpResponse(answer.body, { status: answer.status, headers: answer.headers });
    }));
    open('#/rules/card-fee/2', 'rules-approver');
    await screen.findByText('1 added, 1 changed, 1 removed, 3 unchanged');
    await waitFor(() => { expect(screen.getByRole('button', { name: 'Approve and publish' })).toBeEnabled(); });
    await user.click(screen.getByRole('button', { name: 'Approve and publish' }));
    expect(await screen.findByText('The rules changed after you opened this version. Review it again before you decide.')).toBeInTheDocument();
    // Approve waits for the reviewer to show the current changes; nothing can be sent with the stale hash.
    expect(screen.getByRole('button', { name: 'Approve and publish' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Show the current changes' })).toBeInTheDocument();
  });

  it('RLE_025_approver_rejects_with_a_reason_and_the_history_shows_it', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2', 'rules-approver');
    const reject = await screen.findByRole('button', { name: 'Reject' });
    expect(reject).toBeDisabled();
    expect(reject).toHaveAccessibleDescription('Reject needs a reason; it is kept in the history.');
    await user.type(screen.getByLabelText('Comment'), 'R2 waiver needs pricing committee minutes');
    await user.click(reject);
    expect(await screen.findByText('Version 2 is back with its author.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Draft');
    await user.click(screen.getByRole('tab', { name: 'History' }));
    const events = await screen.findByRole('list', { name: 'Events of version 2' });
    expect(within(events).getAllByRole('listitem')).toHaveLength(3);
    expect(within(events).getByText('Rejected by Trần Văn Minh')).toBeInTheDocument();
    expect(within(events).getByText('R2 waiver needs pricing committee minutes')).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST').map((c) => c.path)).toEqual(['/rule-sets/card-fee/versions/2:reject']);
  });

  it('RLE_043_PD_089_the_requester_never_rejects_their_own_request', async () => {
    open('#/rules/card-fee/2', 'rules-author-approver');
    // Lan holds rules:approve here but wrote and requested v2: no Approve, and no Reject either.
    expect(await screen.findByRole('button', { name: 'Approve and publish' })).toBeDisabled();
    expect(screen.getByText(/Approve is unavailable\. You requested this change/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Comment')).not.toBeInTheDocument();
  });

  it('RLE_043_a_disabled_approve_always_says_why', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/2', () => HttpResponse.json({ ...pendingV2, allowedActions: ['reject'] })));
    open('#/rules/card-fee/2', 'rules-approver');
    const approve = await screen.findByRole('button', { name: 'Approve and publish' });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAccessibleDescription(/Approve is not available to you for this version\./);
  });

  it('PD_094_without_the_setting_draft_editing_is_off_and_import_stays', async () => {
    open('#/rules/card-fee/1', 'rules-author-drafts-default');
    expect(await screen.findByText('Changes to this environment arrive through your release pipeline.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start from this version' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Import new version' })).toBeEnabled();
    window.location.hash = '#/rules';
    expect(await screen.findByRole('button', { name: 'Import rule set' })).toBeInTheDocument();
  });

  it('PD_094_a_host_that_turns_drafts_on_offers_editing', async () => {
    open('#/rules/card-fee/1', 'rules-author');
    expect(await screen.findByRole('button', { name: 'Start from this version' })).toBeInTheDocument();
    expect(screen.queryByText('Changes to this environment arrive through your release pipeline.')).not.toBeInTheDocument();
  });

  it('PD_075_cockpit_drafts_off_hides_editing_and_keeps_import', async () => {
    open('#/rules/card-fee/1', 'rules-author-drafts-off');
    expect(await screen.findByText('Changes to this environment arrive through your release pipeline.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Start from this version' })).not.toBeInTheDocument();
    window.location.hash = '#/rules';
    expect(await screen.findByRole('button', { name: 'Import rule set' })).toBeInTheDocument();
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
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Waiting for approval');
  });

  // After 409 VERSION_CONFLICT the cockpit reads the version again, says it changed, and never sends the decision again on its own.
  it.each([['approve', 'Approve and publish'], ['reject', 'Reject']])('CKP_002_a_version_conflict_on_%s_reloads_the_version_and_never_resubmits', async (action, button) => {
    const user = userEvent.setup();
    let sent = 0;
    mockHost.use(http.post(`*/api/bpm/rules/v1/rule-sets/card-fee/versions/2\\:${action}`, () => {
      sent += 1;
      return HttpResponse.json(
        { type: 'x', title: 'Conflict', status: 409, code: 'VERSION_CONFLICT', detail: 'Expected lock version 1 but card-fee v2 is at 2', correlationId: '01JCONFLICT00000000000000000' },
        { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }));
    open('#/rules/card-fee/2', 'rules-approver');
    await waitFor(() => { expect(screen.getByRole('button', { name: 'Approve and publish' })).toBeEnabled(); });
    if (action === 'reject') await user.type(screen.getByLabelText('Comment'), 'Needs another look');
    const versionReads = () => calls.filter((c) => c.method === 'GET' && c.path === '/rule-sets/card-fee/versions/2').length;
    const before = versionReads();
    await user.click(screen.getByRole('button', { name: button }));
    expect(await screen.findByText('Version 2 changed while you had it open. Check the table again.')).toBeInTheDocument();
    await waitFor(() => { expect(versionReads()).toBeGreaterThan(before); });
    expect(screen.getByRole('button', { name: 'Approve and publish' })).toBeDisabled();
    expect(sent).toBe(1);
    expect(screen.queryByText('Someone changed this version. Reload it and try again.')).not.toBeInTheDocument();
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
    await screen.findByRole('button', { name: 'More' });
    await upload(v3.replace('>= 50000000', '>= 50000000 VND'), 'card-fee-v3.yaml');
    expect(await versionLine(3)).toBeInTheDocument();
    expect(screen.getByText('Draft version 3 created')).toBeInTheDocument();
    const posts = calls.filter((c) => c.method === 'POST');
    expect(posts.map((c) => c.path)).toEqual(['/rule-sets/card-fee/versions']);
    expect(posts[0]?.key).toBeTruthy();
    expect(contentTypes).toEqual(['application/yaml']);
  });

  it('CKP_030_a_bad_file_is_located_by_row_and_column_and_creates_nothing', async () => {
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'More' });
    await upload(v3, 'card-fee-bad.yaml');
    expect(await screen.findByText('Import refused. Nothing was saved.')).toBeInTheDocument();
    expect(screen.getByText('card-fee-bad.yaml, row R4, column Balance')).toBeInTheDocument();
    expect(screen.getByText('compares a money amount with a plain number')).toBeInTheDocument();
    expect(screen.queryByText(/Draft version 3 created/)).not.toBeInTheDocument();
    expect(await versionLine(1)).toBeInTheDocument();
  });

  it('CKP_030_a_failed_import_clears_the_previous_success_notice', async () => {
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'More' });
    await upload(v3.replace('>= 50000000', '>= 50000000 VND'), 'card-fee-v3.yaml');
    expect(await screen.findByText('Draft version 3 created')).toBeInTheDocument();
    await upload(v3, 'card-fee-bad.yaml');
    expect(await screen.findByText('card-fee-bad.yaml, row R4, column Balance')).toBeInTheDocument();
    expect(screen.queryByText('Draft version 3 created')).not.toBeInTheDocument();
  });

  it('PD_075_import_stays_available_when_cockpit_drafts_are_off', async () => {
    open('#/rules/card-fee/1', 'rules-author-drafts-off');
    expect(await screen.findByRole('button', { name: 'Import new version' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Start from this version' })).not.toBeInTheDocument();
  });
});

describe('payload limits (413)', () => {
  it('RLE_044_an_import_over_the_limit_is_shown_in_plain_words', async () => {
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'More' });
    const input = document.body.querySelector<HTMLInputElement>('.rs-head input[type=file]');
    if (!input) throw new Error('file input missing');
    await userEvent.setup().upload(input, new File(['#'.repeat(1_048_577)], 'huge.yaml', { type: 'application/yaml' }));
    expect(await screen.findByText('Import refused. Nothing was saved.')).toBeInTheDocument();
    expect(screen.getByText('huge.yaml').parentElement).toHaveTextContent('huge.yaml: The text is too long for the service.');
    expect(screen.getByTestId('problem-code')).toHaveTextContent('PAYLOAD_TOO_LARGE');
  });

  it('RLE_044_a_save_over_the_limit_keeps_the_edits', async () => {
    const user = userEvent.setup();
    mockHost.use(http.put('*/api/bpm/rules/v1/rule-sets/card-fee/versions/3', () => HttpResponse.json(
      { type: 'x', title: 'Content Too Large', status: 413, code: 'PAYLOAD_TOO_LARGE', detail: 'The document is larger than 1048576 bytes', correlationId: '01JTOOLARGE0000000000000000' },
      { status: 413, headers: { 'Content-Type': 'application/problem+json' } },
    )));
    open('#/rules/card-fee/1');
    await user.click(await screen.findByRole('button', { name: 'Start from this version' }));
    await user.clear(await screen.findByLabelText('Row R3, Fee'));
    await user.type(screen.getByLabelText('Row R3, Fee'), '35000');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('The text is too long for the service.')).toBeInTheDocument();
    expect(screen.getByLabelText('Row R3, Fee')).toHaveValue('35,000');
  });

  it('RLE_044_a_reject_reason_over_the_limit_is_shown_in_plain_words', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2', 'rules-approver');
    await user.click(await screen.findByLabelText('Comment'));
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
    await user.click(await screen.findByRole('tab', { name: 'Try it' }));
    await user.selectOptions(await screen.findByLabelText('Tier'), 'GOLD');
    await user.type(screen.getByLabelText('Balance'), '60000000');
    expect(screen.getByLabelText('Balance')).toHaveValue('60,000,000');
    await user.click(screen.getByRole('button', { name: 'Run' }));
    expect(await screen.findByText('Row R2 matched')).toBeInTheDocument();
    expect(within(screen.getByRole('tabpanel', { name: 'Try it' })).getByText('0 VND')).toBeInTheDocument();
    await waitFor(() => { expect(screen.getByText('Matched', { selector: '.match-mark' })).toBeInTheDocument(); });
    expect(screen.getByText('Version in effect: 1')).toBeInTheDocument();
    // The raw decision id is not on screen until Details is opened.
    expect(document.body.querySelector('.try__details')).not.toHaveAttribute('open');
    const evaluate = calls.find((c) => c.path === '/decisions/card-fee:evaluate');
    expect(evaluate?.key).toBeTruthy();
  });

  it('CKP_030_try_it_needs_the_evaluate_permission', async () => {
    open('#/rules/card-fee/1', 'ready');
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Try it' }));
    expect(await screen.findByText('You cannot run rules on this service.')).toBeInTheDocument();
  });
});
