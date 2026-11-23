import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { mockHost, setScenario } from '../../mocks/node';
import { canonicalJson, demoSeed } from '../../mocks/ruleEngine';

/*
 * CKP-030: an author lists rule sets, reads a version as a table, and imports or exports it. The demo data is
 * Sao Mai Bank's: card-fee v1 published, v2 waiting for approval; kyc-risk v1 published.
 */

interface Call { method: string; url: string; key: string | null }
let calls: Call[] = [];
let downloads: { name: string; file: Blob }[] = [];

beforeEach(() => {
  calls = [];
  downloads = [];
  mockHost.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/api/bpm/rules')) {
      calls.push({ method: request.method, url: decodeURIComponent(url.pathname + url.search).replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key') });
    }
  });
  // The browser's download: the object URL's blob and the link's file name.
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
  return () => { mockHost.events.removeAllListeners(); };
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function open(hash: string, scenario: Scenario = 'rules-author', locale?: 'vi') {
  setScenario(scenario);
  window.location.hash = hash;
  render(<App {...(locale ? { initialLocale: locale } : {})} />);
}

const document3 = (balance: string) => JSON.stringify({
  schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }, { name: 'balance', type: 'money' }], outputs: [{ name: 'fee', type: 'money' }],
  rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }, { id: 'R4', when: { balance: `>= ${balance}` }, then: { fee: '1000 VND' } }],
});
const GOOD = document3('50000000 VND');
const BAD = document3('50000000');

const fileInput = () => {
  const input = document.body.querySelector<HTMLInputElement>('.rs-head input[type=file]');
  if (!input) throw new Error('file input missing');
  return input;
};

async function exportAs(user: ReturnType<typeof userEvent.setup>, item: string) {
  await user.click(await screen.findByRole('button', { name: 'Export' }));
  await user.click(screen.getByRole('menuitem', { name: item }));
}

const versionLine = (n: number) => screen.findByText(new RegExp(`^Version ${String(n)} ·`), { selector: '.rs-head__sub' });

