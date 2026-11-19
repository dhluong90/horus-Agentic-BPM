import { Ajv2020 } from 'ajv/dist/2020.js';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { meLan, meMinh, metaRulesOnly, registryEmbedded } from '../mocks/fixtures';
import { bpmMeV1 } from './generated/bpm-me.v1';
import { bpmMetaV1 } from './generated/bpm-meta.v1';
import { cockpitHostsV1 } from './generated/cockpit-hosts.v1';

// Vitest runs from cockpit/web; the schemas live in cockpit/api/schema.
const schemaDir = join(process.cwd(), '..', 'api', 'schema');
const ajv = new Ajv2020({ allErrors: true, strict: false });
const jsonSchema = (file: string) => ajv.compile(JSON.parse(readFileSync(join(schemaDir, file), 'utf8')) as object);

const meSchema = jsonSchema('bpm-me.v1.schema.json');
const metaSchema = jsonSchema('bpm-meta.v1.schema.json');
const hostsSchema = jsonSchema('cockpit-hosts.v1.schema.json');

const meCases: Array<[string, unknown]> = [
  ['lan', meLan],
  ['minh', meMinh],
  ['no tenant', { schemaVersion: 1, user: { id: 'lan' }, permissions: [] }],
  ['empty user id', { ...meLan, user: { id: '' } }],
  ['unknown permission module', { ...meLan, permissions: ['loans:read'] }],
  ['duplicate permission', { ...meLan, permissions: ['rules:read', 'rules:read'] }],
  ['schema version 2', { ...meLan, schemaVersion: 2 }],
  ['extra field kept', { ...meLan, locale: 'vi' }],
];

const metaCases: Array<[string, unknown]> = [
  ['rules only', metaRulesOnly],
  ['no modules', { ...metaRulesOnly, modules: [] }],
  ['bad engine version', { ...metaRulesOnly, modules: [{ ...metaRulesOnly.modules[0], engineVersion: '0.1' }] }],
  ['page size above 200', { ...metaRulesOnly, limits: { maxPageSize: 500 } }],
  ['unknown module', { ...metaRulesOnly, modules: [{ ...metaRulesOnly.modules[0], module: 'loans' }] }],
  ['snapshot version', { ...metaRulesOnly, modules: [{ ...metaRulesOnly.modules[0], engineVersion: '0.1.0-SNAPSHOT' }] }],
];

const hostCases: Array<[string, unknown]> = [
  ['embedded', registryEmbedded],
  ['https host', { schemaVersion: 1, auth: { mode: 'host-session' }, hosts: [{ id: 'kyc', label: 'KYC', baseUrl: 'https://kyc.bank.example' }] }],
  ['http outside localhost', { schemaVersion: 1, auth: { mode: 'host-session' }, hosts: [{ id: 'kyc', label: 'KYC', baseUrl: 'http://kyc.bank.example' }] }],
  ['trailing slash', { schemaVersion: 1, auth: { mode: 'host-session' }, hosts: [{ id: 'kyc', label: 'KYC', baseUrl: 'https://kyc.bank.example/' }] }],
  ['extra key', { ...registryEmbedded, extra: 1 }],
  ['no hosts', { ...registryEmbedded, hosts: [] }],
];

describe('generated zod schemas agree with the published JSON Schemas', () => {
  it.each(meCases)('CKP_001_contract_bpm_me_%s', (_name, body) => {
    expect(bpmMeV1.safeParse(body).success).toBe(meSchema(body));
  });

  it.each(metaCases)('CKP_001_contract_bpm_meta_%s', (_name, body) => {
    expect(bpmMetaV1.safeParse(body).success).toBe(metaSchema(body));
  });

  it.each(hostCases)('CKP_001_contract_cockpit_hosts_%s', (_name, body) => {
    expect(cockpitHostsV1.safeParse(body).success).toBe(hostsSchema(body));
  });

  it('CKP_001_contract_mock_host_fixtures_are_valid', () => {
    expect(meSchema(meLan)).toBe(true);
    expect(metaSchema(metaRulesOnly)).toBe(true);
    expect(hostsSchema(registryEmbedded)).toBe(true);
  });
});
