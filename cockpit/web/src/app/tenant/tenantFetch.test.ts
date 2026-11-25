import { describe, expect, it } from 'vitest';
import type { BpmMeV1 } from '../../api/generated/bpm-me.v1';
import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';
import { ENGINE_VERSION } from '../../mocks/engineVersion';
import { switchableTenants, tenantFetch, tenantLabel, type TenantRefusal } from './tenantFetch';

const meta = (switching: boolean): BpmMetaV1 => ({ schemaVersion: 1, modules: [{ module: 'rules', engineVersion: ENGINE_VERSION, apiVersion: 'v1', features: [] }], tenancy: { switching }, limits: { maxPageSize: 200 } });
const me = (current: string, allowed: string[]): BpmMeV1 => ({ schemaVersion: 1, user: { id: 'lan' }, tenant: { current, allowed }, permissions: ['rules:read'] });

describe('tenant switching (CKP-022)', () => {
  it('CKP_022_the_switcher_offers_only_the_allowed_tenants_and_only_when_switching_is_on', () => {
    expect(switchableTenants(meta(true), me('bank-a', ['bank-a', 'bank-a-fin']))).toEqual(['bank-a', 'bank-a-fin']);
    // Switching off on the host: hidden, whatever the token lists.
    expect(switchableTenants(meta(false), me('bank-a', ['bank-a', 'bank-a-fin']))).toEqual([]);
    // One tenant only: nothing to switch to.
    expect(switchableTenants(meta(true), me('bank-a', ['bank-a']))).toEqual([]);
  });

  it('CKP_022_the_switcher_never_offers_a_tenant_the_token_does_not_list_and_shows_the_hosts_display_names', () => {
    // A development tenant is the signed-in tenant of a dev host only; it is not in the token's list, so it is not offered.
    expect(switchableTenants(meta(true), me('dev-local', ['bank-a', 'bank-a-fin']))).toEqual(['bank-a', 'bank-a-fin']);
    expect(switchableTenants(meta(true), me('dev-local', ['dev-local']))).toEqual([]);
    const named: BpmMeV1 = { ...me('bank-a', ['bank-a', 'bank-a-fin']), tenant: { current: 'bank-a', allowed: ['bank-a', 'bank-a-fin'], names: { 'bank-a': 'Bank A Retail' } } };
    expect(tenantLabel(named, 'bank-a')).toBe('Bank A Retail');
    // No name from the host: the id itself.
    expect(tenantLabel(named, 'bank-a-fin')).toBe('bank-a-fin');
  });

  it('CKP_022_a_selected_tenant_is_sent_as_x_tenant_id_and_none_sends_no_header', async () => {
    const seen: (string | null)[] = [];
    const base = ((_input: RequestInfo | URL, init?: RequestInit) => {
      seen.push(new Headers(init?.headers).get('X-Tenant-Id'));
      return Promise.resolve(Response.json({}));
    }) as typeof fetch;
    await tenantFetch(base, 'bank-a-fin', () => undefined)('https://loans.api.bank.example/api/bpm/me');
    await tenantFetch(base, undefined, () => undefined)('https://loans.api.bank.example/api/bpm/me');
    expect(seen).toEqual(['bank-a-fin', null]);
  });

  it('CKP_022_tenant_switcher_allowed_tenants_only_a_refusal_is_reported_with_its_reference', async () => {
    const refusals: TenantRefusal[] = [];
    const refuse = (() => Promise.resolve(Response.json({ title: 'Tenant not allowed', status: 403, code: 'TENANT_NOT_ALLOWED', correlationId: '01JD5T3N4NT0000000000000AB' }, { status: 403, headers: { 'Content-Type': 'application/problem+json' } }))) as typeof fetch;
    const response = await tenantFetch(refuse, 'bank-b', (refusal) => { refusals.push(refusal); })('https://loans.api.bank.example/api/bpm/rules/v1/rule-sets');
    // The screen still gets the answer; the shell is told once.
    expect(response.status).toBe(403);
    expect(await response.json()).toMatchObject({ code: 'TENANT_NOT_ALLOWED' });
    expect(refusals).toEqual([{ tenant: 'bank-b', correlationId: '01JD5T3N4NT0000000000000AB' }]);
  });

  it('CKP_022_another_403_is_not_a_tenant_refusal', async () => {
    const refusals: TenantRefusal[] = [];
    const forbidden = (() => Promise.resolve(Response.json({ code: 'PERMISSION_DENIED' }, { status: 403 }))) as typeof fetch;
    await tenantFetch(forbidden, 'bank-a-fin', (refusal) => { refusals.push(refusal); })('https://loans.api.bank.example/x');
    expect(refusals).toEqual([]);
  });
});
