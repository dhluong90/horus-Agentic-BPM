/*
 * The audit reads of the in-memory Rule Engine (rule-engine/api/openapi.yaml): the decision log search, one
 * decision, its plain-language explanation in English or Vietnamese (built like the engine's message catalogue,
 * masked inputs kept masked), a rule set's history across its versions, and the evidence export with its SHA-256
 * stand-in, Content-Digest, Idempotency-Key replay and 422 EVIDENCE_TOO_LARGE. Used by the dev server, Vitest
 * and Playwright through createFakeEngine.
 */

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export interface StoredDecision {
  decisionId: string;
  ruleSet: string;
  version: number;
  outcome: 'OK' | 'UNIQUE_HIT_VIOLATION' | 'EVALUATION_ERROR';
  errorCode: string | null;
  inputs: Record<string, Json>;
  outputs: Record<string, Json> | null;
  matchedRuleIds: string[];
  correlationId: string | null;
  evaluatedAt: string;
}

/** What the audit reads need of a stored version, so this module does not depend on the version store's shape. */
export interface AuditVersion {
  ruleSet: string;
  version: number;
  status: string;
  createdBy: string;
  createdAt: string;
  requestedBy: string | null;
  requestedAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  effectiveFrom: string | null;
  canonicalHash: string;
  hitPolicy: string;
  inputs: { name: string; mask?: boolean }[];
  rules: { id: string; when: Record<string, string> }[];
  history: { action: string; fromStatus: string | null; toStatus: string; actor: string; reason: string | null; lockVersion: number; occurredAt: string }[];
}

export interface EvidenceEvent {
  action: 'EVIDENCE_EXPORTED';
  actor: string;
  occurredAt: string;
  evidence: { format: 'csv' | 'json'; versions: number[]; decisionIds: string[]; contentSha256: string; bytes: number };
}

export interface AuditOptions {
  decisions?: StoredDecision[];
  /** Largest evidence file in bytes; the engine's default is 8,388,608. */
  evidenceLimit?: number;
}

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** A decision id for the n-th generated decision: a well-formed 26-character ULID, stable across runs. */
function generatedId(n: number): string {
  let tail = '';
  let rest = n;
  for (let i = 0; i < 6; i += 1) {
    tail = (CROCKFORD[rest % 32] ?? '0') + tail;
    rest = Math.floor(rest / 32);
  }
  return `01KF3A${'0'.repeat(14)}${tail}`;
}

const MASKED_ID = 'hmac:k1:9f4a6c1e0b7d2c4a6e8f0a1b3c5d7e9f1a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d';
const vnd = (amount: number): Json => ({ currency: 'VND', amount });

