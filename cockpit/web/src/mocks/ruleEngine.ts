/*
 * An in-memory stand-in for the Rule Engine REST API (rule-engine/api/openapi.yaml), for the dev server,
 * Vitest (through MSW) and Playwright (through page.route). It answers like the real engine on the paths the
 * cockpit uses: paged lists of rule sets and versions with a cursor, allowedActions computed for the caller,
 * history, reject with a reason, and the problems the screens must show: 422 VALIDATION_FAILED with
 * violations, 403 SELF_APPROVAL_FORBIDDEN, 409 VERSION_CONFLICT, 400 QUERY_INVALID, 413 PAYLOAD_TOO_LARGE.
 * It accepts JSON text only (JSON is YAML), which is what the cockpit's editor sends.
 */

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
interface Rule { id: string; when: Record<string, string>; then: Record<string, Json> }
interface Definition { schemaVersion: number; ruleSet: string; hitPolicy: string; inputs: { name: string; type: string; allowed?: string[] }[]; outputs: { name: string; type: string }[]; rules: Rule[]; description?: string }

export interface StoredHistory { action: 'CREATED' | 'REQUESTED' | 'APPROVED' | 'REJECTED'; fromStatus: string | null; toStatus: string; actor: string; reason: string | null; lockVersion: number; occurredAt: string }

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
  history?: StoredHistory[];
}

export interface FakeRequest { method: string; path: string; headers: Record<string, string>; body: string; user: string; permissions: readonly string[] }
export interface FakeResponse { status: number; headers: Record<string, string>; body: string }

const TYPE = 'https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#';
let sequence = 0;
const correlation = () => `01JFAKE${String((sequence += 1)).padStart(19, '0')}`;
const hash = (text: string) => Array.from({ length: 64 }, (_, i) => ((text.charCodeAt(i % Math.max(text.length, 1)) || 7) + i) % 16).map((n) => n.toString(16)).join('');

interface Violation { ruleId: string | null; column: string | null; message: string }

function problem(status: number, code: string, detail: string, violations?: Violation[]): FakeResponse {
  const titles: Record<number, string> = { 400: 'Bad Request', 403: 'Forbidden', 404: 'Not Found', 409: 'Conflict', 413: 'Content Too Large', 422: 'Unprocessable Content' };
  return {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
    body: JSON.stringify({ type: TYPE + code.toLowerCase(), title: titles[status] ?? 'Error', status, code, detail, correlationId: correlation(), ...(violations ? { violations } : {}) }),
  };
}

const json = (body: unknown, extra: Record<string, string> = {}): FakeResponse => ({ status: 200, headers: { 'Content-Type': 'application/json', ...extra }, body: JSON.stringify(body) });

/** allowedActions for this caller, by status, permission and four eyes (rule-engine VersionActions). */
function actionsFor(v: StoredVersion, request: FakeRequest): string[] {
  const can = (permission: string) => request.permissions.includes(permission);
  if (v.status === 'DRAFT') return can('rules:author') ? ['request-publish'] : [];
  if (v.status !== 'PENDING_APPROVAL' || !can('rules:approve')) return [];
  const self = v.requestedBy === request.user || v.createdBy === request.user;
  return self ? ['reject'] : ['approve', 'reject'];
}

function lifecycle(v: StoredVersion, request: FakeRequest) {
  return {
    ruleSet: v.ruleSet, version: v.version, status: v.status, hitPolicy: v.definition.hitPolicy.toUpperCase(), effectiveFrom: v.effectiveFrom,
    lockVersion: v.lockVersion, canonicalHash: hash(JSON.stringify(v.definition)),
    requestedBy: v.requestedBy, requestedAt: v.requestedAt, approvedBy: v.approvedBy, approvedAt: v.approvedAt,
    createdBy: v.createdBy, createdAt: v.createdAt, allowedActions: actionsFor(v, request),
  };
}

function view(v: StoredVersion, request: FakeRequest, withSource = false): FakeResponse {
  const body = { ...lifecycle(v, request), sourceFormat: 'YAML', sourceHash: hash(v.source), ...(withSource ? { sourceDocument: v.source } : {}), definition: v.definition };
  return json(body, { ETag: `"${String(v.lockVersion)}"` });
}

const MONEY_TEXT = /^-?\d+(\.\d+)?\s+[A-Z]{3}$/;