describe('rule set browser (CKP-030)', () => {
  it('CKP_030_rule_set_browser_lists_reads_imports_and_exports', async () => {
    const user = userEvent.setup();
    open('#/rules');

    // The list: two rows, read from GET /rule-sets, with status and the version in effect.
    const table = await screen.findByRole('table');
    await waitFor(() => { expect(within(table).getAllByRole('row')).toHaveLength(3); });
    const [cardFee, kycRisk] = within(table).getAllByRole('row').slice(1) as [HTMLElement, HTMLElement];
    expect(cardFee).toHaveTextContent('Card fee');
    expect(within(cardFee).getByText('Waiting for approval')).toBeInTheDocument();
    expect(kycRisk).toHaveTextContent('Kyc risk');
    expect(calls[0]?.method).toBe('GET');
    expect(calls[0]?.url).toMatch(/^\/rule-sets\?limit=\d+$/);

    // Search narrows the list.
    await user.type(screen.getByPlaceholderText('Find a rule set'), 'kyc');
    await waitFor(() => { expect(within(table).getAllByRole('row')).toHaveLength(2); });
    await user.clear(screen.getByPlaceholderText('Find a rule set'));

    // Open card-fee, then version 1 from the version picker: a table, no YAML or JSON.
    await user.click(await screen.findByRole('link', { name: 'Card fee' }));
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Card fee');
    await user.selectOptions(await screen.findByLabelText('Versions'), '1');
    expect(await versionLine(1)).toHaveTextContent('first matching row wins');
    expect(screen.getByText('50,000,000 VND')).toBeInTheDocument();
    expect(screen.queryByText(/schemaVersion/)).not.toBeInTheDocument();

    // Export as JSON: one GET, the file saved as card-fee-v1.json with the engine's bytes.
    await exportAs(user, 'As JSON');
    expect(await screen.findByText('Saved card-fee-v1.json')).toBeInTheDocument();
    expect(calls.filter((c) => c.url.includes('/export')).map((c) => `${c.method} ${c.url}`)).toEqual(['GET /rule-sets/card-fee/versions/1/export?format=json']);
    expect(downloads.map((d) => d.name)).toEqual(['card-fee-v1.json']);
    const v1 = demoSeed().find((v) => v.ruleSet === 'card-fee' && v.version === 1);
    expect(await downloads[0]?.file.text()).toBe(canonicalJson(v1?.definition as never));

    // Import card-fee-v3.yaml: one POST with a key, and the message only after the 201.
    await user.upload(fileInput(), new File([GOOD], 'card-fee-v3.yaml', { type: 'application/yaml' }));
    expect(await screen.findByText('Draft version 3 created')).toBeInTheDocument();
    expect(await versionLine(3)).toBeInTheDocument();
    const posts = calls.filter((c) => c.method === 'POST');
    expect(posts.map((c) => c.url)).toEqual(['/rule-sets/card-fee/versions']);
    expect(posts[0]?.key).toMatch(/^[0-9a-f-]{36}$/);

    // card-fee-bad.yaml: refused whole, located by row and column, no version 4.
    await user.upload(fileInput(), new File([BAD], 'card-fee-bad.yaml', { type: 'application/yaml' }));
    expect(await screen.findByText('Import refused. Nothing was saved.')).toBeInTheDocument();
    expect(screen.getByText('card-fee-bad.yaml, row R4, column Balance')).toBeInTheDocument();
    expect(screen.getByText('compares a money amount with a plain number')).toBeInTheDocument();
    expect(screen.queryByText('Draft version 4 created')).not.toBeInTheDocument();
    expect(within(screen.getByLabelText('Versions')).getAllByRole('option')).toHaveLength(3);
  });

  it('CKP_030_a_reader_sees_export_and_no_import', async () => {
    open('#/rules/card-fee/1', 'rules-reader');
    expect(await screen.findByRole('button', { name: 'Export' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Import new version' })).not.toBeInTheDocument();
    window.location.hash = '#/rules';
    await screen.findByRole('table');
    expect(screen.queryByRole('button', { name: 'Import rule set' })).not.toBeInTheDocument();
    // A reader cannot approve, so the menu shows no count of requests to review.
    expect(document.querySelector('.navrail__count')).toBeNull();
  });

  it('CKP_030_a_double_pick_sends_one_key_and_creates_one_version', async () => {
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    const file = new File([GOOD], 'card-fee-v3.yaml', { type: 'application/yaml' });
    fireEvent.change(fileInput(), { target: { files: [file] } });
    fireEvent.change(fileInput(), { target: { files: [file] } });
    await waitFor(() => { expect(calls.some((c) => c.method === 'POST')).toBe(true); });
    expect(await screen.findByText('Draft version 3 created')).toBeInTheDocument();
    const keys = new Set(calls.filter((c) => c.method === 'POST').map((c) => c.key));
    expect(keys.size).toBe(1);
    await waitFor(() => { expect(within(screen.getByLabelText('Versions')).getAllByRole('option')).toHaveLength(3); });
  });

  it('CKP_030_export_as_dmn_saves_a_dmn_file', async () => {
    const user = userEvent.setup();
    open('#/rules/kyc-risk/1');
    await exportAs(user, 'As DMN decision table');
    expect(await screen.findByText('Saved kyc-risk-v1.dmn')).toBeInTheDocument();
    expect(await downloads[0]?.file.text()).toContain('<decision id="kyc-risk"');
  });

  it('CKP_030_an_export_the_engine_refuses_is_located_and_saves_nothing', async () => {
    const user = userEvent.setup();
    mockHost.use(http.get('*/api/bpm/rules/v1/rule-sets/card-fee/versions/1/export', () => HttpResponse.json(
      { type: 'x', title: 'Unprocessable Content', status: 422, code: 'VALIDATION_FAILED', detail: 'Rule R2, column fee: a calculated cell has no decision table form', correlationId: '01JEXPORT000000000000000000', violations: [{ ruleId: 'R2', column: 'fee', message: 'a calculated cell has no decision table form' }] },
      { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
    )));
    open('#/rules/card-fee/1');
    await exportAs(user, 'As DMN decision table');
    expect(await screen.findByText('Row R2, column Fee')).toBeInTheDocument();
    expect(screen.getByText('a calculated cell has no decision table form')).toHaveAttribute('lang', 'en');
    expect(downloads).toEqual([]);
  });

  it('CKP_030_import_and_export_speak_vietnamese', async () => {
    open('#/rules/card-fee/1', 'rules-author', 'vi');
    expect(await screen.findByRole('button', { name: 'Xuất tệp' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nhập phiên bản mới' })).toBeInTheDocument();
  });

  it('CKP_030_a_dmn_file_is_imported_as_xml', async () => {
    const types: string[] = [];
    mockHost.events.on('request:start', ({ request }) => { if (request.method === 'POST') types.push(request.headers.get('Content-Type') ?? ''); });
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    await userEvent.setup().upload(fileInput(), new File(['<definitions/>'], 'card-fee.dmn', { type: 'application/xml' }));
    await waitFor(() => { expect(types).toEqual(['application/xml']); });
  });

  it('CKP_030_an_imported_version_names_who_imported_it_and_the_file', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    await user.upload(fileInput(), new File([GOOD], 'card-fee-v3.yaml', { type: 'application/yaml' }));
    expect(await screen.findByText('Imported by lan from card-fee-v3.yaml')).toBeInTheDocument();
    // The new version's title takes the focus, so the import button no longer holds it.
    await waitFor(() => { expect(screen.getByRole('heading', { level: 1 })).toHaveFocus(); });
  });

  it('CKP_030_a_refused_import_says_why_in_vietnamese', async () => {
    open('#/rules/card-fee/1', 'rules-author', 'vi');
    await screen.findByRole('button', { name: 'Nhập phiên bản mới' });
    await userEvent.setup().upload(fileInput(), new File([BAD], 'card-fee-bad.yaml', { type: 'application/yaml' }));
    expect(await screen.findByText('Đã từ chối nhập tệp. Không có thay đổi nào được lưu.')).toBeInTheDocument();
    expect(screen.getByText('card-fee-bad.yaml, dòng R4, cột Balance')).toBeInTheDocument();
    expect(screen.getByText('so sánh số tiền với một số thường')).toBeInTheDocument();
  });

  it('CKP_030_a_refused_new_rule_set_names_the_file_on_the_list', async () => {
    open('#/rules');
    await screen.findByRole('table');
    const input = document.body.querySelector<HTMLInputElement>('.workspace input[type=file]');
    if (!input) throw new Error('file input missing');
    await userEvent.setup().upload(input, new File([BAD.replace('card-fee', 'new-fee')], 'new-fee.yaml', { type: 'application/yaml' }));
    expect(await screen.findByText('Import refused. Nothing was saved.')).toBeInTheDocument();
    expect(screen.getByText('new-fee.yaml, row R4, column Balance')).toBeInTheDocument();
  });

  it('CKP_030_short_codes_and_labelled_columns_read_as_written', async () => {
    open('#/rules/kyc-risk/1', 'rules-reader');
    expect(await screen.findByRole('columnheader', { name: 'PEP' })).toBeInTheDocument();
    expect(screen.getByText('VN')).toBeInTheDocument();
    expect(screen.getByText('SG')).toBeInTheDocument();
  });
});
