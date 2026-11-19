/*
 * An in-memory stand-in for the Rule Engine REST API (rule-engine/api/openapi.yaml 0.1.0), for the dev
 * server, Vitest (through MSW) and Playwright (through page.route). It answers like the real engine on the
 * paths the cockpit uses, including the problems the screens must show: 422 VALIDATION_FAILED naming the row
 * and input, 403 SELF_APPROVAL_FORBIDDEN, 409 VERSION_CONFLICT, 404 VERSION_NOT_FOUND. It accepts JSON text
 * only (JSON is YAML), which is what the cockpit's editor sends.
 */

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
interface Rule { id: string; when: Record<string, string>; then: Record<string, Json> }
interface Definition { schemaVersion: number; ruleSet: string; hitPolicy: string; inputs: { name: string; type: string; allowed?: string[] }[]; outputs: { name: string; type: string }[]; rules: Rule[]; description?: string }

export interface StoredVersion {
  ruleSet: string;
  version: number;
  status: 'DRAFT' | 'PENDING_APPROVAL' | 'PUBLISHED' | 'RETIRED';
  lockVersion: number;
  createdBy: string;
  createdAt: string;
  requestedBy: string | null;
  requestedAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  effectiveFrom: string | null;
  source: string;
  definition: Definition;
}

export interface FakeRequest { method: string; path: string; headers: Record<string, string>; body: string; user: string }
export interface FakeResponse { status: number; headers: Record<string, string>; body: string }

const TYPE = 'https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#';
let sequence = 0;
const correlation = () => `01JFAKE${String((sequence += 1)).padStart(19, '0')}`;
const hash = (text: string) => Array.from({ length: 64 }, (_, i) => ((text.charCodeAt(i % Math.max(text.length, 1)) || 7) + i) % 16).map((n) => n.toString(16)).join('');

function problem(status: number, code: string, detail: string): FakeResponse {
  const titles: Record<number, string> = { 400: 'Bad Request', 403: 'Forbidden', 404: 'Not Found', 409: 'Conflict', 422: 'Unprocessable Content' };
  return {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
    body: JSON.stringify({ type: TYPE + code.toLowerCase(), title: titles[status] ?? 'Error', status, code, detail, correlationId: correlation() }),
  };
}

function view(v: StoredVersion, withSource = false): FakeResponse {
  const body = {
    ruleSet: v.ruleSet, version: v.version, status: v.status, hitPolicy: v.definition.hitPolicy.toUpperCase(), effectiveFrom: v.effectiveFrom,
    lockVersion: v.lockVersion, canonicalHash: hash(JSON.stringify(v.definition)), sourceFormat: 'YAML', sourceHash: hash(v.source),
    ...(withSource ? { sourceDocument: v.source } : {}),
    requestedBy: v.requestedBy, requestedAt: v.requestedAt, approvedBy: v.approvedBy, approvedAt: v.approvedAt,
    createdBy: v.createdBy, createdAt: v.createdAt, definition: v.definition,
  };
  return { status: 200, headers: { 'Content-Type': 'application/json', ETag: `"${v.lockVersion}"` }, body: JSON.stringify(body) };
}

/** The engine's static checks that the screens show, in the engine's words. */
function validate(definition: Definition): string | undefined {
  const types = new Map(definition.inputs.map((input) => [input.name, input.type]));
  for (const rule of definition.rules) {
    for (const [input, condition] of Object.entries(rule.when)) {
      if (!types.has(input)) return `Rule ${rule.id}: unknown input '${input}'`;
      const bare = /^(==|!=)\s*([A-Za-z_]\w*)$/.exec(condition);
      if (bare && bare[2] !== 'true' && bare[2] !== 'false' && bare[2] !== 'null') return `Rule ${rule.id}, input ${input}: Unknown input '${bare[2] ?? ''}' at position ${String(condition.indexOf(bare[2] ?? '') + 1)}`;
      if (types.get(input) === 'money' && /^(>=|<=|>|<|==|!=)\s*-?\d+(\.\d+)?$/.test(condition.trim())) return `Rule ${rule.id}, input ${input}: compares a money with a number`;
    }
  }
  return undefined;
}

function parseDocument(text: string): Definition | string {
  try {
    const parsed = JSON.parse(text) as Definition;
    if (typeof parsed !== 'object' || !Array.isArray(parsed.rules)) return 'Document must be a mapping';
    return parsed;
  } catch {
    return 'Document is not well-formed YAML: the fake engine reads JSON text only';
  }
}

function moneyMinor(text: string): { currency: string; amount: number } | undefined {
  const match = /^(-?\d+)\s+([A-Z]{3})$/.exec(text.trim());
  return match ? { currency: match[2] ?? '', amount: Number(match[1]) } : undefined;
}

