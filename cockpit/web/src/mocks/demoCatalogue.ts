import { demoSeed, type StoredVersion } from './ruleEngine';

/*
 * A fuller demo catalogue for screenshots and the dev server: the base demo data plus the rule sets a retail
 * bank's pricing and onboarding teams keep. It adds a request by another author (an approver reviews it) and a
 * request by the approver themselves (four eyes: no Approve on their own). Tests that count rows use demoSeed().
 */

type Definition = StoredVersion['definition'];

function feeTable(ruleSet: string, description: string, input: { name: string; type: string; allowed?: string[] }, rows: [string, string][]): Definition {
  return {
    schemaVersion: 1,
    ruleSet,
    description,
    hitPolicy: 'first',
    inputs: [input],
    outputs: [{ name: 'fee', type: 'money' }],
    rules: rows.map(([when, fee], index) => ({ id: `R${String(index + 1)}`, when: when === '' ? {} : { [input.name]: when }, then: { fee } })),
  };
}

const segment = { name: 'segment', type: 'string', allowed: ['MASS', 'AFFLUENT', 'PRIVATE'] };

function version(definition: Definition, fields: Omit<StoredVersion, 'ruleSet' | 'source' | 'definition' | 'effectiveFrom'> & { effectiveFrom?: string | null }): StoredVersion {
  return { effectiveFrom: null, ...fields, ruleSet: definition.ruleSet, definition, source: JSON.stringify(definition, null, 2) };
}

const published = (definition: Definition, v: number, day: string): StoredVersion =>
  version(definition, { version: v, status: 'PUBLISHED', lockVersion: 2, createdBy: 'lan', createdAt: `${day}T01:00:00Z`, requestedBy: 'lan', requestedAt: `${day}T02:00:00Z`, approvedBy: 'minh', approvedAt: `${day}T03:00:00Z`, effectiveFrom: `${day}T03:00:00Z` });

export function demoCatalogue(): StoredVersion[] {
  const fx = feeTable('fx-margin', 'FX margin by customer segment', segment, [["== 'PRIVATE'", '0 VND'], ["== 'AFFLUENT'", '20000 VND'], ['', '40000 VND']]);
  const atm = feeTable('atm-withdrawal-fee', 'Off-network ATM withdrawal fee', segment, [["== 'PRIVATE'", '0 VND'], ['', '3300 VND']]);
  const overdraft = feeTable('overdraft-fee', 'Monthly overdraft facility fee', segment, [["== 'PRIVATE'", '0 VND'], ['', '50000 VND']]);
  const wire = feeTable('wire-transfer-fee', 'Outgoing interbank transfer fee', segment, [["== 'MASS'", '11000 VND'], ['', '5500 VND']]);
  const loan = feeTable('loan-pricing', 'Consumer loan arrangement fee', segment, [["== 'AFFLUENT'", '500000 VND'], ['', '1000000 VND']]);
  return [
    ...demoSeed(),
    published(fx, 1, '2026-10-12'),
    version(fx, { version: 2, status: 'PENDING_APPROVAL', lockVersion: 1, createdBy: 'hoa', createdAt: '2026-11-19T01:10:00Z', requestedBy: 'hoa', requestedAt: '2026-11-19T01:40:00Z', approvedBy: null, approvedAt: null }),
    published(atm, 1, '2026-09-30'),
    version(atm, { version: 2, status: 'PENDING_APPROVAL', lockVersion: 1, createdBy: 'minh', createdAt: '2026-11-17T06:00:00Z', requestedBy: 'minh', requestedAt: '2026-11-17T07:15:00Z', approvedBy: null, approvedAt: null }),
    published(overdraft, 1, '2026-08-20'),
    version(overdraft, { version: 2, status: 'DRAFT', lockVersion: 0, createdBy: 'lan', createdAt: '2026-11-16T03:20:00Z', requestedBy: null, requestedAt: null, approvedBy: null, approvedAt: null }),
    published(wire, 1, '2026-11-05'),
    published(loan, 1, '2026-10-28'),
  ];
}

/** Customer screening at onboarding: inputs that hold personal data, so the masking guards have something to show. */
function screening(inputs: Definition['inputs']): Definition {
  return {
    schemaVersion: 1,
    ruleSet: 'customer-screening',
    description: 'Onboarding screening by segment',
    hitPolicy: 'first',
    inputs,
    outputs: [{ name: 'check', type: 'string' }],
    rules: [
      { id: 'S1', when: { segment: "== 'PRIVATE'" }, then: { check: "'ENHANCED'" } },
      { id: 'S2', when: {}, then: { check: "'STANDARD'" } },
    ],
  };
}

/**
 * The personal-data demo (RLE-049, RLE-051): customer-screening v1 masks the national ID and the phone number;
 * v2, sent by another author, drops the national ID's mask and adds an email column without one. An approver
 * sees two warnings beside Approve and "Masked → Not masked by default" in the diff.
 */
export function personalDataDemo(): StoredVersion[] {
  const v1 = screening([
    { name: 'nationalId', type: 'string', mask: true, description: 'National ID' },
    { name: 'phoneNumber', type: 'string', mask: true, description: 'Phone number' },
    segment,
  ]);
  const v2 = screening([
    { name: 'nationalId', type: 'string', description: 'National ID' },
    { name: 'phoneNumber', type: 'string', mask: true, description: 'Phone number' },
    { name: 'email', type: 'string', description: 'Email' },
    segment,
  ]);
  return [
    ...demoSeed(),
    published(v1, 1, '2026-10-02'),
    version(v2, { version: 2, status: 'PENDING_APPROVAL', lockVersion: 1, createdBy: 'hoa', createdAt: '2026-11-19T02:00:00Z', requestedBy: 'hoa', requestedAt: '2026-11-19T02:20:00Z', approvedBy: null, approvedAt: null }),
  ];
}