/** Sao Mai Bank's decision log for November 2026: three named card-fee decisions, a failure, a kyc-risk case and 120 more. */
export function demoDecisions(): StoredDecision[] {
  const named: StoredDecision[] = [
    { decisionId: '01KF2Q8Z0RQ6V2Y8M1C4N5P7T9', ruleSet: 'card-fee', version: 1, outcome: 'OK', errorCode: null,
      inputs: { tier: 'GOLD', balance: vnd(60_000_000), txCount: 5, nationalId: MASKED_ID }, outputs: { fee: vnd(0), waived: true },
      matchedRuleIds: ['R2'], correlationId: 'LN-2026-0042', evaluatedAt: '2026-11-20T10:00:00Z' },
    { decisionId: '01KF2Q8Z0RQ6V2Y8M1C4N5P7TA', ruleSet: 'card-fee', version: 1, outcome: 'OK', errorCode: null,
      inputs: { tier: 'CLASSIC', balance: vnd(5_000_000), txCount: 25, nationalId: 'hmac:k1:2b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfe' }, outputs: { fee: vnd(15_000), waived: false },
      matchedRuleIds: ['R4'], correlationId: 'LN-2026-0057', evaluatedAt: '2026-11-19T09:15:00Z' },
    { decisionId: '01KF2Q8Z0RQ6V2Y8M1C4N5P7TB', ruleSet: 'card-fee', version: 1, outcome: 'OK', errorCode: null,
      inputs: { tier: 'CLASSIC', balance: vnd(2_000_000), txCount: 3, nationalId: null }, outputs: { fee: vnd(50_000), waived: false },
      matchedRuleIds: ['R5'], correlationId: 'LN-2026-0061', evaluatedAt: '2026-11-18T16:40:00Z' },
    { decisionId: '01KF2Q8Z0RQ6V2Y8M1C4N5P7TC', ruleSet: 'card-fee', version: 1, outcome: 'EVALUATION_ERROR', errorCode: 'EVALUATION_ERROR',
      inputs: { tier: 'GOLD', balance: null, txCount: 2, nationalId: MASKED_ID }, outputs: null,
      matchedRuleIds: [], correlationId: 'LN-2026-0063', evaluatedAt: '2026-11-17T11:05:00Z' },
    { decisionId: '01KF2Q8Z0RQ6V2Y8M1C4N5P7TD', ruleSet: 'kyc-risk', version: 1, outcome: 'OK', errorCode: null,
      inputs: { country: 'VN', monthlyIncome: vnd(250_000_000), pep: false }, outputs: { risk: 'MEDIUM' },
      matchedRuleIds: ['K2'], correlationId: 'LN-2026-0042', evaluatedAt: '2026-11-20T09:58:00Z' },
  ];
  const tiers = ['CLASSIC', 'GOLD', 'PLATINUM'] as const;
  const generated = Array.from({ length: 120 }, (_, n): StoredDecision => {
    const tier = tiers[n % 3] ?? 'CLASSIC';
    const rule = tier === 'PLATINUM' ? 'R1' : tier === 'GOLD' ? 'R3' : 'R5';
    const fee = tier === 'PLATINUM' ? 0 : tier === 'GOLD' ? 30_000 : 50_000;
    const day = 1 + (n % 16);
    return {
      decisionId: generatedId(n + 1), ruleSet: 'card-fee', version: 1, outcome: 'OK', errorCode: null,
      inputs: { tier, balance: vnd(1_000_000 * (1 + (n % 40))), txCount: n % 12, nationalId: MASKED_ID }, outputs: { fee: vnd(fee), waived: fee === 0 },
      matchedRuleIds: [rule], correlationId: `LN-2026-${String(100 + n).padStart(4, '0')}`,
      evaluatedAt: `2026-11-${String(day).padStart(2, '0')}T${String(8 + (n % 9)).padStart(2, '0')}:${String((n * 7) % 60).padStart(2, '0')}:00Z`,
    };
  });
  return [...named, ...generated];
}

export interface AuditResponse { status: number; headers: Record<string, string>; body: string }
export interface AuditRequest { method: string; headers: Record<string, string>; body: string; user: string; permissions: readonly string[] }

type Problem = (status: number, code: string, detail: string) => AuditResponse;
type Hash = (text: string) => string;

