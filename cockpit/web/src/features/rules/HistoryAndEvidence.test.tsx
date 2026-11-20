import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { auditSeed } from '../../mocks/ruleEngine';
import { formatDateTime } from './model/dates';

interface Call { method: string; path: string; key: string | null; body: string }
let calls: Call[] = [];
let downloads: { name: string; file: Blob }[] = [];

beforeEach(() => {
  // The month pickers start at this month; only Date is faked, timers run as usual.
  vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-11-21T03:00:00Z') });
  calls = [];
  downloads = [];
  resetRuleEngine(auditSeed());
  mockHost.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/api/bpm/rules')) return;
    const entry: Call = { method: request.method, path: decodeURIComponent(url.pathname).replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key'), body: '' };
    calls.push(entry);
    if (request.method === 'POST') void request.clone().text().then((text) => { entry.body = text; });
  });
  const blobs = new Map<string, Blob>();
  let n = 0;
  vi.stubGlobal('URL', Object.assign(URL, {
    createObjectURL: (file: Blob) => { const url = `blob:test/${String((n += 1))}`; blobs.set(url, file); return url; },
    revokeObjectURL: () => undefined,
  }));
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    const file = blobs.get(this.href);
    if (file) downloads.push({ name: this.download, file });
  });
  return () => { mockHost.events.removeAllListeners(); vi.restoreAllMocks(); vi.useRealTimers(); };
});

function open(hash: string, scenario: Scenario = 'rules-auditor') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App />);
}

const exportCalls = () => calls.filter((call) => call.method === 'POST' && call.path.endsWith('/evidence'));
const exportButton = () => screen.getByRole('button', { name: 'Export evidence' });
const when = (iso: string) => formatDateTime(iso, 'en') ?? '';

async function chooseNamedDecisions(user: ReturnType<typeof userEvent.setup>) {
  fireEvent.change(screen.getByLabelText('Month of the decisions'), { target: { value: '2026-11' } });
  const list = await screen.findByRole('list', { name: '124 decisions found' });
  for (const short of ['01KF2Q…P7T9', '01KF2Q…P7TA', '01KF2Q…P7TB']) {
    const item = within(list).getByText(short).closest('label');
    await user.click(within(item as HTMLElement).getByRole('checkbox'));
  }
}

describe('rule set history', () => {
  it('CKP_073_history_lists_every_version_newest_first_with_people_and_events', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/3/history');
    expect(await screen.findByRole('heading', { name: 'Versions (3)' })).toBeInTheDocument();
    expect(calls.some((call) => call.method === 'GET' && call.path === '/rule-sets/card-fee/history')).toBe(true);
    const toggles = Array.from(document.querySelectorAll<HTMLButtonElement>('button.rsh__toggle'));
    expect(toggles.map((toggle) => toggle.textContent)).toEqual([
      expect.stringMatching(/^▾Version 3✎ DraftNot in effect/),
      expect.stringMatching(new RegExp(`^▸Version 2✓ PublishedIn effect from ${when('2026-11-19T03:00:00Z')}`)),
      expect.stringMatching(new RegExp(`^▸Version 1■ RetiredIn effect ${when('2026-11-01T03:00:00Z')} to ${when('2026-11-19T03:00:00Z')}`)),
    ]);
    // The version on screen starts open; the others open on demand.
    expect(toggles[0]).toHaveAttribute('aria-expanded', 'true');
    expect(toggles[1]).toHaveAttribute('aria-expanded', 'false');
    const v2 = toggles[1]?.closest('li') as HTMLElement;
    expect(within(v2).getByText('Rejected by').nextSibling).toHaveTextContent(`minh · ${when('2026-11-16T03:00:00Z')}`);
    expect(within(v2).getByText('Approved by').nextSibling).toHaveTextContent('minh');
    expect(within(v2).getByText('Rejection reason').nextSibling).toHaveTextContent('R2 waiver needs pricing committee minutes');
    expect(within(v2).getByText('Approval comment').nextSibling).toHaveTextContent('Committee minutes attached');
    await user.click(toggles[1] as HTMLElement);
    const events = screen.getByRole('list', { name: 'Events of version 2' });
    expect(within(events).getAllByRole('listitem').map((item) => item.querySelector('.history__what')?.textContent)).toEqual([
      '✎ Created by lan', '◷ Sent for approval by lan', '✕ Rejected by minh', '◷ Sent for approval by lan', '✓ Approved by minh',
    ]);
    await user.click(toggles[2] as HTMLElement);
    const v1Events = screen.getByRole('list', { name: 'Events of version 1' });
    expect(within(v1Events).getAllByRole('listitem').at(-1)?.querySelector('.history__what')).toHaveTextContent('■ Retired by minh');
    expect(screen.getByText('No evidence has been exported yet.')).toBeInTheDocument();
    // History and Export evidence side by side.
    expect(screen.getByRole('heading', { name: 'Export evidence' })).toBeInTheDocument();
  });
});

