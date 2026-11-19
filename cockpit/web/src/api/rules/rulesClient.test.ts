import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { HostCallError } from '../hostError';
import { csrfToken } from '../hostCall';
import { allVersions } from './catalogue';
import { everyRuleSet } from '../../features/overview/useRuleOverview';
import type { RulesClient } from './rulesClient';
import { createRulesClient } from './rulesClient';
import { createFakeEngine, demoSeed } from '../../mocks/ruleEngine';

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
    const approved = await client.approve('card-fee', 2, pending.lockVersion, 'Checked', 'key-1');
    expect(approved.status).toBe('PUBLISHED');
    const post = sent[1];
    expect(post?.url).toBe('/api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve');
    expect(post?.headers).toMatchObject({ 'idempotency-key': 'key-1', 'if-match': '"1"', 'x-xsrf-token': 'token-123', 'content-type': 'application/json' });
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ comment: 'Checked' });
    expect(sent[0]?.headers['x-xsrf-token']).toBeUndefined();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('TR_UI_02_refusals_arrive_as_typed_problems_with_code_and_correlation_id', async () => {
    // Lan wrote and requested card-fee v2; even holding rules:approve she cannot approve it.
    const { fetchImpl } = engineFetch('lan', ['rules:read', 'rules:author', 'rules:approve']);
    const client = createRulesClient('', fetchImpl);
    const error: unknown = await client.approve('card-fee', 2, 1, undefined, 'key-2').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(HostCallError);
    expect(error instanceof HostCallError ? error.hostError : undefined).toMatchObject({ kind: 'problem', status: 403, code: 'SELF_APPROVAL_FORBIDDEN' });
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
    const rejected = await client.reject('card-fee', 2, 1, 'Needs committee minutes', 'key-r');
    expect(rejected).toMatchObject({ status: 'DRAFT', requestedBy: null });
    expect(sent[0]?.headers).toMatchObject({ 'idempotency-key': 'key-r', 'if-match': '"1"' });
    expect(JSON.parse(sent[0]?.body ?? '{}')).toEqual({ reason: 'Needs committee minutes' });
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

  it('TR_UI_02_csrf_token_is_read_from_the_host_cookie', () => {
    expect(csrfToken('a=1; XSRF-TOKEN=abc%3D; b=2')).toBe('abc=');
    expect(csrfToken('a=1')).toBeUndefined();
  });

  it('TR_UI_02_every_client_path_is_an_operation_of_the_engine_contract', () => {
    // The contract the client is written from: rule-engine/api/openapi.yaml in this repository.
    const contract = readFileSync(resolve(process.cwd(), '../../rule-engine/api/openapi.yaml'), 'utf8');
    for (const path of ['/rule-sets:', '/rule-sets/{name}/versions:', '/rule-sets/{name}/versions/{version}:', '/rule-sets/{name}/versions/{version}/history:', '/rule-sets/{name}/versions/{version}:request-publish:', '/rule-sets/{name}/versions/{version}:approve:', '/rule-sets/{name}/versions/{version}:reject:', '/decisions/{ruleSet}:evaluate:']) {
      expect(contract, path).toContain(`  ${path}\n`);
    }
  });
});