const json = (body: unknown): AuditResponse => ({ status: 200, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

/* ---------- explanation, as the engine's catalogue writes it (explain_en / explain_vi) ---------- */

const CATALOGUE = {
  en: {
    because: 'Rule {ruleId} of {ruleSet} version {version} matched because {conditions}.',
    always: 'Rule {ruleId} of {ruleSet} version {version} matched; it has no conditions.',
    result: 'Result: {outputs}.',
    noMatch: 'No rule of {ruleSet} version {version} matched. No output was produced.',
    error: 'The decision of {ruleSet} version {version} failed with {errorCode}. No output was produced.',
    value: '{input} is {value}', masked: '{input} is masked', missing: '{input} has no value',
    ge: 'which is at least {operand}', eq: 'which equals {operand}', in: 'which is one of {operand}', other: 'which satisfies {test}',
    joinConditions: ', and ', joinOutputs: '; ', nothing: 'no value',
  },
  vi: {
    because: 'Quy tắc {ruleId} của {ruleSet} phiên bản {version} khớp vì {conditions}.',
    always: 'Quy tắc {ruleId} của {ruleSet} phiên bản {version} khớp; quy tắc này không có điều kiện.',
    result: 'Kết quả: {outputs}.',
    noMatch: 'Không quy tắc nào của {ruleSet} phiên bản {version} khớp. Không có kết quả.',
    error: 'Quyết định theo {ruleSet} phiên bản {version} bị lỗi {errorCode}. Không có kết quả.',
    value: '{input} là {value}', masked: '{input} đã được che', missing: '{input} không có giá trị',
    ge: 'tức là ít nhất {operand}', eq: 'tức là bằng {operand}', in: 'tức là một trong {operand}', other: 'thỏa điều kiện {test}',
    joinConditions: ', và ', joinOutputs: '; ', nothing: 'không có giá trị',
  },
} as const;

const fill = (template: string, params: Record<string, string>) => template.replaceAll(/\{(\w+)\}/g, (whole, name: string) => params[name] ?? whole);

/** A value as the engine writes it in text: numbers without grouping, money in major units with its code. */
function plain(value: Json, nothing: string): string {
  if (value === null) return nothing;
  if (typeof value === 'object' && !Array.isArray(value) && typeof value['currency'] === 'string' && typeof value['amount'] === 'number') return `${String(value['amount'])} ${value['currency']}`;
  return typeof value === 'string' ? value : JSON.stringify(value);
}

function testText(test: string, words: (typeof CATALOGUE)['en' | 'vi']): string {
  const ge = /^>=\s*(.+)$/.exec(test);
  if (ge) return fill(words.ge, { operand: (ge[1] ?? '').replaceAll("'", '') });
  const eq = /^==\s*(.+)$/.exec(test);
  if (eq) return fill(words.eq, { operand: (eq[1] ?? '').replaceAll("'", '') });
  const list = /^in\s*\[(.*)\]$/.exec(test);
  if (list) return fill(words.in, { operand: (list[1] ?? '').replaceAll("'", '') });
  return fill(words.other, { test });
}

function explanation(d: StoredDecision, v: AuditVersion | undefined, language: 'en' | 'vi') {
  const words = CATALOGUE[language];
  const masked = new Set((v?.inputs ?? []).filter((input) => input.mask === true).map((input) => input.name));
  const isMasked = (name: string, value: Json) => masked.has(name) || (typeof value === 'string' && value.startsWith('hmac:'));
  const matched = d.matchedRuleIds.map((ruleId) => {
    const position = (v?.rules.findIndex((rule) => rule.id === ruleId) ?? -1) + 1;
    const when = v?.rules.find((rule) => rule.id === ruleId)?.when ?? {};
    const conditions = Object.entries(when).map(([input, test]) => {
      const value = d.inputs[input] ?? null;
      return { input, path: `/when/${input}`, test, value, masked: isMasked(input, value), missing: value === null, result: true };
    });
    return { ruleId, position: Math.max(position, 1), conditions, outputs: d.outcome === 'OK' ? (d.outputs ?? {}) : {} };
  });
  const reasons: { code: string; params: Record<string, Json> }[] = [];
  const sentences: string[] = [];
  const base = { ruleSet: d.ruleSet, version: String(d.version) };
  if (d.outcome === 'EVALUATION_ERROR') {
    reasons.push({ code: 'EVALUATION_ERROR', params: { errorCode: d.errorCode, ruleIds: d.matchedRuleIds, version: d.version } });
    sentences.push(fill(words.error, { ...base, errorCode: d.errorCode ?? '' }));
  } else if (matched.length === 0) {
    reasons.push({ code: 'NO_RULE_MATCHED', params: { ruleSet: d.ruleSet, version: d.version } });
    sentences.push(fill(words.noMatch, base));
  } else {
    for (const row of matched) {
      reasons.push({ code: 'RULE_MATCHED', params: { ruleId: row.ruleId, version: d.version } });
      const parts = row.conditions.map((c) => {
        if (c.masked) { reasons.push({ code: 'VALUE_MASKED', params: { ruleId: row.ruleId, input: c.input } }); return fill(words.masked, { input: c.input }); }
        if (c.missing) { reasons.push({ code: 'VALUE_MISSING', params: { ruleId: row.ruleId, input: c.input } }); return fill(words.missing, { input: c.input }); }
        return `${fill(words.value, { input: c.input, value: plain(c.value, words.nothing) })}, ${testText(c.test, words)}`;
      });
      sentences.push(parts.length === 0 ? fill(words.always, { ...base, ruleId: row.ruleId }) : fill(words.because, { ...base, ruleId: row.ruleId, conditions: parts.join(words.joinConditions) }));
    }
    const outputs = Object.entries(d.outputs ?? {}).map(([name, value]) => `${name} ${plain(value, words.nothing)}`).join(words.joinOutputs);
    if (outputs !== '') sentences.push(fill(words.result, { outputs }));
  }
  return {
    decisionId: d.decisionId, ruleSet: d.ruleSet, version: d.version, hitPolicy: (v?.hitPolicy ?? 'first').toUpperCase(), outcome: d.outcome, errorCode: d.errorCode,
    clock: d.evaluatedAt, matched, outputs: d.outputs, reasons, language, text: sentences.join(' '),
  };
}

function logged(d: StoredDecision, v: AuditVersion | undefined) {
  return {
    decisionId: d.decisionId, ruleSet: d.ruleSet, version: d.version, hitPolicy: (v?.hitPolicy ?? 'first').toUpperCase(), outcome: d.outcome, errorCode: d.errorCode,
    inputs: d.inputs, outputs: d.outputs, matchedRuleIds: d.matchedRuleIds, callerId: 'teller-app', onBehalfOf: null, correlationId: d.correlationId,
    evalClock: d.evaluatedAt, evaluatedAt: d.evaluatedAt, clockChosen: false, pinned: false, durationMicros: 412,
  };
}

/* ---------- history ---------- */

function historyLine(v: AuditVersion, next: AuditVersion | undefined) {
  const events = v.history;
  const rejected = [...events].reverse().find((e) => e.action === 'REJECTED');
  const approved = [...events].reverse().find((e) => e.action === 'APPROVED');
  const retired = events.find((e) => e.action === 'RETIRED');
  const effectiveTo = v.status === 'RETIRED' ? (retired?.occurredAt ?? next?.effectiveFrom ?? null) : null;
  return {
    version: v.version, status: v.status, effectiveFrom: v.effectiveFrom, effectiveTo, canonicalHash: v.canonicalHash,
    createdBy: v.createdBy, createdAt: v.createdAt, requestedBy: v.requestedBy, requestedAt: v.requestedAt,
    approvedBy: v.approvedBy, approvedAt: v.approvedAt, approvalComment: approved?.reason ?? null,
    rejectedBy: rejected?.actor ?? null, rejectedAt: rejected?.occurredAt ?? null, rejectionReason: rejected?.reason ?? null,
    events, eventsTruncated: false,
  };
}

/* ---------- the audit routes ---------- */

export function createAuditRoutes({ versions, problem, hash, now, options = {} }: {
  versions: () => AuditVersion[];
  problem: Problem;
  hash: Hash;
  now: () => string;
  options?: AuditOptions;
}) {
  const decisions = (options.decisions ?? demoDecisions()).map((d) => structuredClone(d));
  const exports: Record<string, EvidenceEvent[]> = {};
  const limit = options.evidenceLimit ?? 8_388_608;
  const versionOf = (name: string, version: number) => versions().find((v) => v.ruleSet === name && v.version === version);
  const can = (request: AuditRequest, permission: string) => request.permissions.includes(permission);
  const denied = (permission: string) => problem(403, 'PERMISSION_DENIED', `The caller lacks permission ${permission}`);

  function search(url: URL, request: AuditRequest): AuditResponse {
    if (!can(request, 'rules:log:read')) return denied('rules:log:read');
    const ruleSet = url.searchParams.get('ruleSet');
    const correlationId = url.searchParams.get('correlationId');
    const from = Date.parse(url.searchParams.get('from') ?? '');
    const to = Date.parse(url.searchParams.get('to') ?? '');
    if (ruleSet === null && correlationId === null) return problem(400, 'QUERY_INVALID', 'Give ruleSet or correlationId');
    if (correlationId !== null && !/^[A-Za-z0-9._:-]{1,128}$/.test(correlationId)) return problem(400, 'QUERY_INVALID', 'correlationId must be 1 to 128 of A-Z a-z 0-9 . _ : -');
    if (Number.isNaN(from) || Number.isNaN(to) || to <= from || to - from > 31 * 86_400_000) return problem(400, 'QUERY_INVALID', 'The period must be at most 31 days');
    const pageLimit = Number(url.searchParams.get('limit') ?? '200');
    const offset = Number(url.searchParams.get('cursor') ?? '0');
    const all = decisions
      .filter((d) => (ruleSet === null || d.ruleSet === ruleSet) && (correlationId === null || d.correlationId === correlationId))
      .filter((d) => Date.parse(d.evaluatedAt) >= from && Date.parse(d.evaluatedAt) < to)
      .sort((a, b) => b.evaluatedAt.localeCompare(a.evaluatedAt) || b.decisionId.localeCompare(a.decisionId));
    const items = all.slice(offset, offset + pageLimit);
    const hasNext = offset + pageLimit < all.length;
    return json({ data: items.map((d) => logged(d, versionOf(d.ruleSet, d.version))), nextCursor: hasNext ? String(offset + pageLimit) : null, hasNext });
  }

  function one(id: string, tail: string | undefined, url: URL, request: AuditRequest): AuditResponse {
    if (!can(request, 'rules:log:read')) return denied('rules:log:read');
    const d = decisions.find((item) => item.decisionId === id);
    if (!d) return problem(404, 'DECISION_NOT_FOUND', `Decision ${id} not found`);
    const v = versionOf(d.ruleSet, d.version);
    if (tail === undefined) return json(logged(d, v));
    const asked = url.searchParams.get('lang') ?? request.headers['accept-language'] ?? 'en';
    return json(explanation(d, v, asked.startsWith('vi') ? 'vi' : 'en'));
  }

  function history(name: string, request: AuditRequest): AuditResponse {
    if (!can(request, 'rules:read')) return denied('rules:read');
    const own = versions().filter((v) => v.ruleSet === name).sort((a, b) => b.version - a.version);
    if (own.length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
    const lines = own.map((v) => historyLine(v, own.find((other) => other.version === v.version + 1)));
    return json({ ruleSet: name, data: lines, ruleSetEvents: [...(exports[name] ?? [])].reverse(), ruleSetEventsTruncated: false, nextCursor: null, hasNext: false });
  }

  function evidence(name: string, request: AuditRequest): AuditResponse {
    if (!can(request, 'rules:read')) return denied('rules:read');
    const own = versions().filter((v) => v.ruleSet === name);
    if (own.length === 0) return problem(404, 'RULE_SET_NOT_FOUND', `Rule set ${name} not found`);
    let body: { format?: unknown; versions?: unknown; decisionIds?: unknown };
    try {
      body = JSON.parse(request.body) as typeof body;
    } catch {
      return problem(400, 'INPUT_INVALID', 'The body is not JSON');
    }
    const format = body.format === 'json' ? 'json' : body.format === 'csv' ? 'csv' : undefined;
    if (format === undefined) return problem(400, 'INPUT_INVALID', 'format must be csv or json');
    const numbers = Array.isArray(body.versions) ? (body.versions as number[]) : [];
    const ids = Array.isArray(body.decisionIds) ? (body.decisionIds as string[]) : [];
    if (ids.length > 0 && !can(request, 'rules:log:read')) return denied('rules:log:read');
    if (numbers.length === 0 && ids.length === 0) return problem(400, 'INPUT_INVALID', 'Name at least one version or one decision');
    if (ids.length > 100) return problem(400, 'INPUT_INVALID', `Choose at most 100 decisions; was ${String(ids.length)}`);
    const chosenVersions = [];
    for (const number of numbers) {
      const v = versionOf(name, number);
      if (!v) return problem(404, 'VERSION_NOT_FOUND', `Version ${String(number)} of rule set ${name} not found`);
      chosenVersions.push(v);
    }
    const chosenDecisions = [];
    for (const id of ids) {
      const d = decisions.find((item) => item.decisionId === id);
      if (!d) return problem(404, 'DECISION_NOT_FOUND', `Decision ${id} not found`);
      if (d.ruleSet !== name) return problem(400, 'INPUT_INVALID', `Decision ${id} belongs to rule set ${d.ruleSet}, not ${name}`);
      chosenDecisions.push(d);
    }
    const exportedAt = now();
    const columns = 'record,version,occurred_at,action,from_status,to_status,actor,reason,version_canonical_hash,decision_id,outcome,error_code,matched_rule_ids,inputs,outputs,correlation_id,eval_clock';
    const quote = (text: string) => (/[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text);
    const rows = [
      ...chosenVersions.flatMap((v) => v.history.map((e) => ['version event', String(v.version), e.occurredAt, e.action, e.fromStatus ?? '', e.toStatus, e.actor, e.reason ?? '', v.canonicalHash, '', '', '', '', '', '', '', ''])),
      ...chosenDecisions.map((d) => ['decision', String(d.version), d.evaluatedAt, '', '', '', '', '', '', d.decisionId, d.outcome, d.errorCode ?? '', d.matchedRuleIds.join(' '), JSON.stringify(d.inputs), JSON.stringify(d.outputs), d.correlationId ?? '', d.evaluatedAt]),
    ];
    const content = format === 'csv'
      ? [columns, ...rows.map((row) => row.map(quote).join(','))].join('\r\n') + '\r\n'
      : JSON.stringify({ ruleSet: name, versions: chosenVersions.map((v) => ({ version: v.version, status: v.status, canonicalHash: v.canonicalHash, events: v.history })), decisions: chosenDecisions.map((d) => logged(d, versionOf(d.ruleSet, d.version))) });
    const sha = hash(content);
    const file = format === 'csv'
      ? ['# HORUS Rule Engine evidence export', `# Rule set: ${name}`, '# Tenant: bank-a', `# Exported by: ${request.user}`, `# Exported at: ${exportedAt}`, `# Content SHA-256: ${sha}`].join('\r\n') + '\r\n' + content
      : `{"evidence":${JSON.stringify({ ruleSet: name, tenantId: 'bank-a', exportedBy: request.user, exportedAt, contentSha256: sha })},"content":${content}}`;
    if (file.length > limit) return problem(422, 'EVIDENCE_TOO_LARGE', `The evidence file would be larger than ${String(limit)} bytes; choose fewer decisions`);
    (exports[name] ??= []).push({ action: 'EVIDENCE_EXPORTED', actor: request.user, occurredAt: exportedAt, evidence: { format, versions: numbers, decisionIds: ids, contentSha256: sha, bytes: file.length } });
    const stamp = exportedAt.replaceAll(/[-:]/g, '').replace(/\.\d+/, '');
    return {
      status: 200,
      headers: {
        'Content-Type': format === 'csv' ? 'text/csv; charset=utf-8' : 'application/json',
        'Content-Disposition': `attachment; filename="${name}-evidence-${stamp}.${format}"`,
        'X-Evidence-Sha256': sha,
        'Content-Digest': `sha-256=:${btoa(hash(file).slice(0, 32))}:`,
      },
      body: file,
    };
  }

  /** Answers an audit path, or undefined when the path is not one. `path` is the part after /api/bpm/rules/v1. */
  function route(path: string, url: URL, request: AuditRequest): AuditResponse | undefined {
    let m: RegExpExecArray | null;
    if (request.method === 'GET' && path === '/decisions') return search(url, request);
    if (request.method === 'GET' && (m = /^\/decisions\/([^/:]+)(\/explain)?$/.exec(path))) return one(m[1] ?? '', m[2], url, request);
    if (request.method === 'GET' && (m = /^\/rule-sets\/([^/]+)\/history$/.exec(path))) return history(m[1] ?? '', request);
    if (request.method === 'POST' && (m = /^\/rule-sets\/([^/]+)\/evidence$/.exec(path))) return evidence(m[1] ?? '', request);
    return undefined;
  }

  return { route, decisions, exports };
}
