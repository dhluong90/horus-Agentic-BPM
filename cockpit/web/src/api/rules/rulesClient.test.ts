import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { HostCallError } from '../hostError';
import { attachmentName, csrfToken } from '../hostCall';
import { allVersions } from './catalogue';
import { everyRuleSet } from '../../features/overview/useRuleOverview';
import type { RulesClient } from './rulesClient';
import { createRulesClient } from './rulesClient';
import { versionDiff } from './schemas';
import { canonicalJson, createFakeEngine, demoSeed } from '../../mocks/ruleEngine';

interface Sent { url: string; method: string; headers: Record<string, string>; body: string | undefined }

function recordingFetch(answer: (sent: Sent) => Response) {
  const sent: Sent[] = [];
  const fetchImpl: typeof fetch = (input, init) => {
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((value, key) => { headers[key] = value; });
    const entry: Sent = { url: input instanceof Request ? input.url : input.toString(), method: init?.method ?? 'GET', headers, body: typeof init?.body === 'string' ? init.body : undefined };
    sent.push(entry);
    return Promise.resolve(answer(entry));
  };
  return { sent, fetchImpl };
}

const PERMISSIONS: Record<string, string[]> = { lan: ['rules:read', 'rules:author'], minh: ['rules:read', 'rules:approve'] };

function engineFetch(user = 'minh', permissions: string[] = PERMISSIONS[user] ?? []) {
  const engine = createFakeEngine(demoSeed());
  return recordingFetch((sent) => {
    const answer = engine.handle({ method: sent.method, path: sent.url, headers: sent.headers, body: sent.body ?? '', user, permissions });
    return new Response(answer.body, { status: answer.status, headers: answer.headers });
  });
}

