import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../../App';
import type { Scenario } from '../../mocks/fixtures';
import { resetRuleEngine } from '../../mocks/handlers';
import { mockHost, setScenario } from '../../mocks/node';
import { demoSeed } from '../../mocks/ruleEngine';

/*
 * CKP-038 to CKP-040: the spreadsheet round trip of a rule table. The engine reads and writes the file (PD-107,
 * PD-142); the cockpit exports it, asks the user to confirm an import "based on version N", and shows the
 * engine's refusals per row and column in the user's language. The demo data is Sao Mai Bank's card-fee v1.
 */

interface Call { method: string; url: string; key: string | null; type: string | null }
let calls: Call[] = [];
let downloads: { name: string; file: Blob }[] = [];
let engine: ReturnType<typeof resetRuleEngine>;

beforeEach(() => {
  calls = [];
  downloads = [];
  engine = resetRuleEngine(demoSeed());
  mockHost.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/api/bpm/rules')) {
      calls.push({ method: request.method, url: decodeURIComponent(url.pathname + url.search).replace('/api/bpm/rules/v1', ''), key: request.headers.get('Idempotency-Key'), type: request.headers.get('Content-Type') });
    }
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
  // jsdom has no Blob.arrayBuffer on File in every version; the picker needs the bytes.
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

/** card-fee v1 as the engine exports it: header, one line per rule, shorthand cells. */
const HEADER = 'Rule,tier,balance,txCount,fee,waived,Description';
const ROWS = ['R1,PLATINUM,-,-,0 VND,true,', 'R2,GOLD,>= 50000000 VND,-,0 VND,true,', 'R3,GOLD,-,-,30000 VND,false,', 'R4,-,-,>= 20,15000 VND,false,', 'R5,-,-,-,50000 VND,false,'];
const file = (lines: readonly string[], name = 'card-fee-v1.csv') => new File([`\uFEFF${lines.join('\r\n')}\r\n`], name, { type: 'text/csv' });
const sheetInput = () => {
  const input = document.body.querySelector<HTMLInputElement>('.rs-head input[type=file]');
  if (!input) throw new Error('file input missing');
  return input;
};
const posts = () => calls.filter((c) => c.method === 'POST' && c.url.includes('/versions?base='));
const dialog = () => screen.findByRole('dialog', { name: /Import spreadsheet|Nhập bảng tính/ });

async function exportCsv(user: ReturnType<typeof userEvent.setup>, label = 'Export', item = 'As spreadsheet (CSV)') {
  await user.click(await screen.findByRole('button', { name: label }));
  await user.click(screen.getByRole('menuitem', { name: item }));
}

describe('spreadsheet export (CKP-038)', () => {
  it('CKP_038_a_version_exports_as_a_csv_file_the_engine_wrote', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await exportCsv(user);
    expect(await screen.findByText('Saved card-fee-v1.csv')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'GET' && c.url === '/rule-sets/card-fee/versions/1/export?format=csv')).toBe(true);
    const bytes = new Uint8Array(await (downloads[0]?.file ?? new Blob()).arrayBuffer());
    expect(Array.from(bytes.slice(0, 3))).toEqual([0xef, 0xbb, 0xbf]);
    const text = new TextDecoder('utf-8', { ignoreBOM: true }).decode(bytes);
    expect(text.split('\r\n').slice(0, 3)).toEqual(['\uFEFF' + HEADER, ROWS[0], ROWS[1]]);
    expect(downloads[0]?.name).toBe('card-fee-v1.csv');
  });

  it('CKP_038_a_version_with_no_table_form_saves_nothing_and_names_the_rule', async () => {
    const user = userEvent.setup();
    const seed = demoSeed();
    const v1 = seed.find((v) => v.ruleSet === 'card-fee' && v.version === 1);
    if (v1) v1.definition.rules[1] = { ...v1.definition.rules[1], id: 'R2', when: { tier: "any(== 'GOLD', == 'PLATINUM')" }, then: { fee: '0 VND' } };
    resetRuleEngine(seed);
    open('#/rules/card-fee/1');
    await exportCsv(user);
    expect(await screen.findByText('This version cannot be a spreadsheet.')).toBeInTheDocument();
    expect(screen.getByText('Row R2')).toBeInTheDocument();
    expect(screen.getByText('uses a condition a table cannot show')).toBeInTheDocument();
    expect(downloads).toEqual([]);
  });
});

