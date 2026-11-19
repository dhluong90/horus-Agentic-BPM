import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { HostCallError } from '../hostError';
import { csrfToken } from '../hostCall';
import { knownRuleSets, listVersions, rememberRuleSet } from './catalogue';
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

function engineFetch(user = 'minh') {
  const engine = createFakeEngine(demoSeed());
  return recordingFetch((sent) => {
    const answer = engine.handle({ method: sent.method, path: sent.url, headers: sent.headers, body: sent.body ?? '', user });
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
    const { fetchImpl } = engineFetch('lan');
    const client = createRulesClient('', fetchImpl);
    const error: unknown = await client.approve('card-fee', 2, 1, undefined, 'key-2').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(HostCallError);
    expect(error instanceof HostCallError ? error.hostError : undefined).toMatchObject({ kind: 'problem', status: 403, code: 'SELF_APPROVAL_FORBIDDEN' });
  });

  it('CKP_030_versions_are_found_by_reading_until_not_found', async () => {
    const { sent, fetchImpl } = engineFetch();
    const versions = await listVersions(createRulesClient('', fetchImpl), 'card-fee');
    expect(versions.map((v) => [v.version, v.status])).toEqual([[1, 'PUBLISHED'], [2, 'PENDING_APPROVAL']]);
    expect(sent.map((s) => s.url.split('/').pop())).toEqual(['1', '2', '3']);
    expect(await listVersions(createRulesClient('', fetchImpl), 'nothing-here')).toEqual([]);
  });

  it('CKP_030_probing_stops_at_the_first_not_found', async () => {
    const { sent, fetchImpl } = engineFetch();
    await listVersions(createRulesClient('', fetchImpl), 'kyc-risk');
    expect(sent).toHaveLength(2);
  });

  it('CKP_030_remembered_rule_sets_are_kept_per_user', () => {
    const lan = { hostId: 'loans', tenant: 'bank-a', userId: 'lan' };
    const minh = { ...lan, userId: 'minh' };
    rememberRuleSet(lan, 'card-fee');
    rememberRuleSet(lan, 'Not A Name');
    expect(knownRuleSets(lan)).toEqual(['card-fee']);
    expect(knownRuleSets(minh)).toEqual([]);
    expect(knownRuleSets({ ...lan, tenant: 'bank-b' })).toEqual([]);
  });

  it('TR_UI_02_csrf_token_is_read_from_the_host_cookie', () => {
    expect(csrfToken('a=1; XSRF-TOKEN=abc%3D; b=2')).toBe('abc=');
    expect(csrfToken('a=1')).toBeUndefined();
  });

  it('TR_UI_02_every_client_path_is_an_operation_of_the_engine_contract', () => {
    // The contract the client is written from: rule-engine/api/openapi.yaml in this repository.
    const contract = readFileSync(resolve(process.cwd(), '../../rule-engine/api/openapi.yaml'), 'utf8');
    for (const path of ['/rule-sets:', '/rule-sets/{name}/versions:', '/rule-sets/{name}/versions/{version}:', '/rule-sets/{name}/versions/{version}:request-publish:', '/rule-sets/{name}/versions/{version}:approve:', '/decisions/{ruleSet}:evaluate:']) {
      expect(contract, path).toContain(`  ${path}\n`);
    }
  });
});