describe('rules client', () => {
  it('TR_IDEM_01_state_changes_send_idempotency_key_if_match_and_csrf', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/';
    const { sent, fetchImpl } = engineFetch();
    const client = createRulesClient('', fetchImpl);
    const pending = await client.getVersion('card-fee', 2);
    const approved = await client.approve('card-fee', 2, pending.lockVersion, pending.canonicalHash, 'Checked', 'key-1');
    expect(approved.status).toBe('PUBLISHED');
    const post = sent[1];
    expect(post?.url).toBe('/api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve');
    expect(post?.headers).toMatchObject({ 'idempotency-key': 'key-1', 'if-match': '"1"', 'x-xsrf-token': 'token-123', 'content-type': 'application/json' });
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedCanonicalHash: pending.canonicalHash, comment: 'Checked' });
    expect(sent[0]?.headers['x-xsrf-token']).toBeUndefined();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('CKP_037_the_diff_is_read_with_its_base_and_its_masking_column', async () => {
    const { sent, fetchImpl } = engineFetch();
    const diff = await createRulesClient('', fetchImpl).diffVersions('card-fee', 2, 1);
    expect(sent[0]?.url).toBe('/api/bpm/rules/v1/rule-sets/card-fee/versions/2/diff?base=1');
    expect(diff.masking.map((item) => [item.input, item.from, item.to, item.changed])).toEqual([
      ['tier', 'NOT_MASKED_BY_DEFAULT', 'NOT_MASKED_BY_DEFAULT', false],
      ['balance', 'NOT_MASKED_BY_DEFAULT', 'NOT_MASKED_BY_DEFAULT', false],
      ['txCount', 'NOT_MASKED_BY_DEFAULT', 'NOT_MASKED_BY_DEFAULT', false],
    ]);
  });

  it('TR_UI_02_refusals_arrive_as_typed_problems_with_code_and_correlation_id', async () => {
    // Lan wrote and requested card-fee v2; even holding rules:approve she cannot approve it.
    const { fetchImpl } = engineFetch('lan', ['rules:read', 'rules:author', 'rules:approve']);
    const client = createRulesClient('', fetchImpl);
    const error: unknown = await client.approve('card-fee', 2, 1, 'a'.repeat(64), undefined, 'key-2').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(HostCallError);
    expect(error instanceof HostCallError ? error.hostError : undefined).toMatchObject({ kind: 'problem', status: 403, code: 'SELF_APPROVAL_FORBIDDEN' });
  });

  it('RLE_084_a_hash_that_no_longer_matches_is_CONTENT_CHANGED_with_the_current_hash_and_nothing_is_approved', async () => {
    const { fetchImpl } = engineFetch('minh');
    const client = createRulesClient('', fetchImpl);
    const stored = (await client.getVersion('card-fee', 2)).canonicalHash;
    const error: unknown = await client.approve('card-fee', 2, 1, 'a'.repeat(64), undefined, 'key-c').catch((e: unknown) => e);
    expect(error instanceof HostCallError ? error.hostError : undefined).toMatchObject({ kind: 'problem', status: 409, code: 'CONTENT_CHANGED', members: { currentCanonicalHash: stored } });
    expect((await client.getVersion('card-fee', 2)).status).toBe('PENDING_APPROVAL');
  });

  it('RLE_039_rule_sets_page_with_a_cursor_until_the_last_page', async () => {
    const { sent, fetchImpl } = engineFetch();
    const client = createRulesClient('', fetchImpl);
    const first = await client.listRuleSets({ limit: 1 });
    expect(first.data.map((r) => [r.ruleSet, r.latestVersion, r.latestStatus, r.publishedVersion])).toEqual([['card-fee', 2, 'PENDING_APPROVAL', 1]]);
    expect(first.hasNext).toBe(true);
    const second = await client.listRuleSets({ limit: 1, cursor: first.nextCursor ?? '' });
    expect(second.data.map((r) => r.ruleSet)).toEqual(['kyc-risk']);
    expect(second).toMatchObject({ hasNext: false, nextCursor: null });
    expect(sent[1]?.url).toBe(`/api/bpm/rules/v1/rule-sets?limit=1&cursor=${encodeURIComponent(first.nextCursor ?? '')}`);
    const bad: unknown = await client.listRuleSets({ cursor: 'not-ours' }).catch((e: unknown) => e);
    expect(bad instanceof HostCallError ? bad.hostError : undefined).toMatchObject({ status: 400, code: 'QUERY_INVALID' });
  });

  it('RLE_040_versions_arrive_newest_first_with_allowed_actions_for_the_caller', async () => {
    const asMinh = await allVersions(createRulesClient('', engineFetch('minh').fetchImpl), 'card-fee');
    expect(asMinh.map((v) => [v.version, v.status, v.allowedActions])).toEqual([[2, 'PENDING_APPROVAL', ['approve', 'reject']], [1, 'PUBLISHED', []]]);
    const asLan = await allVersions(createRulesClient('', engineFetch('lan').fetchImpl), 'card-fee');
    expect(asLan[0]?.allowedActions).toEqual([]);
    const missing: unknown = await allVersions(createRulesClient('', engineFetch().fetchImpl), 'nothing-here').catch((e: unknown) => e);
    expect(missing instanceof HostCallError ? missing.hostError : undefined).toMatchObject({ status: 404, code: 'RULE_SET_NOT_FOUND' });
  });

  it('RLE_025_reject_sends_the_reason_with_if_match_and_a_key_and_is_recorded_in_history', async () => {
    const { sent, fetchImpl } = engineFetch('minh');
    const client = createRulesClient('', fetchImpl);
    const reviewed = (await client.getVersion('card-fee', 2)).canonicalHash;
    const rejected = await client.reject('card-fee', 2, 1, reviewed, 'Needs committee minutes', 'key-r');
    expect(rejected).toMatchObject({ status: 'DRAFT', requestedBy: null });
    expect(sent[1]?.headers).toMatchObject({ 'idempotency-key': 'key-r', 'if-match': '"1"' });
    expect(JSON.parse(sent[1]?.body ?? '{}')).toEqual({ reason: 'Needs committee minutes', expectedCanonicalHash: reviewed });
    const history = await client.getHistory('card-fee', 2);
    expect(history.map((h) => [h.action, h.actor, h.reason ?? null])).toEqual([['CREATED', 'lan', null], ['REQUESTED', 'lan', null], ['REJECTED', 'minh', 'Needs committee minutes']]);
  });

  it('RLE_041_violations_of_a_refused_document_arrive_located', async () => {
    const { fetchImpl } = engineFetch('lan');
    const client = createRulesClient('', fetchImpl);
    const document = { schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [{ name: 'balance', type: 'money' }], outputs: [{ name: 'fee', type: 'money' }],
      rules: [{ id: 'R1', when: { balance: '>= 5' }, then: { fee: '30000' } }] };
    const error: unknown = await client.importVersion('card-fee', { contentType: 'application/yaml', text: JSON.stringify(document) }, 'k').catch((e: unknown) => e);
    expect(error instanceof HostCallError ? error.hostError : undefined).toMatchObject({
      status: 422,
      code: 'VALIDATION_FAILED',
      violations: [
        { ruleId: 'R1', column: 'balance', message: 'compares a money with a number' },
        { ruleId: 'R1', column: 'fee', message: expect.stringContaining('needs a currency') as unknown },
      ],
    });
  });

  it('RLE_039_the_page_guard_reports_a_list_cut_short', async () => {
    let n = 0;
    const endless = { listRuleSets: () => Promise.resolve({ data: [{ ruleSet: `rs-${String((n += 1))}`, latestVersion: 1, latestStatus: 'DRAFT' as const, publishedVersion: null, lastChangedAt: '2026-11-18T09:00:00Z' }], nextCursor: 'more', hasNext: true }) } as unknown as RulesClient;
    const cut = await everyRuleSet(endless, 1, undefined, 3);
    expect(cut).toMatchObject({ truncated: true });
    expect(cut.items).toHaveLength(3);
    const { fetchImpl } = engineFetch();
    expect(await everyRuleSet(createRulesClient('', fetchImpl), 1, undefined)).toMatchObject({ truncated: false });
  });

  it('RLE_028_the_client_reads_the_engine_diff_and_sends_the_base_as_a_query', async () => {
    const { sent, fetchImpl } = engineFetch('minh');
    const diff = await createRulesClient('', fetchImpl).diffVersions('card-fee', 2, 1);
    expect(sent.map((s) => `${s.method} ${new URL(s.url, 'http://host').pathname}${new URL(s.url, 'http://host').search}`)).toEqual(['GET /api/bpm/rules/v1/rule-sets/card-fee/versions/2/diff?base=1']);
    expect(diff.rows.map((row) => `${row.ruleId}:${row.change}`)).toEqual(['R6:ADDED', 'R3:CHANGED', 'R4:REMOVED']);
    expect(diff.rows[1]?.fields).toEqual([{ path: '/then/fee', from: { currency: 'VND', amount: 30000 }, to: { currency: 'VND', amount: 35000 }, nonAscii: false }]);
    expect(diff.unchangedCount).toBe(3);
  });

  it('RLE_028_the_contract_example_of_a_diff_parses', () => {
    // The 200 example of diffVersions in rule-engine/api/openapi.yaml.
    const example = {
      ruleSet: 'card-fee', version: 2, base: 1, canonicalHash: '4be1d7a0c35e9f2b8a6d4c1e0f9b7a5d3c2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a', baseCanonicalHash: '9c07e5d3b1a9f7e5d3c1b9a7f5e3d1c9b7a5f3e1d9c7b5a3f1e9d7c5b3a1f9e7',
      sourceHashChanged: true, effectiveFrom: { from: '2026-11-01T00:00:00Z', to: null }, header: [],
      inputs: { added: [], removed: [], changed: [], reordered: false }, outputs: { added: [], removed: [], changed: [], reordered: false },
      rows: [
        { ruleId: 'R2', change: 'CHANGED', position: 2, basePosition: 2, moved: false, fields: [{ path: '/then/fee', from: { currency: 'VND', amount: 30000 }, to: { currency: 'VND', amount: 0 }, nonAscii: false }] },
        { ruleId: 'R6', change: 'ADDED', position: 3 },
      ],
      unchangedCount: 2,
    };
    expect(versionDiff.safeParse(example).success).toBe(true);
    expect(versionDiff.safeParse({ ...example, rows: [{ ruleId: 'R2', change: 'RENAMED' }] }).success).toBe(false);
  });

  it('RLE_028_a_missing_base_is_refused_as_input_invalid', async () => {
    const { fetchImpl } = engineFetch('minh');
    const error: unknown = await createRulesClient('', fetchImpl).diffVersions('card-fee', 2, 2).catch((e: unknown) => e);
    expect(error instanceof HostCallError ? error.hostError : undefined).toMatchObject({ kind: 'problem', status: 400, code: 'INPUT_INVALID' });
  });

  it('TR_UI_02_csrf_token_is_read_from_the_host_cookie', () => {
    expect(csrfToken('a=1; XSRF-TOKEN=abc%3D; b=2')).toBe('abc=');
    expect(csrfToken('a=1')).toBeUndefined();
  });

  it('TR_UI_02_every_client_path_is_an_operation_of_the_engine_contract', () => {
    // The contract the client is written from: rule-engine/api/openapi.yaml in this repository.
    const contract = readFileSync(resolve(process.cwd(), '../../rule-engine/api/openapi.yaml'), 'utf8');
    for (const path of ['/rule-sets:', '/rule-sets/{name}/versions:', '/rule-sets/{name}/versions/{version}:', '/rule-sets/{name}/versions/{version}/history:', '/rule-sets/{name}/versions/{version}/diff:', '/rule-sets/{name}/versions/{version}:request-publish:', '/rule-sets/{name}/versions/{version}:approve:', '/rule-sets/{name}/versions/{version}:reject:', '/decisions/{ruleSet}:evaluate:']) {
      expect(contract, path).toContain(`  ${path}\n`);
    }
  });
});