/** The engine's static checks that the screens show, every cell at once, in the engine's words. */
function validate(definition: Definition): Violation[] {
  const types = new Map(definition.inputs.map((input) => [input.name, input.type]));
  const outputs = new Map(definition.outputs.map((output) => [output.name, output.type]));
  const found: Violation[] = [];
  for (const rule of definition.rules) {
    for (const [input, condition] of Object.entries(rule.when)) {
      if (!types.has(input)) {
        found.push({ ruleId: rule.id, column: null, message: `unknown input '${input}'` });
        continue;
      }
      const bare = /^(==|!=)\s*([A-Za-z_]\w*)$/.exec(condition);
      if (bare && bare[2] !== 'true' && bare[2] !== 'false' && bare[2] !== 'null') found.push({ ruleId: rule.id, column: input, message: `Unknown input '${bare[2] ?? ''}' at position ${String(condition.indexOf(bare[2] ?? '') + 1)}` });
      else if (types.get(input) === 'money' && /^(>=|<=|>|<|==|!=)\s*-?\d+(\.\d+)?$/.test(condition.trim())) found.push({ ruleId: rule.id, column: input, message: 'compares a money with a number' });
    }
    for (const [output, value] of Object.entries(rule.then)) {
      if (!outputs.has(output)) found.push({ ruleId: rule.id, column: null, message: `unknown output '${output}'` });
      else if (outputs.get(output) === 'money' && (typeof value === 'number' || (typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value.trim())))) {
        found.push({ ruleId: rule.id, column: output, message: `output ${output} is money and needs a currency: write ${String(value)} followed by its ISO 4217 currency code` });
      } else if (outputs.get(output) === 'money' && typeof value === 'string' && !MONEY_TEXT.test(value.trim()) && /^\d/.test(value.trim())) {
        found.push({ ruleId: rule.id, column: output, message: `output ${output} is not a money amount` });
      }
    }
  }
  return found;
}

const COMMAND_LIMIT = 16_384;
const EVALUATE_LIMIT = 1_048_576;

type PageQuery = { ok: true; limit: number; after: string | undefined } | { ok: false; response: FakeResponse };

/** limit 1 to 200 (default 50) and an opaque cursor this engine issued: "<scope>:<last key>" in base64url. */
function pageQuery(url: URL, scope: string): PageQuery {
  const limitText = url.searchParams.get('limit');
  const limit = limitText === null ? 50 : Number(limitText);
  if (!Number.isInteger(limit) || limit < 1 || limit > 200) return { ok: false, response: problem(400, 'QUERY_INVALID', 'limit must be between 1 and 200') };
  const cursor = url.searchParams.get('cursor');
  if (cursor === null) return { ok: true, limit, after: undefined };
  let decoded: string;
  try {
    decoded = atob(cursor.replaceAll('-', '+').replaceAll('_', '/'));
  } catch {
    decoded = '';
  }
  if (!decoded.startsWith(`${scope}:`)) return { ok: false, response: problem(400, 'QUERY_INVALID', 'The cursor was not issued by this endpoint') };
  return { ok: true, limit, after: decoded.slice(scope.length + 1) };
}

