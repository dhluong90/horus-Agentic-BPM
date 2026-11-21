import { describe, expect, it } from 'vitest';
import type { BpmMetaV1 } from '../../api/generated/bpm-meta.v1';
import { hostSwitchHref } from '../../components/HostPicker';
import { parseRegistry, type HostRegistry } from '../config/registry';
import { linkedHost, parseRoute, splitHost, withHost } from '../route';
import { chooseHost } from './useStandaloneSession';
import { checkVersion, supportedLines } from './versionSupport';

function meta(...versions: string[]): BpmMetaV1 {
  return {
    schemaVersion: 1,
    hostId: 'kyc',
    modules: versions.map((engineVersion, index) => ({ module: index === 0 ? 'rules' : 'processes', engineVersion, apiVersion: 'v1', features: [] })),
    tenancy: { switching: false },
    limits: { maxPageSize: 200 },
  };
}

function registry(hosts: { id: string; scope?: string; enabled?: boolean }[], tokenMode: 'per-host' | 'shared' = 'per-host') {
  return parseRegistry({
    schemaVersion: 1,
    auth: { mode: 'oidc', issuer: 'https://idp.bank.example/realms/operations', clientId: 'horus-cockpit', tokenMode },
    hosts: hosts.map((host) => ({ label: host.id, baseUrl: `https://${host.id}.api.bank.example`, ...host })),
  });
}

function ok(result: ReturnType<typeof registry>): HostRegistry {
  if (!result.ok) throw new Error('fixture');
  return result.registry;
}

describe('version skew (CKP-014, TR-UI-08)', () => {
  it('CKP_014_cockpit_0_3_supports_engines_0_2_and_0_3_only', () => {
    expect(supportedLines('0.3.0')).toEqual(['0.2', '0.3']);
    expect(supportedLines('1.0.0')).toEqual(['1.0']);
    expect(supportedLines('1.4.2')).toEqual(['1.3', '1.4']);
    expect(checkVersion(meta('0.3.1'), '0.3.0')).toEqual({ supported: true });
    expect(checkVersion(meta('0.2.4'), '0.3.0')).toEqual({ supported: true });
    expect(checkVersion(meta('0.1.9'), '0.3.0')).toEqual({ supported: false, engineVersion: '0.1.9', lines: ['0.2', '0.3'] });
    expect(checkVersion(meta('0.4.0'), '0.3.0')).toMatchObject({ supported: false });
  });

  it('CKP_014_a_host_is_unsupported_when_any_of_its_engines_is_off_the_supported_lines', () => {
    expect(checkVersion(meta('0.3.0', '0.1.2'), '0.3.0-SNAPSHOT')).toEqual({ supported: false, engineVersion: '0.1.2', lines: ['0.2', '0.3'] });
  });
});

describe('host in the address (CKP-015)', () => {
  it('CKP_015_the_host_prefix_is_split_from_the_route_and_put_back', () => {
    expect(splitHost('#/hosts/loans/rules/card-fee/2')).toEqual({ hostId: 'loans', rest: '#/rules/card-fee/2' });
    expect(splitHost('#/hosts/kyc')).toEqual({ hostId: 'kyc', rest: '#/' });
    expect(splitHost('#/rules')).toEqual({ hostId: undefined, rest: '#/rules' });
    expect(withHost('#/rules', 'kyc')).toBe('#/hosts/kyc/rules');
    expect(withHost('#/', 'kyc')).toBe('#/hosts/kyc');
    expect(parseRoute('#/hosts/loans/rules')).toEqual({ name: 'rule-sets' });
  });

  it('CKP_015_switching_host_keeps_the_area_and_drops_the_object', () => {
    expect(hostSwitchHref('#/hosts/loans/rules/card-fee/2/table', 'kyc')).toBe('#/hosts/kyc/rules');
    expect(hostSwitchHref('#/hosts/loans/decisions/01JD2X3Y4Z5A6B7C8D9E0F1G2H', 'kyc')).toBe('#/hosts/kyc/decisions');
    expect(hostSwitchHref('#/hosts/loans', 'kyc')).toBe('#/hosts/kyc/home');
  });

  it('CKP_015_the_address_names_the_host_otherwise_the_first_enabled_one', () => {
    const two = ok(registry([{ id: 'loans', scope: 'loans-bpm', enabled: false }, { id: 'kyc', scope: 'kyc-bpm' }, { id: 'cards', scope: 'cards-bpm' }]));
    expect(chooseHost(two, '#/hosts/cards/rules')).toMatchObject({ kind: 'host', host: { id: 'cards' } });
    expect(chooseHost(two, '#/rules')).toMatchObject({ kind: 'host', host: { id: 'kyc' } });
    // A disabled host is never called, even when the address names it
    expect(chooseHost(two, '#/hosts/loans/rules')).toMatchObject({ kind: 'host', host: { id: 'kyc' } });
  });

  it('CKP_041_a_decision_link_names_its_host_or_asks_which_one_when_several_are_registered', () => {
    const two = ok(registry([{ id: 'loans', scope: 'loans-bpm' }, { id: 'cards', scope: 'cards-bpm' }]));
    const one = ok(registry([{ id: 'loans', scope: 'loans-bpm' }]));
    const id = '01JD2X3Y4Z5A6B7C8D9E0F1G2H';
    expect(linkedHost(`#/decisions/${id}?host=cards`)).toBe('cards');
    expect(linkedHost(`#/decisions/${id}?host=<b>`)).toBeUndefined();
    expect(chooseHost(two, `#/decisions/${id}?host=cards`)).toMatchObject({ kind: 'host', host: { id: 'cards' } });
    expect(chooseHost(two, `#/decisions/${id}`)).toEqual({ kind: 'choose' });
    expect(chooseHost(one, `#/decisions/${id}`)).toMatchObject({ kind: 'host', host: { id: 'loans' } });
  });
});

describe('registry for per-host tokens (CKP-012)', () => {
  it('CKP_012_per_host_mode_names_the_enabled_host_without_a_scope', () => {
    expect(registry([{ id: 'loans', scope: 'loans-bpm' }, { id: 'kyc' }])).toEqual({
      ok: false,
      problem: { reason: 'invalid', pointer: '/hosts/1/scope', message: 'Required with tokenMode per-host' },
    });
    expect(registry([{ id: 'loans', scope: 'loans-bpm' }, { id: 'kyc', enabled: false }]).ok).toBe(true);
    expect(registry([{ id: 'loans' }, { id: 'kyc' }], 'shared').ok).toBe(true);
  });
});
