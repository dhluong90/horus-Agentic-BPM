import { labelOf } from './ruleLabels';
import { demoSeed, type StoredVersion } from './ruleEngine';

/*
 * Demo data for reviewing changes, on top of the base demo data. Two requests by Hoa that Minh can approve:
 * - domestic-transfer-fee v2: a 20-row tariff with one fee changed, one row added, one removed and the last row
 *   changed, so the diff is taller than a laptop screen and the docked decision has to stay in view;
 * - card-cashback v2: the hit policy goes from first to unique, a cap column is added, and GOLD in R2 is written
 *   with a Cyrillic capital O (U+041E), which reads like GOLD and never matches it. It is scheduled for a date.
 */

type Definition = StoredVersion['definition'];
type Rule = Definition['rules'][number];

const CHANNELS = ['BRANCH', 'ONLINE', 'MOBILE'] as const;
const BANDS = ['< 2000000 VND', '< 10000000 VND', '< 50000000 VND', '< 200000000 VND', '< 500000000 VND', '>= 500000000 VND'] as const;
const FEES: Record<(typeof CHANNELS)[number], readonly number[]> = {
  BRANCH: [11000, 16500, 22000, 33000, 55000, 110000],
  ONLINE: [0, 0, 5500, 11000, 22000, 44000],
  MOBILE: [0, 0, 0, 5500, 11000, 22000],
};

function transferV1(): Definition {
  const rules: Rule[] = CHANNELS.flatMap((channel, c) =>
    BANDS.map((band, b) => ({ id: `R${String(c * BANDS.length + b + 1)}`, when: { channel: `== '${channel}'`, amount: band }, then: { fee: `${String(FEES[channel][b])} VND` } })),
  );
  rules.push({ id: 'R19', when: { channel: "in ['ONLINE', 'MOBILE']" }, then: { fee: '5500 VND' } });
  rules.push({ id: 'R20', when: {}, then: { fee: '11000 VND' } });
  return {
    schemaVersion: 1, ruleSet: 'domestic-transfer-fee', description: 'Domestic transfer fee by channel and amount', hitPolicy: 'first',
    inputs: [{ name: 'channel', label: labelOf('channel'), type: 'string', allowed: [...CHANNELS] }, { name: 'amount', label: labelOf('amount'), type: 'money' }],
    outputs: [{ name: 'fee', label: labelOf('fee'), type: 'money' }],
    rules,
  };
}

function transferV2(v1: Definition): Definition {
  const rules = v1.rules
    .filter((rule) => rule.id !== 'R12')
    .map((rule): Rule => (rule.id === 'R9' ? { ...rule, then: { fee: '3300 VND' } } : rule.id === 'R20' ? { ...rule, then: { fee: '12000 VND' } } : rule));
  const at = rules.findIndex((rule) => rule.id === 'R16');
  rules.splice(at, 0, { id: 'R21', when: { channel: "== 'MOBILE'", amount: '< 100000000 VND' }, then: { fee: '2200 VND' } });
  return { ...v1, rules };
}

const CASHBACK_V1: Definition = {
  schemaVersion: 1, ruleSet: 'card-cashback', description: 'Monthly card cashback rate by tier and spend', hitPolicy: 'first',
  inputs: [{ name: 'tier', label: labelOf('tier'), type: 'string', allowed: ['CLASSIC', 'GOLD', 'PLATINUM'] }, { name: 'monthlySpend', label: labelOf('monthlySpend'), type: 'money' }],
  outputs: [{ name: 'rate', label: labelOf('rate'), type: 'decimal' }],
  rules: [
    { id: 'R1', when: { tier: "== 'PLATINUM'", monthlySpend: '>= 20000000 VND' }, then: { rate: '2' } },
    { id: 'R2', when: { tier: "== 'GOLD'", monthlySpend: '>= 10000000 VND' }, then: { rate: '1.5' } },
    { id: 'R3', when: { tier: "== 'CLASSIC'" }, then: { rate: '0.5' } },
    { id: 'R4', when: {}, then: { rate: '0' } },
  ],
};

const CASHBACK_V2: Definition = {
  ...CASHBACK_V1,
  hitPolicy: 'unique',
  outputs: [{ name: 'rate', label: labelOf('rate'), type: 'decimal' }, { name: 'cap', label: labelOf('cap'), type: 'money' }],
  rules: [
    { id: 'R1', when: { tier: "== 'PLATINUM'", monthlySpend: '>= 20000000 VND' }, then: { rate: '2', cap: '1000000 VND' } },
    // "GОLD" below holds U+041E CYRILLIC CAPITAL LETTER O.
    { id: 'R2', when: { tier: "== 'GОLD'", monthlySpend: '>= 10000000 VND' }, then: { rate: '1.5', cap: '500000 VND' } },
    { id: 'R3', when: { tier: "== 'CLASSIC'" }, then: { rate: '0.5', cap: '200000 VND' } },
  ],
};

function version(definition: Definition, fields: Pick<StoredVersion, 'version' | 'status' | 'lockVersion' | 'createdBy' | 'createdAt' | 'requestedBy' | 'requestedAt' | 'approvedBy' | 'approvedAt' | 'effectiveFrom'>): StoredVersion {
  return { ...fields, ruleSet: definition.ruleSet, definition, source: JSON.stringify(definition, null, 2) };
}

const publishedOn = (definition: Definition, day: string): StoredVersion =>
  version(definition, { version: 1, status: 'PUBLISHED', lockVersion: 2, createdBy: 'lan', createdAt: `${day}T01:00:00Z`, requestedBy: 'lan', requestedAt: `${day}T02:00:00Z`, approvedBy: 'minh', approvedAt: `${day}T03:00:00Z`, effectiveFrom: `${day}T03:00:00Z` });

const requestedByHoa = (definition: Definition, effectiveFrom: string | null): StoredVersion =>
  version(definition, { version: 2, status: 'PENDING_APPROVAL', lockVersion: 1, createdBy: 'hoa', createdAt: '2026-11-19T01:10:00Z', requestedBy: 'hoa', requestedAt: '2026-11-19T01:40:00Z', approvedBy: null, approvedAt: null, effectiveFrom });

export function reviewDemo(): StoredVersion[] {
  const transfer = transferV1();
  return [
    ...demoSeed(),
    publishedOn(transfer, '2026-10-01'),
    requestedByHoa(transferV2(transfer), null),
    publishedOn(CASHBACK_V1, '2026-09-15'),
    // 1 Jan 2027, 00:00 in Ho Chi Minh City.
    requestedByHoa(CASHBACK_V2, '2026-12-31T17:00:00Z'),
  ];
}
