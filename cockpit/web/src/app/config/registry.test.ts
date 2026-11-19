import { describe, expect, it } from 'vitest';
import { registryEmbedded } from '../../mocks/fixtures';
import { hostLabel, parseRegistry, selectHost } from './registry';

const base = { schemaVersion: 1, auth: { mode: 'host-session' } };

describe('host registry validation', () => {
  it('TR_UI_01_generated_one_host_registry_is_valid_and_selects_its_host', () => {
    const result = parseRegistry(registryEmbedded);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(selectHost(result.registry)?.id).toBe('loans');
    expect(result.registry.auth.tokenMode).toBe('per-host');
  });

  it('TR_UI_01_http_base_url_outside_localhost_is_rejected_at_its_json_pointer', () => {
    const result = parseRegistry({
      ...base,
      hosts: [
        { id: 'loans', label: 'Loans', baseUrl: 'https://loans.api.bank.example' },
        { id: 'kyc', label: 'KYC', baseUrl: 'http://kyc.bank.example' },
      ],
    });
    expect(result).toMatchObject({ ok: false, problem: { reason: 'invalid', pointer: '/hosts/1/baseUrl' } });
  });

  it('TR_UI_01_duplicate_host_id_is_an_error', () => {
    const result = parseRegistry({
      ...base,
      hosts: [
        { id: 'loans', label: 'Loans', baseUrl: 'https://a.bank.example' },
        { id: 'loans', label: 'Loans 2', baseUrl: 'https://b.bank.example' },
      ],
    });
    expect(result).toMatchObject({ ok: false, problem: { reason: 'duplicate-id', pointer: '/hosts/1/id', value: 'loans' } });
  });

  it('TR_UI_01_duplicate_base_url_is_an_error', () => {
    const result = parseRegistry({
      ...base,
      hosts: [
        { id: 'a', label: 'A', baseUrl: 'https://a.bank.example' },
        { id: 'b', label: 'B', baseUrl: 'https://a.bank.example' },
      ],
    });
    expect(result).toMatchObject({ ok: false, problem: { reason: 'duplicate-base-url', pointer: '/hosts/1/baseUrl' } });
  });

  it('TR_UI_01_label_map_without_english_is_an_error', () => {
    const result = parseRegistry({ ...base, hosts: [{ id: 'a', label: { vi: 'Khoản vay' }, baseUrl: '' }] });
    expect(result).toMatchObject({ ok: false, problem: { reason: 'invalid', pointer: '/hosts/0/label' } });
  });

  it('TR_UI_01_oidc_mode_requires_issuer_and_client_id', () => {
    const result = parseRegistry({ schemaVersion: 1, auth: { mode: 'oidc' }, hosts: [{ id: 'a', label: 'A', baseUrl: '' }] });
    expect(result).toMatchObject({ ok: false, problem: { reason: 'invalid', pointer: '/auth/issuer' } });
  });

  it('TR_UI_01_unknown_top_level_key_is_rejected', () => {
    const result = parseRegistry({ ...registryEmbedded, extra: true });
    expect(result).toMatchObject({ ok: false, problem: { reason: 'invalid', pointer: '/extra' } });
  });

  it('TR_UI_01_all_hosts_disabled_is_an_error', () => {
    const result = parseRegistry({ ...base, hosts: [{ id: 'a', label: 'A', baseUrl: '', enabled: false }] });
    expect(result).toMatchObject({ ok: false, problem: { reason: 'no-enabled-host' } });
  });

  it('TR_UI_04_host_label_follows_the_language_and_falls_back_to_english', () => {
    expect(hostLabel({ en: 'Loans', vi: 'Khoản vay' }, 'vi')).toBe('Khoản vay');
    expect(hostLabel({ en: 'Loans' }, 'vi')).toBe('Loans');
    expect(hostLabel('Loans', 'vi')).toBe('Loans');
  });
});