function matches(condition: string, value: Json | undefined): boolean {
  const text = condition.trim();
  const literal = (raw: string): Json => {
    const t = raw.trim();
    if (/^'.*'$/.test(t)) return t.slice(1, -1);
    if (/^-?\d+$/.test(t)) return Number(t);
    return moneyMinor(t)?.amount ?? t;
  };
  const scalar = typeof value === 'object' && value !== null && !Array.isArray(value) ? (value['amount'] as number) : value;
  const eq = /^==\s*(.+)$/.exec(text);
  if (eq) return scalar === literal(eq[1] ?? '');
  const list = /^in\s*\[(.*)\]$/.exec(text);
  if (list) return (list[1] ?? '').split(',').map(literal).includes(scalar ?? null);
  const cmp = /^(>=|<=|>|<)\s*(.+)$/.exec(text);
  if (cmp && typeof scalar === 'number') {
    const bound = literal(cmp[2] ?? '') as number;
    return cmp[1] === '>=' ? scalar >= bound : cmp[1] === '<=' ? scalar <= bound : cmp[1] === '>' ? scalar > bound : scalar < bound;
  }
  return false;
}

export function createFakeEngine(seed: StoredVersion[]) {
  const store: StoredVersion[] = seed.map((v) => structuredClone(v));
  const now = () => new Date('2026-11-19T03:00:00Z').toISOString();
  const find = (name: string, version: number) => store.find((v) => v.ruleSet === name && v.version === version);
  const versionsOf = (name: string) => store.filter((v) => v.ruleSet === name);

  function importVersion(name: string | undefined, request: FakeRequest): FakeResponse {
    const parsed = parseDocument(request.body);
    if (typeof parsed === 'string') return problem(422, 'VALIDATION_FAILED', parsed);
    if (name !== undefined && parsed.ruleSet !== name) return problem(400, 'INPUT_INVALID', `Document ruleSet ${parsed.ruleSet} differs from ${name}`);
    if (name === undefined && versionsOf(parsed.ruleSet).length > 0) return problem(409, 'RULE_SET_EXISTS', `Rule set ${parsed.ruleSet} exists`);
    if (name !== undefined && versionsOf(name).length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
    const invalid = validate(parsed);
    if (invalid) return problem(422, 'VALIDATION_FAILED', invalid);
    const stored: StoredVersion = {
      ruleSet: parsed.ruleSet, version: versionsOf(parsed.ruleSet).length + 1, status: 'DRAFT', lockVersion: 0, createdBy: request.user, createdAt: now(),
      requestedBy: null, requestedAt: null, approvedBy: null, approvedAt: null, effectiveFrom: null, source: request.body, definition: parsed,
    };
    store.push(stored);
    const answer = view(stored);
    return { ...answer, status: 201, headers: { ...answer.headers, Location: `/api/bpm/rules/v1/rule-sets/${stored.ruleSet}/versions/${String(stored.version)}` } };
  }

  function transition(v: StoredVersion, request: FakeRequest, action: 'request-publish' | 'approve'): FakeResponse {
    const ifMatch = request.headers['if-match'];
    if (ifMatch !== undefined && ifMatch !== `"${String(v.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT', `Expected lock version ${ifMatch.replaceAll('"', '')} but ${v.ruleSet} v${String(v.version)} is at ${String(v.lockVersion)}`);
    if (action === 'request-publish') {
      if (v.status !== 'DRAFT') return problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`);
      Object.assign(v, { status: 'PENDING_APPROVAL', requestedBy: request.user, requestedAt: now(), lockVersion: v.lockVersion + 1 });
      return view(v);
    }
    if (v.status !== 'PENDING_APPROVAL') return problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`);
    if (v.requestedBy === request.user || v.createdBy === request.user) return problem(403, 'SELF_APPROVAL_FORBIDDEN', `The requester or author of ${v.ruleSet} v${String(v.version)} cannot approve it`);
    for (const other of versionsOf(v.ruleSet)) if (other.status === 'PUBLISHED') other.status = 'RETIRED';
    Object.assign(v, { status: 'PUBLISHED', approvedBy: request.user, approvedAt: now(), effectiveFrom: now(), lockVersion: v.lockVersion + 1 });
    return view(v);
  }

  function evaluate(name: string, request: FakeRequest): FakeResponse {
    const effective = [...versionsOf(name)].reverse().find((v) => v.status === 'PUBLISHED');
    if (!effective) return problem(404, 'NO_EFFECTIVE_VERSION', `No version of ${name} is effective`);
    const inputs = (JSON.parse(request.body) as { inputs: Record<string, Json> }).inputs;
    const rule = effective.definition.rules.find((r) => Object.entries(r.when).every(([input, condition]) => matches(condition, inputs[input])));
    const outputs: Record<string, Json> = {};
    for (const [key, value] of Object.entries(rule?.then ?? {})) outputs[key] = typeof value === 'string' ? (moneyMinor(value) ?? value) : value;
    return {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decisionId: correlation(), ruleSet: name, version: effective.version, evaluatedAt: now(), outcome: rule ? 'MATCHED' : 'NO_MATCH', matchedRuleIds: rule ? [rule.id] : [], outputs }),
    };
  }

  function handle(request: FakeRequest): FakeResponse {
    const url = new URL(request.path, 'http://host');
    const path = decodeURIComponent(url.pathname).replace(/^.*\/api\/bpm\/rules\/v1/, '');
    let m: RegExpExecArray | null;
    if (request.method === 'POST' && path === '/rule-sets') return importVersion(undefined, request);
    if (request.method === 'POST' && (m = /^\/rule-sets\/([^/]+)\/versions$/.exec(path))) return importVersion(m[1], request);
    if ((m = /^\/rule-sets\/([^/]+)\/versions\/(\d+)(?::(request-publish|approve))?$/.exec(path))) {
      const v = find(m[1] ?? '', Number(m[2]));
      if (!v) return problem(404, 'VERSION_NOT_FOUND', `Version ${m[2] ?? ''} of rule set ${m[1] ?? ''} not found`);
      if (request.method === 'GET') return view(v, url.searchParams.get('include') === 'source');
      if (m[3] === 'request-publish' || m[3] === 'approve') return transition(v, request, m[3]);
    }
    if (request.method === 'POST' && (m = /^\/decisions\/([^/:]+):evaluate$/.exec(path))) return evaluate(m[1] ?? '', request);
    return problem(404, 'VERSION_NOT_FOUND', `No route ${request.method} ${path}`);
  }

  return { handle, store };
}

const CARD_FEE_V1: Definition = {
  schemaVersion: 1, ruleSet: 'card-fee', description: 'Annual card fee by tier and balance', hitPolicy: 'first',
  inputs: [{ name: 'tier', type: 'string', allowed: ['CLASSIC', 'GOLD', 'PLATINUM'] }, { name: 'balance', type: 'money' }, { name: 'txCount', type: 'integer' }],
  outputs: [{ name: 'fee', type: 'money' }, { name: 'waived', type: 'boolean' }],
  rules: [
    { id: 'R1', when: { tier: "== 'PLATINUM'" }, then: { fee: '0 VND', waived: true } },
    { id: 'R2', when: { tier: "== 'GOLD'", balance: '>= 50000000 VND' }, then: { fee: '0 VND', waived: true } },
    { id: 'R3', when: { tier: "== 'GOLD'" }, then: { fee: '30000 VND', waived: false } },
    { id: 'R4', when: { txCount: '>= 20' }, then: { fee: '15000 VND', waived: false } },
    { id: 'R5', when: {}, then: { fee: '50000 VND', waived: false } },
  ],
};

const CARD_FEE_V2: Definition = {
  ...CARD_FEE_V1,
  rules: [
    CARD_FEE_V1.rules[0] as Rule,
    { id: 'R2', when: { tier: "in ['GOLD', 'PLATINUM']", balance: '>= 50000000 VND' }, then: { fee: '0 VND', waived: true } },
    { id: 'R6', when: { tier: "== 'GOLD'", balance: '>= 20000000 VND' }, then: { fee: '10000 VND', waived: false } },
    { id: 'R3', when: { tier: "== 'GOLD'" }, then: { fee: '35000 VND', waived: false } },
    CARD_FEE_V1.rules[3] as Rule,
    CARD_FEE_V1.rules[4] as Rule,
  ],
};

const KYC_RISK_V1: Definition = {
  schemaVersion: 1, ruleSet: 'kyc-risk', hitPolicy: 'first',
  inputs: [{ name: 'country', type: 'string' }, { name: 'monthlyIncome', type: 'money' }, { name: 'pep', type: 'boolean' }],
  outputs: [{ name: 'risk', type: 'string' }],
  rules: [
    { id: 'K1', when: { pep: '== true' }, then: { risk: "'HIGH'" } },
    { id: 'K2', when: { country: "in ['VN', 'SG']", monthlyIncome: '>= 200000000 VND' }, then: { risk: "'MEDIUM'" } },
    { id: 'K3', when: {}, then: { risk: "'LOW'" } },
  ],
};

const base = { lockVersion: 2, requestedAt: '2026-11-01T02:00:00Z', approvedAt: '2026-11-01T03:00:00Z', effectiveFrom: '2026-11-01T03:00:00Z' };

/** Sao Mai Bank's demo data: card-fee v1 published, v2 waiting for approval by Lan; kyc-risk v1 published. */
export function demoSeed(): StoredVersion[] {
  return [
    { ruleSet: 'card-fee', version: 1, status: 'PUBLISHED', createdBy: 'lan', createdAt: '2026-11-01T01:00:00Z', requestedBy: 'lan', approvedBy: 'minh', source: JSON.stringify(CARD_FEE_V1, null, 2), definition: CARD_FEE_V1, ...base },
    { ruleSet: 'card-fee', version: 2, status: 'PENDING_APPROVAL', lockVersion: 1, createdBy: 'lan', createdAt: '2026-11-18T08:00:00Z', requestedBy: 'lan', requestedAt: '2026-11-18T08:30:00Z', approvedBy: null, approvedAt: null, effectiveFrom: null, source: JSON.stringify(CARD_FEE_V2, null, 2), definition: CARD_FEE_V2 },
    { ruleSet: 'kyc-risk', version: 1, status: 'PUBLISHED', createdBy: 'lan', createdAt: '2026-10-20T01:00:00Z', requestedBy: 'lan', approvedBy: 'minh', source: JSON.stringify(KYC_RISK_V1, null, 2), definition: KYC_RISK_V1, ...base },
  ];
}