describe('spreadsheet import (CKP-039)', () => {
  it('CKP_039_a_valid_file_is_sent_once_after_confirming_and_opens_the_new_draft', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    fireEvent.change(sheetInput(), { target: { files: [file([HEADER, ROWS[0] ?? '', ROWS[1] ?? '', 'R3,GOLD,-,-,32000 VND,false,', ROWS[3] ?? '', ROWS[4] ?? ''])] } });
    const confirm = await dialog();
    expect(within(confirm).getByText('Based on version 1')).toBeInTheDocument();
    expect(within(confirm).getByText('card-fee-v1.csv')).toBeInTheDocument();
    // Nothing is sent before the user confirms.
    expect(posts()).toEqual([]);
    const button = within(confirm).getByRole('button', { name: 'Import as draft' });
    await user.dblClick(button);
    expect(await screen.findByText('Draft version 3 created')).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(posts()[0]).toMatchObject({ url: '/rule-sets/card-fee/versions?base=1', type: 'text/csv' });
    expect(posts()[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
    const created = engine.store.find((v) => v.ruleSet === 'card-fee' && v.version === 3);
    expect(created?.status).toBe('DRAFT');
    expect(created?.definition.rules.find((r) => r.id === 'R3')?.then['fee']).toBe('32000 VND');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('CKP_039_a_refused_cell_is_listed_by_row_and_column_without_its_value_and_nothing_is_saved', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    fireEvent.change(sheetInput(), { target: { files: [file([HEADER, ROWS[0] ?? '', 'R2,GOLD,>= 51234567,-,0 VND,true,', ROWS[2] ?? '', ROWS[3] ?? '', ROWS[4] ?? ''])] } });
    await user.click(within(await dialog()).getByRole('button', { name: 'Import as draft' }));
    const refused = await screen.findByText('Import refused. Nothing was saved.');
    expect(refused).toBeInTheDocument();
    expect(screen.getByText('card-fee-v1.csv, row R2, column Balance')).toBeInTheDocument();
    expect(screen.getByText('the amount has no currency. Write it after the number, for example 50000000 VND.')).toBeInTheDocument();
    // A refusal never quotes a cell, so a value of a masked input cannot leave in one.
    expect(within(screen.getByRole('dialog')).queryByText(/51234567/)).not.toBeInTheDocument();
    expect(engine.store.filter((v) => v.ruleSet === 'card-fee')).toHaveLength(2);
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Choose another file' })).toBeInTheDocument();
  });

  it('CKP_039_a_refusal_is_said_in_vietnamese_on_a_vietnamese_screen', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1', 'rules-author', 'vi');
    await screen.findByRole('button', { name: 'Nhập phiên bản mới' });
    fireEvent.change(sheetInput(), { target: { files: [file([HEADER, ROWS[0] ?? '', 'R2,GOLD,>= 51234567,-,0 VND,true,', ROWS[2] ?? '', ROWS[3] ?? '', ROWS[4] ?? ''])] } });
    const confirm = await dialog();
    expect(within(confirm).getByText('Dựa trên phiên bản 1')).toBeInTheDocument();
    await user.click(within(confirm).getByRole('button', { name: 'Nhập thành bản nháp' }));
    expect(await screen.findByText('Đã từ chối nhập tệp. Không có thay đổi nào được lưu.')).toBeInTheDocument();
    expect(screen.getByText(/dòng R2, cột/)).toBeInTheDocument();
    expect(screen.getByText('số tiền thiếu loại tiền. Hãy ghi sau con số, ví dụ 50000000 VND.')).toBeInTheDocument();
    expect(within(screen.getByRole('dialog')).queryByText(/amount has no currency/)).not.toBeInTheDocument();
  });

  it('CKP_039_a_conflict_keeps_the_file_and_the_dialog_and_a_retry_sends_a_new_key', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    mockHost.use(http.post('*/api/bpm/rules/v1/rule-sets/card-fee/versions', () => {
      attempts += 1;
      if (attempts > 1) return undefined;
      return HttpResponse.json({ type: 'x', title: 'Conflict', status: 409, code: 'VERSION_CONFLICT', detail: 'Another version was created', correlationId: '01JCONFLICT0000000000000000' }, { status: 409, headers: { 'Content-Type': 'application/problem+json' } });
    }));
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    fireEvent.change(sheetInput(), { target: { files: [file([HEADER, ...ROWS])] } });
    await user.click(within(await dialog()).getByRole('button', { name: 'Import as draft' }));
    expect(await screen.findByText('This rule set changed while you imported. Your file is kept.')).toBeInTheDocument();
    const kept = screen.getByRole('dialog');
    expect(within(kept).getByText('card-fee-v1.csv')).toBeInTheDocument();
    await user.click(within(kept).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Draft version 3 created')).toBeInTheDocument();
    expect(new Set(posts().map((c) => c.key)).size).toBe(2);
  });

  it('CKP_039_cancelling_sends_nothing', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    fireEvent.change(sheetInput(), { target: { files: [file([HEADER, ...ROWS])] } });
    await user.click(within(await dialog()).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => { expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); });
    expect(posts()).toEqual([]);
  });

  it('CKP_039_a_reader_is_offered_export_and_not_import', async () => {
    open('#/rules/card-fee/1', 'rules-reader');
    expect(await screen.findByRole('button', { name: 'Export' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Import new version' })).not.toBeInTheDocument();
  });
});

describe('spreadsheet columns and damaged cells (CKP-040)', () => {
  it('CKP_040_an_added_or_missing_column_is_refused_by_name', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    const lines = [HEADER.replace(',waived', ',segment'), ...ROWS.map((r) => r.replace(',true,', ',x,').replace(',false,', ',x,'))];
    fireEvent.change(sheetInput(), { target: { files: [file(lines)] } });
    await user.click(within(await dialog()).getByRole('button', { name: 'Import as draft' }));
    expect(await screen.findByText('card-fee-v1.csv, column segment')).toBeInTheDocument();
    expect(screen.getByText('this column is not in the base version. Inputs and outputs cannot change through a file.')).toBeInTheDocument();
    expect(screen.getByText('card-fee-v1.csv, column waived')).toBeInTheDocument();
    expect(engine.store.filter((v) => v.ruleSet === 'card-fee')).toHaveLength(2);
  });

  it('CKP_040_excel_damage_is_refused_cell_by_cell_and_a_semicolon_file_is_read', async () => {
    const user = userEvent.setup();
    open('#/rules/card-fee/1');
    await screen.findByRole('button', { name: 'Import new version' });
    const damaged = [HEADER, 'R1,PLATINUM,5E+07 VND,-,0 VND,true,', 'R2,GOLD,"50,000,000 VND",-,0 VND,true,', ROWS[2] ?? '', ROWS[3] ?? '', ROWS[4] ?? ''];
    fireEvent.change(sheetInput(), { target: { files: [file(damaged)] } });
    await user.click(within(await dialog()).getByRole('button', { name: 'Import as draft' }));
    expect(await screen.findByText('card-fee-v1.csv, row R1, column Balance')).toBeInTheDocument();
    expect(screen.getByText('the amount is in scientific notation. Write it in full, for example 50000000 VND.')).toBeInTheDocument();
    expect(screen.getByText('card-fee-v1.csv, row R2, column Balance')).toBeInTheDocument();
    expect(screen.getByText('write the amount without separators, for example 50000000 VND.')).toBeInTheDocument();
    expect(engine.store.filter((v) => v.ruleSet === 'card-fee')).toHaveLength(2);
    // A semicolon file without a byte order mark is accepted.
    await user.click(screen.getByRole('button', { name: 'Choose another file' }));
    const semicolon = new File([[HEADER, ...ROWS].map((l) => l.replaceAll(',', ';').replace('>= 50000000 VND', '>= 50000000 VND')).join('\r\n')], 'card-fee-v1.csv', { type: 'text/csv' });
    const inDialog = screen.getByRole('dialog').querySelector<HTMLInputElement>('input[type=file]');
    if (!inDialog) throw new Error('file input missing');
    fireEvent.change(inDialog, { target: { files: [semicolon] } });
    await user.click(await within(screen.getByRole('dialog')).findByRole('button', { name: 'Import as draft' }));
    expect(await screen.findByText('Draft version 3 created')).toBeInTheDocument();
  });
});