const cursorOf = (scope: string, key: string) => btoa(`${scope}:${key}`).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');

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
    const violations = validate(parsed);
    const first = violations[0];
    if (first) {
      const where = first.ruleId === null ? '' : first.column === null ? `Rule ${first.ruleId}: ` : `Rule ${first.ruleId}, column ${first.column}: `;
      const more = violations.length > 1 ? ` (and ${String(violations.length - 1)} more)` : '';
      return problem(422, 'VALIDATION_FAILED', `${where}${first.message}${more}`, violations);
    }
    const stored: StoredVersion = {
      ruleSet: parsed.ruleSet, version: versionsOf(parsed.ruleSet).length + 1, status: 'DRAFT', lockVersion: 0, createdBy: request.user, createdAt: now(),
      requestedBy: null, requestedAt: null, approvedBy: null, approvedAt: null, effectiveFrom: null, source: request.body, definition: parsed,
      history: [{ action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: request.user, reason: null, lockVersion: 0, occurredAt: now() }],
    };
    store.push(stored);
    const answer = view(stored, request);
    return { ...answer, status: 201, headers: { ...answer.headers, Location: `/api/bpm/rules/v1/rule-sets/${stored.ruleSet}/versions/${String(stored.version)}` } };
  }

  function record(v: StoredVersion, action: StoredHistory['action'], from: string, actor: string, reason: string | null) {
    (v.history ??= []).push({ action, fromStatus: from, toStatus: v.status, actor, reason, lockVersion: v.lockVersion, occurredAt: now() });
  }

  function transition(v: StoredVersion, request: FakeRequest, action: 'request-publish' | 'approve' | 'reject'): FakeResponse {
    if (action !== 'request-publish' && request.body.length > COMMAND_LIMIT) return problem(413, 'PAYLOAD_TOO_LARGE', `The document is larger than ${String(COMMAND_LIMIT)} bytes`);
    const needs = action === 'request-publish' ? 'rules:author' : 'rules:approve';
    if (!request.permissions.includes(needs)) return problem(403, 'PERMISSION_DENIED', `The caller lacks permission ${needs}`);
    const ifMatch = request.headers['if-match'];
    if (ifMatch !== undefined && ifMatch !== `"${String(v.lockVersion)}"`) return problem(409, 'VERSION_CONFLICT', `Expected lock version ${ifMatch.replaceAll('"', '')} but ${v.ruleSet} v${String(v.version)} is at ${String(v.lockVersion)}`);
    const from = v.status;
    if (action === 'request-publish') {
      if (v.status !== 'DRAFT') return problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`);
      Object.assign(v, { status: 'PENDING_APPROVAL', requestedBy: request.user, requestedAt: now(), lockVersion: v.lockVersion + 1 });
      record(v, 'REQUESTED', from, request.user, null);
      return view(v, request);
    }
    if (v.status !== 'PENDING_APPROVAL') return problem(409, 'INVALID_STATE', `${v.ruleSet} v${String(v.version)} is ${v.status}`);
    const body = request.body === '' ? {} : (JSON.parse(request.body) as { comment?: string; reason?: string });
    if (action === 'reject') {
      const reason = (body.reason ?? '').trim();
      if (reason === '') return problem(400, 'INPUT_INVALID', 'reason is required and must not be blank');
      if (reason.length > 2000) return problem(400, 'INPUT_INVALID', 'reason is longer than 2000 characters');
      Object.assign(v, { status: 'DRAFT', requestedBy: null, requestedAt: null, lockVersion: v.lockVersion + 1 });
      record(v, 'REJECTED', from, request.user, reason);
      return view(v, request);
    }
    if (v.requestedBy === request.user || v.createdBy === request.user) return problem(403, 'SELF_APPROVAL_FORBIDDEN', `The requester or author of ${v.ruleSet} v${String(v.version)} cannot approve it`);
    for (const other of versionsOf(v.ruleSet)) if (other.status === 'PUBLISHED') other.status = 'RETIRED';
    Object.assign(v, { status: 'PUBLISHED', approvedBy: request.user, approvedAt: now(), effectiveFrom: now(), lockVersion: v.lockVersion + 1 });
    record(v, 'APPROVED', from, request.user, body.comment ?? null);
    return view(v, request);
  }

  function summary(name: string) {
    const versions = versionsOf(name);
    const latest = versions.reduce((a, b) => (b.version > a.version ? b : a));
    const published = [...versions].reverse().find((v) => v.status === 'PUBLISHED');
    const times = versions.flatMap((v) => [v.createdAt, v.requestedAt, v.approvedAt, ...(v.history ?? []).map((h) => h.occurredAt)]).filter((x): x is string => typeof x === 'string');
    return { ruleSet: name, latestVersion: latest.version, latestStatus: latest.status, publishedVersion: published?.version ?? null, lastChangedAt: times.sort().at(-1) ?? latest.createdAt };
  }

  function listRuleSets(url: URL): FakeResponse {
    const query = pageQuery(url, 'rule-sets');
    if (!query.ok) return query.response;
    const names = [...new Set(store.map((v) => v.ruleSet))].sort().filter((name) => query.after === undefined || name > query.after);
    const pageNames = names.slice(0, query.limit);
    const hasNext = names.length > query.limit;
    const last = pageNames.at(-1);
    return json({ data: pageNames.map(summary), nextCursor: hasNext && last !== undefined ? cursorOf('rule-sets', last) : null, hasNext });
  }

  function listVersions(name: string, url: URL, request: FakeRequest): FakeResponse {
    if (versionsOf(name).length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
    const query = pageQuery(url, `versions:${name}`);
    if (!query.ok) return query.response;
    const after = query.after === undefined ? undefined : Number(query.after);
    const all = versionsOf(name).sort((a, b) => b.version - a.version).filter((v) => after === undefined || v.version < after);
    const items = all.slice(0, query.limit);
    const hasNext = all.length > query.limit;
    const last = items.at(-1);
    return json({ data: items.map((v) => lifecycle(v, request)), nextCursor: hasNext && last ? cursorOf(`versions:${name}`, String(last.version)) : null, hasNext });
  }

  function evaluate(name: string, request: FakeRequest): FakeResponse {
    if (request.body.length > EVALUATE_LIMIT) return problem(413, 'PAYLOAD_TOO_LARGE', `The document is larger than ${String(EVALUATE_LIMIT)} bytes`);
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
    if (path === '/rule-sets') return request.method === 'GET' ? listRuleSets(url) : importVersion(undefined, request);
    if (request.method === 'GET' && (m = /^\/rule-sets\/([^/]+)$/.exec(path))) {
      const name = m[1] ?? '';
      return versionsOf(name).length === 0 ? problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`) : json(summary(name));
    }
    if ((m = /^\/rule-sets\/([^/]+)\/versions$/.exec(path))) return request.method === 'GET' ? listVersions(m[1] ?? '', url, request) : importVersion(m[1], request);
    if ((m = /^\/rule-sets\/([^/]+)\/versions\/(\d+)(\/history|:request-publish|:approve|:reject)?$/.exec(path))) {
      const v = find(m[1] ?? '', Number(m[2]));
      if (!v) return problem(404, 'VERSION_NOT_FOUND', `Version ${m[2] ?? ''} of rule set ${m[1] ?? ''} not found`);
      const tail = m[3];
      if (request.method === 'GET' && tail === undefined) return view(v, request, url.searchParams.get('include') === 'source');
      if (request.method === 'GET' && tail === '/history') return json({ data: v.history ?? [] });
      if (request.method === 'POST' && tail?.startsWith(':')) return transition(v, request, tail.slice(1) as 'request-publish' | 'approve' | 'reject');
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
    { ruleSet: 'card-fee', version: 1, status: 'PUBLISHED', createdBy: 'lan', createdAt: '2026-11-01T01:00:00Z', requestedBy: 'lan', approvedBy: 'minh', source: JSON.stringify(CARD_FEE_V1, null, 2), definition: CARD_FEE_V1, ...base,
      history: [
        { action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: 'lan', reason: null, lockVersion: 0, occurredAt: '2026-11-01T01:00:00Z' },
        { action: 'REQUESTED', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actor: 'lan', reason: null, lockVersion: 1, occurredAt: '2026-11-01T02:00:00Z' },
        { action: 'APPROVED', fromStatus: 'PENDING_APPROVAL', toStatus: 'PUBLISHED', actor: 'minh', reason: 'Matches the tariff', lockVersion: 2, occurredAt: '2026-11-01T03:00:00Z' },
      ] },
    { ruleSet: 'card-fee', version: 2, status: 'PENDING_APPROVAL', lockVersion: 1, createdBy: 'lan', createdAt: '2026-11-18T08:00:00Z', requestedBy: 'lan', requestedAt: '2026-11-18T08:30:00Z', approvedBy: null, approvedAt: null, effectiveFrom: null, source: JSON.stringify(CARD_FEE_V2, null, 2), definition: CARD_FEE_V2,
      history: [
        { action: 'CREATED', fromStatus: null, toStatus: 'DRAFT', actor: 'lan', reason: null, lockVersion: 0, occurredAt: '2026-11-18T08:00:00Z' },
        { action: 'REQUESTED', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actor: 'lan', reason: null, lockVersion: 1, occurredAt: '2026-11-18T08:30:00Z' },
      ] },
    { ruleSet: 'kyc-risk', version: 1, status: 'PUBLISHED', createdBy: 'lan', createdAt: '2026-10-20T01:00:00Z', requestedBy: 'lan', approvedBy: 'minh', source: JSON.stringify(KYC_RISK_V1, null, 2), definition: KYC_RISK_V1, ...base },
  ];
}