describe('evidence export', () => {
  it('CKP_074_export_sends_one_request_saves_the_bytes_and_shows_the_sha256', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/history');
    await screen.findByRole('heading', { name: 'Versions (3)' });
    await user.click(screen.getByRole('checkbox', { name: 'Version 1 · Retired' }));
    await user.click(screen.getByRole('checkbox', { name: 'Version 2 · Published' }));
    await chooseNamedDecisions(user);
    expect(screen.getByText('3 chosen')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CSV' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(exportButton());

    expect(await screen.findByRole('heading', { name: /Evidence exported/ })).toBeInTheDocument();
    expect(exportCalls()).toHaveLength(1);
    const sent = exportCalls()[0];
    expect(sent?.key).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => { expect(sent?.body).not.toBe(''); });
    expect(JSON.parse(sent?.body ?? '{}')).toEqual({ format: 'csv', versions: [1, 2], decisionIds: ['01KF2Q8Z0RQ6V2Y8M1C4N5P7T9', '01KF2Q8Z0RQ6V2Y8M1C4N5P7TA', '01KF2Q8Z0RQ6V2Y8M1C4N5P7TB'] });

    // The saved file is the engine's bytes under the engine's name; it states the SHA-256 the screen shows.
    expect(downloads).toHaveLength(1);
    const file = await downloads[0]?.file.text() ?? '';
    expect(downloads[0]?.name).toMatch(/^card-fee-evidence-\d{8}T\d{6}Z\.csv$/);
    const sha = /# Content SHA-256: ([0-9a-f]{64})/.exec(file)?.[1] ?? '';
    expect(sha).toMatch(/^[0-9a-f]{64}$/);
    expect(file).toContain('# Exported by: ha');
    // Masked inputs stay their keyed hash in the file.
    expect(file).toContain('hmac:k1:9f4a6c');
    expect(screen.getByTestId('evidence-sha')).toHaveTextContent(`${sha.slice(0, 8)}…`);
    expect(screen.getByText('Versions 1, 2 · 3 decisions')).toBeInTheDocument();
    await user.click(within(screen.getByRole('region', { name: 'Evidence exported' })).getByRole('button', { name: 'Copy SHA-256' }));
    // user-event provides the clipboard; the full 64 digits are copied, not the short reference.
    expect(await navigator.clipboard.readText()).toBe(sha);
    expect(await screen.findByText('SHA-256 copied')).toBeInTheDocument();

    // The history now records the export.
    expect(await screen.findByText('Evidence exported by Phạm Thu Hà: 2 versions, 3 decisions')).toBeInTheDocument();
  });

  it('CKP_074_a_second_click_while_the_export_runs_sends_nothing_more', async () => {
    const user = userEvent.setup();
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/card-fee/evidence', async () => {
      await gate;
      return new HttpResponse('# x\r\n', { status: 200, headers: { 'Content-Type': 'text/csv', 'X-Evidence-Sha256': 'a'.repeat(64), 'Content-Disposition': 'attachment; filename="card-fee-evidence.csv"' } });
    }));
    open('#/rules/card-fee/2/history');
    await screen.findByRole('heading', { name: 'Versions (3)' });
    await user.click(screen.getByRole('checkbox', { name: 'Version 2 · Published' }));
    await user.click(exportButton());
    expect(await screen.findByRole('button', { name: 'Exporting…' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Exporting…' }));
    release();
    expect(await screen.findByRole('heading', { name: /Evidence exported/ })).toBeInTheDocument();
    expect(exportCalls()).toHaveLength(1);
    expect(screen.getByTestId('evidence-sha')).toHaveTextContent('aaaaaaaa…');
  });

  it('CKP_075_export_limits_and_refusals_read_in_words', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/history');
    await screen.findByRole('heading', { name: 'Versions (3)' });
    // Nothing chosen: unavailable, with the reason in one line.
    expect(exportButton()).toBeDisabled();
    expect(exportButton()).toHaveAccessibleDescription('Choose at least one version or decision.');
    // More than 100 decisions: unavailable, and nothing is sent.
    fireEvent.change(screen.getByLabelText('Month of the decisions'), { target: { value: '2026-11' } });
    await screen.findByRole('list', { name: '124 decisions found' });
    await user.click(screen.getByRole('button', { name: 'Choose all 124' }));
    expect(screen.getByText('124 chosen')).toBeInTheDocument();
    expect(exportButton()).toBeDisabled();
    expect(exportButton()).toHaveAccessibleDescription('Choose at most 100 decisions.');
    await user.click(screen.getByRole('button', { name: 'Clear' }));
    expect(exportCalls()).toEqual([]);
  });

  it('CKP_075_without_the_decision_log_only_versions_are_exported', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/2/history', 'rules-auditor-no-log');
    await screen.findByRole('heading', { name: 'Versions (3)' });
    expect(screen.getByText('Adding decisions needs permission to read the decision log.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Month of the decisions')).not.toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Version 1 · Retired' }));
    await user.click(exportButton());
    expect(await screen.findByRole('heading', { name: /Evidence exported/ })).toBeInTheDocument();
    await waitFor(() => { expect(exportCalls()[0]?.body).not.toBe(''); });
    expect(JSON.parse(exportCalls()[0]?.body ?? '{}')).toEqual({ format: 'csv', versions: [1] });
    expect(calls.some((call) => call.path.startsWith('/decisions'))).toBe(false);
  });

  it('CKP_075_a_file_over_the_limit_reads_choose_fewer_decisions', async () => {
    const user = userEvent.setup();
    resetRuleEngine(auditSeed(), {}, { evidenceLimit: 200 });
    open('#/rules/card-fee/2/history');
    await screen.findByRole('heading', { name: 'Versions (3)' });
    await user.click(screen.getByRole('checkbox', { name: 'Version 2 · Published' }));
    await user.click(screen.getByRole('button', { name: 'JSON' }));
    await user.click(exportButton());
    expect(await screen.findByText('The file would be larger than 8 MB. Choose fewer decisions.')).toBeInTheDocument();
    // The choices stay, and the code sits behind Details.
    expect(screen.getByRole('checkbox', { name: 'Version 2 · Published' })).toBeChecked();
    await user.click(screen.getByText('Details'));
    expect(screen.getByText('EVIDENCE_TOO_LARGE')).toBeInTheDocument();
    expect(downloads).toEqual([]);
  });

  it('CKP_075_forbidden_and_a_decision_of_another_rule_set_read_in_words', async () => {
    const user = userEvent.setup();
    const problem = (status: number, code: string, detail: string) => HttpResponse.json({ type: 'about:blank', title: 'Refused', status, code, detail, correlationId: '01JFAKE0000000000000000001' }, { status, headers: { 'Content-Type': 'application/problem+json' } });
    let answer = problem(403, 'PERMISSION_DENIED', 'The caller lacks permission rules:log:read');
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/card-fee/evidence', () => answer));
    open('#/rules/card-fee/2/history');
    await screen.findByRole('heading', { name: 'Versions (3)' });
    await chooseNamedDecisions(user);
    await user.click(exportButton());
    expect(await screen.findByText('You may not export this evidence on this service.')).toBeInTheDocument();
    answer = problem(400, 'INPUT_INVALID', 'Decision 01KF2Q8Z0RQ6V2Y8M1C4N5P7TA belongs to rule set kyc-risk, not card-fee');
    await user.click(screen.getByRole('checkbox', { name: 'Version 1 · Retired' }));
    await user.click(exportButton());
    expect(await screen.findByText('Decision 01KF2Q…P7TA belongs to another rule set and cannot be exported here.')).toBeInTheDocument();
  });

  it('CKP_075_a_missing_rule_set_history_reads_not_found', async () => {
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/history', () => HttpResponse.json({ type: 'about:blank', title: 'Not Found', status: 404, code: 'RULE_SET_NOT_FOUND', detail: 'Rule set card-fee not found', correlationId: '01JFAKE0000000000000000002' }, { status: 404, headers: { 'Content-Type': 'application/problem+json' } })));
    open('#/rules/card-fee/2/history');
    expect(await screen.findByText('This rule set was not found on this service.')).toBeInTheDocument();
  });
});