describe('export (CKP-030, RLE-032)', () => {
  it('CKP_030_export_returns_the_engine_bytes_unchanged_under_its_file_name', async () => {
    const { sent, fetchImpl } = engineFetch('minh');
    const client = createRulesClient('', fetchImpl);
    const exported = await client.exportVersion('card-fee', 1, 'json');
    expect(sent.map((s) => `${s.method} ${s.url}`)).toEqual(['GET /api/bpm/rules/v1/rule-sets/card-fee/versions/1/export?format=json']);
    expect(sent[0]?.headers['idempotency-key']).toBeUndefined();
    expect(exported.fileName).toBe('card-fee-v1.json');
    const definition = demoSeed().find((v) => v.ruleSet === 'card-fee' && v.version === 1)?.definition;
    expect(await exported.file.text()).toBe(canonicalJson(definition as never));
  });

  it('CKP_030_export_as_dmn_is_an_xml_file', async () => {
    const { fetchImpl } = engineFetch('minh');
    const exported = await createRulesClient('', fetchImpl).exportVersion('kyc-risk', 1, 'dmn');
    expect(exported.fileName).toBe('kyc-risk-v1.dmn');
    expect(await exported.file.text()).toContain('<decisionTable hitPolicy="FIRST">');
  });

  it('CKP_030_export_without_a_file_name_falls_back_to_name_version_format', async () => {
    const { fetchImpl } = recordingFetch(() => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    const exported = await createRulesClient('', fetchImpl).exportVersion('card-fee', 2, 'json');
    expect(exported.fileName).toBe('card-fee-v2.json');
  });

  it('CKP_030_an_export_refusal_is_a_typed_problem_with_violations', async () => {
    const { fetchImpl } = recordingFetch(() => new Response(JSON.stringify({ status: 422, code: 'VALIDATION_FAILED', detail: 'no table form', violations: [{ ruleId: 'R2', column: 'fee', message: 'calculated cell' }] }), { status: 422, headers: { 'Content-Type': 'application/problem+json' } }));
    const failure = await createRulesClient('', fetchImpl).exportVersion('card-fee', 1, 'dmn').catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(HostCallError);
    expect((failure as HostCallError).hostError).toMatchObject({ kind: 'problem', code: 'VALIDATION_FAILED', violations: [{ ruleId: 'R2', column: 'fee' }] });
  });

  it('CKP_030_file_name_is_read_from_content_disposition', () => {
    expect(attachmentName('attachment; filename="card-fee-v1.json"')).toBe('card-fee-v1.json');
    expect(attachmentName('attachment; filename=card-fee-v1.dmn')).toBe('card-fee-v1.dmn');
    expect(attachmentName("attachment; filename=\"x.json\"; filename*=UTF-8''ph%C3%AD-v1.json")).toBe('phí-v1.json');
    expect(attachmentName('attachment')).toBeUndefined();
    expect(attachmentName(null)).toBeUndefined();
  });

  it('TR_IDEM_01_the_same_import_key_creates_one_version', async () => {
    const { fetchImpl } = engineFetch('lan');
    const client = createRulesClient('', fetchImpl);
    const text = JSON.stringify({ schemaVersion: 1, ruleSet: 'card-fee', hitPolicy: 'first', inputs: [{ name: 'tier', type: 'string' }], outputs: [{ name: 'fee', type: 'money' }], rules: [{ id: 'R1', when: { tier: "== 'GOLD'" }, then: { fee: '0 VND' } }] });
    const first = await client.importVersion('card-fee', { contentType: 'application/yaml', text }, 'key-1');
    const again = await client.importVersion('card-fee', { contentType: 'application/yaml', text }, 'key-1');
    expect(again.version).toBe(first.version);
    expect((await client.listVersions('card-fee')).data.map((v) => v.version)).toEqual([3, 2, 1]);
  });
});

describe('audit reads and evidence', () => {
  const auditor = () => engineFetch('ha', ['rules:read', 'rules:log:read']);

  it('CKP_071_search_sends_the_rule_set_or_the_case_reference_and_the_period', async () => {
    const { sent, fetchImpl } = auditor();
    const client = createRulesClient('', fetchImpl);
    const page = await client.searchDecisions({ ruleSet: 'card-fee', from: '2026-11-01T00:00:00Z', to: '2026-12-01T00:00:00Z', limit: 50 });
    expect(page.data).toHaveLength(50);
    expect(page.hasNext).toBe(true);
    expect(sent[0]?.url).toBe('/api/bpm/rules/v1/decisions?ruleSet=card-fee&from=2026-11-01T00%3A00%3A00Z&to=2026-12-01T00%3A00%3A00Z&limit=50');
    const byCase = await client.searchDecisions({ correlationId: 'LN-2026-0042', from: '2026-11-01T00:00:00Z', to: '2026-12-01T00:00:00Z' });
    expect(byCase.data.map((d) => d.ruleSet)).toEqual(['card-fee', 'kyc-risk']);
  });

  it('CKP_072_explain_asks_for_the_screen_language', async () => {
    const { sent, fetchImpl } = auditor();
    const client = createRulesClient('', fetchImpl);
    const explanation = await client.explainDecision('01KF2Q8Z0RQ6V2Y8M1C4N5P7T9', 'vi');
    expect(sent[0]?.url).toBe('/api/bpm/rules/v1/decisions/01KF2Q8Z0RQ6V2Y8M1C4N5P7T9/explain?lang=vi');
    expect(sent[0]?.headers['accept-language']).toBe('vi');
    expect(explanation.language).toBe('vi');
    expect(explanation.matched[0]?.ruleId).toBe('R2');
  });

  it('CKP_074_evidence_keeps_the_bytes_and_reads_the_sha256_the_engine_stated', async () => {
    const { sent, fetchImpl } = auditor();
    const client = createRulesClient('', fetchImpl);
    const file = await client.exportEvidence('card-fee', { format: 'csv', versions: [1], decisionIds: ['01KF2Q8Z0RQ6V2Y8M1C4N5P7T9'] }, 'key-9');
    expect(sent[0]?.headers['idempotency-key']).toBe('key-9');
    expect(JSON.parse(sent[0]?.body ?? '{}')).toEqual({ format: 'csv', versions: [1], decisionIds: ['01KF2Q8Z0RQ6V2Y8M1C4N5P7T9'] });
    const text = await file.file.text();
    expect(text).toContain(`# Content SHA-256: ${file.contentSha256}`);
    expect(file.bytes).toBe(file.file.size);
    expect(file.contentDigest).toMatch(/^sha-256=:.+:$/);
    // A repeat with the same key gets the same bytes and records nothing more.
    const again = await client.exportEvidence('card-fee', { format: 'csv', versions: [1], decisionIds: ['01KF2Q8Z0RQ6V2Y8M1C4N5P7T9'] }, 'key-9');
    expect(await again.file.text()).toBe(text);
    expect((await client.getRuleSetHistory('card-fee')).ruleSetEvents).toHaveLength(1);
  });

  it('CKP_074_an_answer_without_the_sha256_is_not_evidence', async () => {
    const { fetchImpl } = recordingFetch(() => new Response('# x', { status: 200, headers: { 'Content-Type': 'text/csv' } }));
    const client = createRulesClient('', fetchImpl);
    await expect(client.exportEvidence('card-fee', { format: 'csv', versions: [1], decisionIds: [] }, 'key-1')).rejects.toBeInstanceOf(HostCallError);
  });
});
